"""Browser-adapter regression: permissions, MediaRecorder, IndexedDB recovery and transport exclusion."""
import json
import os
import shutil
import time
import urllib.request
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'


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
    dialogs = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.on('dialog', lambda dialog: (dialogs.append(dialog.type), dialog.accept()))

    try:
        page.goto(BASE, wait_until='networkidle', timeout=60000)
        page.locator('canvas').first.wait_for(state='visible', timeout=30000)
        assert page.title() == 'Kasha'
        assert page.locator('#webApp').bounding_box()['width'] == 900
        assert page.evaluate('kashaPlatform.phase()') == 'idle'
        assert page.evaluate('kashaPlatform.pending()') is False

        # Пустая аварийная сессия не должна превращаться в вечный recovery.
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

        # Отказ getUserMedia не оставляет ложный pending и возвращает фактический idle.
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

        # Reload во время активной записи: уже записанные chunks остаются восстановимыми.
        assert page.evaluate('kashaPlatform.start()') == 'ok'
        page.wait_for_timeout(1300)
        assert page.evaluate('kashaPlatform.phase()') == 'recording'
        assert page.evaluate('kashaPlatform.consent()') is True
        page.reload(wait_until='networkidle')
        page.locator('canvas').first.wait_for(state='visible', timeout=30000)
        assert page.evaluate('kashaPlatform.phase()') == 'idle'
        assert page.evaluate('kashaPlatform.pending()') is True
        recovered_text = page.evaluate('(base) => kashaPlatform.recover(base)', BASE)
        assert not recovered_text.startswith('ERROR:'), recovered_text
        recovered = json.loads(recovered_text)
        assert recovered['id']
        assert page.evaluate('kashaPlatform.pending()') is False

        audio_url = BASE + '/api/captures/' + recovered['id'] + '/audio'
        assert page.evaluate('(url) => kashaPlatform.play(url, 0, 1)', audio_url) == 'ok'
        assert page.evaluate('kashaPlatform.pauseAudio()') == 'ok'
        assert page.evaluate('kashaPlatform.audioState().phase') == 'paused'
        blocked_paused_playback = page.evaluate('kashaPlatform.start()')
        assert blocked_paused_playback.startswith('ERROR:'), blocked_paused_playback
        page.evaluate('kashaPlatform.stopAudio()')

        # Pause/resume + отказ localhost transport: pending остаётся до подтверждённого receipt.
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
        receipt_text = page.evaluate('(base) => kashaPlatform.recover(base)', BASE)
        assert not receipt_text.startswith('ERROR:'), receipt_text
        receipt = json.loads(receipt_text)
        assert receipt['id']
        assert page.evaluate('kashaPlatform.pending()') is False

        # Активный recorder блокирует playback симметрично.
        audio_url = BASE + '/api/captures/' + receipt['id'] + '/audio'
        assert page.evaluate('kashaPlatform.start()') == 'ok'
        page.wait_for_timeout(700)
        blocked_play = page.evaluate('(url) => kashaPlatform.play(url, 0, 1)', audio_url)
        assert blocked_play.startswith('ERROR:'), blocked_play
        final_receipt = page.evaluate('(base) => kashaPlatform.stop(base)', BASE)
        assert not final_receipt.startswith('ERROR:'), final_receipt
        assert page.evaluate('kashaPlatform.pending()') is False

        assert not errors, errors
        print('WEB ADAPTER BROWSER PASSED')
    finally:
        browser.close()
