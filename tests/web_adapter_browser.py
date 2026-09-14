"""Browser-adapter regression: permissions, MediaRecorder, IndexedDB recovery and transport exclusion."""
import json
import os
import shutil
import time
import urllib.request
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'
WORKING = {'RECORDING', 'QUEUED', 'TRANSCRIBING', 'COMPACTING', 'POLISHING'}


def api(path, data=None, method=None):
    headers = {'X-Kasha-Client': 'web'}
    if data is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(
        BASE + '/api/' + path,
        data=json.dumps(data).encode() if data is not None else None,
        headers=headers,
        method=method,
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def wait_capture_idle(capture_id, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        capture = next((c for c in api('snapshot')['captures'] if c['id'] == capture_id), None)
        if capture is None or capture['status'] not in WORKING:
            return capture
        time.sleep(0.1)
    raise AssertionError('Capture не завершил обработку перед discard: ' + capture_id)


for _ in range(90):
    try:
        health = api('health')
        assert health['localOnly'] is True
        break
    except Exception:
        time.sleep(1)
else:
    raise AssertionError('Локальный runtime не запущен')

prefs = api('preferences')
prefs['autoRecord'] = False
api('preferences', prefs, 'PUT')

with sync_playwright() as pw:
    executable = os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium')
    browser = pw.chromium.launch(
        executable_path=executable,
        headless=True,
        args=[
            '--no-sandbox',
            '--use-fake-device-for-media-stream',
            '--use-fake-ui-for-media-stream',
            '--use-angle=swiftshader',
            '--enable-unsafe-swiftshader',
        ],
    )
    page = browser.new_page(viewport={'width': 900, 'height': 700}, locale='ru-RU')
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.on('dialog', lambda dialog: dialog.accept())

    def persisted_recording():
        return page.evaluate('''async () => {
            const db = await new Promise((resolve, reject) => {
                const r = indexedDB.open('kasha-audio-v1', 1);
                r.onsuccess = () => resolve(r.result);
                r.onerror = () => reject(r.error);
            });
            try {
                const all = store => new Promise((resolve, reject) => {
                    const r = db.transaction(store).objectStore(store).getAll();
                    r.onsuccess = () => resolve(r.result);
                    r.onerror = () => reject(r.error);
                });
                const sessions = (await all('sessions')).sort((a, b) => a.created - b.created);
                const saved = sessions[0];
                if (!saved) return null;
                const chunks = (await all('chunks')).filter(x => x.id === saved.id && x.blob?.size > 0);
                return {id: saved.id, chunks: chunks.length};
            } finally {
                db.close();
            }
        }''')

    try:
        page.goto(BASE, wait_until='networkidle', timeout=60000)
        page.locator('canvas').first.wait_for(state='visible', timeout=30000)
        assert page.title() == 'Kasha'
        assert page.locator('#webApp').bounding_box()['width'] == 900
        assert page.evaluate('kashaPlatform.phase()') == 'idle'
        assert page.evaluate('kashaPlatform.pending()') is False

        empty_left = page.evaluate('''async () => {
            const db = await new Promise((resolve, reject) => {
                const r = indexedDB.open('kasha-audio-v1', 1);
                r.onsuccess = () => resolve(r.result);
                r.onerror = () => reject(r.error);
            });
            await new Promise((resolve, reject) => {
                const tx = db.transaction('sessions', 'readwrite');
                tx.objectStore('sessions').put({id:'empty-regression', mime:'audio/webm', created:0});
                tx.oncomplete = resolve;
                tx.onerror = () => reject(tx.error);
            });
            const pending = await kashaPlatform.pending();
            const sessions = await new Promise((resolve, reject) => {
                const r = db.transaction('sessions').objectStore('sessions').getAll();
                r.onsuccess = () => resolve(r.result);
                r.onerror = () => reject(r.error);
            });
            db.close();
            return {pending, exists: sessions.some(x => x.id === 'empty-regression')};
        }''')
        assert empty_left == {'pending': False, 'exists': False}, empty_left

        denied = page.evaluate('''async () => {
            const mediaDevices = navigator.mediaDevices;
            const original = mediaDevices.getUserMedia.bind(mediaDevices);
            mediaDevices.getUserMedia = async () => { throw new DOMException('Permission denied', 'NotAllowedError'); };
            try {
                const result = await kashaPlatform.start();
                return {result, pending: await kashaPlatform.pending(), phase: kashaPlatform.phase()};
            } finally {
                mediaDevices.getUserMedia = original;
            }
        }''')
        assert denied['result'].startswith('ERROR:'), denied
        assert denied['pending'] is False, denied
        assert denied['phase'] == 'idle', denied

        assert page.evaluate('kashaPlatform.start()') == 'ok'
        source = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            source = persisted_recording()
            if source and source['chunks'] > 0:
                break
            page.wait_for_timeout(100)
        assert source and source['chunks'] > 0, 'MediaRecorder не записал ни одного persisted chunk'
        source_id = source['id']
        assert page.evaluate('kashaPlatform.phase()') == 'recording'
        assert page.evaluate('kashaPlatform.consent()') is True
        page.reload(wait_until='networkidle')
        page.locator('canvas').first.wait_for(state='visible', timeout=30000)
        assert page.evaluate('kashaPlatform.phase()') == 'idle'

        recovered = None
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            recovered = next((c for c in api('snapshot')['captures'] if c['id'] == source_id), None)
            if recovered:
                break
            page.wait_for_timeout(100)
        assert recovered is not None, 'Автоматическое recovery не опубликовало исходный browser session id'
        assert page.evaluate('kashaPlatform.pending()') is False

        first_audio_url = BASE + '/api/captures/' + recovered['id'] + '/audio'
        assert page.evaluate('(url) => kashaPlatform.play(url, 0, 1)', first_audio_url) == 'ok'
        assert page.evaluate('kashaPlatform.pauseAudio()') == 'ok'
        assert page.evaluate('kashaPlatform.audioState().phase') == 'paused'
        assert page.evaluate('kashaPlatform.start()').startswith('ERROR:')
        page.evaluate('kashaPlatform.stopAudio()')

        assert page.evaluate('kashaPlatform.start()') == 'ok'
        page.wait_for_timeout(1200)
        assert page.evaluate('kashaPlatform.pause()') == 'ok'
        assert page.evaluate('kashaPlatform.phase()') == 'paused'
        assert page.evaluate('kashaPlatform.resume()') == 'ok'
        page.wait_for_timeout(700)
        page.route('**/api/captures/audio', lambda route: route.abort())
        failed = page.evaluate('(base) => kashaPlatform.stop(base)', BASE)
        assert failed.startswith('ERROR:'), failed
        assert page.evaluate('kashaPlatform.phase()') == 'idle'
        assert page.evaluate('kashaPlatform.pending()') is True
        page.unroute('**/api/captures/audio')

        page.reload(wait_until='networkidle')
        assert page.evaluate('kashaPlatform.pending()') is True
        wait_capture_idle(recovered['id'])
        api('captures/' + recovered['id'], method='DELETE')
        receipt_text = page.evaluate('(base) => kashaPlatform.recover(base)', BASE)
        assert not receipt_text.startswith('ERROR:'), receipt_text
        receipt = json.loads(receipt_text)
        assert receipt['id']
        assert page.evaluate('kashaPlatform.pending()') is False

        second_audio_url = BASE + '/api/captures/' + receipt['id'] + '/audio'
        assert page.evaluate('kashaPlatform.start()') == 'ok'
        page.wait_for_timeout(700)
        assert page.evaluate('(url) => kashaPlatform.play(url, 0, 1)', second_audio_url).startswith('ERROR:')
        runtime_rejected = page.evaluate('(base) => kashaPlatform.stop(base)', BASE)
        assert runtime_rejected.startswith('ERROR:'), runtime_rejected
        assert page.evaluate('kashaPlatform.pending()') is True
        wait_capture_idle(receipt['id'])
        api('captures/' + receipt['id'], method='DELETE')
        final_receipt_text = page.evaluate('(base) => kashaPlatform.recover(base)', BASE)
        assert not final_receipt_text.startswith('ERROR:'), final_receipt_text
        final_receipt = json.loads(final_receipt_text)
        assert final_receipt['id']
        assert page.evaluate('kashaPlatform.pending()') is False

        wait_capture_idle(final_receipt['id'])
        api('captures/' + final_receipt['id'], method='DELETE')
        assert not api('snapshot')['captures']
        assert not errors, errors
        print('WEB ADAPTER BROWSER PASSED')
    finally:
        browser.close()
