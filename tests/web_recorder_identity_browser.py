"""Real MediaRecorder/IndexedDB checks for journal ownership and identity-aware cancellation."""
import json
import os
import shutil
import time
import urllib.request
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'

def api(path, value=None):
    request = urllib.request.Request(BASE + '/api/' + path,
        data=json.dumps(value).encode() if value is not None else None,
        headers={'X-Kasha-Client': 'web', 'Content-Type': 'application/json'},
        method='PUT' if value is not None else 'GET')
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)

for _ in range(90):
    try:
        prefs = api('preferences')
        break
    except Exception:
        time.sleep(1)
else:
    raise AssertionError('Local runtime unavailable')
prefs['autoRecord'] = False
api('preferences', prefs)
before = api('snapshot')['captures']

with sync_playwright() as pw:
    browser = pw.chromium.launch(
        executable_path=os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium'),
        headless=True, args=['--no-sandbox', '--use-fake-device-for-media-stream',
            '--use-fake-ui-for-media-stream', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'])
    # Match the existing suites: all share the same local test store and its first project.
    page = browser.new_page(locale='ru-RU')
    page.on('dialog', lambda dialog: dialog.accept())
    uploads = []
    page.on('request', lambda request: uploads.append(request.url)
        if request.method == 'POST' and '/api/captures/audio' in request.url else None)
    try:
        page.goto(BASE, wait_until='networkidle')
        page.locator('canvas').first.wait_for(state='visible')
        # Hold startup after the journal was created but before MediaRecorder.start().
        page.evaluate('''() => {
            const original = AudioContext.prototype.resume;
            AudioContext.prototype.resume = function() {
                const self = this;
                return new Promise(resolve => { globalThis.releaseStart = () => {
                    AudioContext.prototype.resume = original;
                    original.call(self).then(resolve);
                }; });
            };
            globalThis.started = kashaPlatform.start();
        }''')
        page.wait_for_function('typeof releaseStart === "function"')
        assert page.evaluate('kashaPlatform.pending()') is False
        assert json.loads(page.evaluate('kashaPlatform.pendingRecordings()')) == []
        page.evaluate('releaseStart()')
        assert page.evaluate('started') == 'ok'
        session = json.loads(page.evaluate('kashaPlatform.sessionState()'))
        assert session['phase'] == 'RECORDING' and session['activeSessionId']
        source_id = session['activeSessionId']
        page.wait_for_function('''async id => {
            const db = await new Promise(resolve => {
                const r = indexedDB.open('kasha-audio-v1', 1); r.onsuccess = () => resolve(r.result);
            });
            try {
                const all = name => new Promise(resolve => {
                    const r = db.transaction(name).objectStore(name).getAll(); r.onsuccess = () => resolve(r.result);
                });
                return (await all('sessions')).some(s => s.id === id) &&
                    (await all('chunks')).some(c => c.id === id && c.blob.size > 0);
            } finally { db.close(); }
        }''', arg=source_id, timeout=15000)
        assert page.evaluate("kashaPlatform.cancel('wrong-session')").startswith('ERROR:')
        assert page.evaluate('kashaPlatform.phase()') == 'recording'
        assert page.evaluate('kashaPlatform.pause()') == 'ok'
        assert page.evaluate('(id) => kashaPlatform.cancel(id)', source_id) == 'ok'
        assert page.evaluate('kashaPlatform.phase()') == 'idle'
        assert page.evaluate('kashaPlatform.pending()') is False
        assert page.evaluate('(id) => kashaPlatform.cancel(id)', source_id) == 'ok'
        assert page.evaluate('kashaPlatform.start()') == 'ok'
        second = json.loads(page.evaluate('kashaPlatform.sessionState()'))['activeSessionId']
        assert second != source_id
        assert page.evaluate('(id) => kashaPlatform.cancel(id)', source_id).startswith('ERROR:')
        assert page.evaluate('kashaPlatform.phase()') == 'recording'
        assert page.evaluate('(id) => kashaPlatform.cancel(id)', second) == 'ok'
        assert not uploads, uploads
        assert api('snapshot')['captures'] == before, 'Cancel must not enqueue a capture'
        print('WEB RECORDER IDENTITY PASSED')
    finally:
        browser.close()
