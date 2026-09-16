"""ТЗ 4.1: production adapter + настоящая IndexedDB; сбои вводятся только на границе API."""
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
import urllib.request

from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'
OUT = Path('test-output/storage-retry')
OUT.mkdir(parents=True, exist_ok=True)

for _ in range(90):
    try:
        with urllib.request.urlopen(BASE + '/api/health', timeout=2) as response:
            assert json.load(response)['localOnly'] is True
        break
    except Exception:
        time.sleep(1)
else:
    raise AssertionError('Локальный runtime не запущен')

SEED = """async () => {
    const open = (name, version, upgrade) => new Promise((resolve, reject) => {
        const r = indexedDB.open(name, version);
        r.onupgradeneeded = () => upgrade?.(r.result);
        r.onsuccess = () => resolve(r.result);
        r.onerror = () => reject(r.error);
    });
    // Returning a real VersionError request later simulates one unavailable open,
    // while the original audio database remains untouched.
    (await open('kasha-storage-error-fixture', 2)).close();
    const db = await open('kasha-audio-v1', 1, d => {
        d.createObjectStore('sessions', {keyPath: 'id'});
        d.createObjectStore('chunks', {keyPath: ['id', 'index']});
    });
    try {
        await new Promise((resolve, reject) => {
            const tx = db.transaction(['sessions', 'chunks'], 'readwrite');
            tx.objectStore('sessions').put({id: 'kept', created: 7, mime: 'audio/webm'});
            // Another tab may commit its session before its first audio chunk.
            tx.objectStore('sessions').put({id: 'awaiting-chunk', created: 8, mime: 'audio/webm'});
            tx.objectStore('chunks').put({id: 'kept', index: 0, blob: new Blob(['original audio'])});
            tx.oncomplete = resolve;
            tx.onerror = tx.onabort = () => reject(tx.error || Error('Seed aborted'));
        });
    } finally { db.close(); }
}"""

SNAPSHOT = """async () => {
    const db = await new Promise((resolve, reject) => {
        const r = indexedDB.open('kasha-audio-v1', 1);
        r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error);
    });
    try {
        const [sessions, chunks] = await new Promise((resolve, reject) => {
            const tx = db.transaction(['sessions', 'chunks']);
            const a = tx.objectStore('sessions').getAll(), b = tx.objectStore('chunks').getAll();
            tx.oncomplete = () => resolve([a.result, b.result]);
            tx.onerror = tx.onabort = () => reject(tx.error || Error('Read aborted'));
        });
        return {sessions, chunks: await Promise.all(chunks.map(async c => ({
            id: c.id, index: c.index, bytes: Array.from(new Uint8Array(await c.blob.arrayBuffer()))
        })))};
    } finally { db.close(); }
}"""

checks, errors = [], []
with tempfile.TemporaryDirectory(prefix='kasha-storage-profile-') as profile, sync_playwright() as pw:
    executable = os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium')

    def launch():
        context = pw.chromium.launch_persistent_context(
            profile, executable_path=executable, headless=True, args=['--no-sandbox'])
        page = context.pages[0] if context.pages else context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        # Real origin/IndexedDB, but no Compose, AI or other adapter instance on this probe page.
        page.route(BASE + '/storage-retry-probe', lambda route: route.fulfill(
            content_type='text/html', body='<!doctype html><title>Kasha storage regression</title>'))
        page.goto(BASE + '/storage-retry-probe', wait_until='load')
        return context, page

    context, page = launch()
    try:
        page.evaluate(SEED)
        original = page.evaluate(SNAPSHOT)
        assert len(original['sessions']) == 2 and len(original['chunks']) == 1
        assert bytes(original['chunks'][0]['bytes']) == b'original audio'
        page.evaluate("""() => {
            const nativeOpen = indexedDB.open.bind(indexedDB);
            let failOnce = true;
            globalThis.storageOpenAttempts = 0;
            indexedDB.open = (name, version) => {
                if (name === 'kasha-audio-v1') {
                    storageOpenAttempts++;
                    if (failOnce) { failOnce = false; return nativeOpen('kasha-storage-error-fixture', 1); }
                }
                return nativeOpen(name, version);
            };
            globalThis.restoreOpen = () => { indexedDB.open = nativeOpen; };
        }""")
        page.add_script_tag(url=BASE + '/browser-platform.js')
        failed = page.evaluate('() => kashaPlatform.pendingRecordings()')
        assert failed.startswith('ERROR:'), failed
        assert page.evaluate('storageOpenAttempts') == 1
        pending = json.loads(page.evaluate('() => kashaPlatform.pendingRecordings()'))
        assert pending == [{'id': 'kept', 'createdAt': 7}], pending
        assert page.evaluate('storageOpenAttempts') == 2
        page.evaluate('restoreOpen()')
        assert page.evaluate(SNAPSHOT) == original
        checks.append('real IndexedDB open error -> Retry -> original session and exact audio bytes')
        checks.append('pending scan leaves a session awaiting its first chunk untouched')

        page.evaluate("""() => {
            const nativeGetAll = IDBObjectStore.prototype.getAll;
            let failOnce = true;
            globalThis.storageReadAborts = 0;
            IDBObjectStore.prototype.getAll = function(...args) {
                const request = nativeGetAll.apply(this, args);
                if (failOnce && this.name === 'chunks' && this.transaction.db.name === 'kasha-audio-v1') {
                    failOnce = false;
                    const tx = this.transaction;
                    request.addEventListener('success', () => { storageReadAborts++; tx.abort(); }, {once: true});
                }
                return request;
            };
            globalThis.restoreGetAll = () => { IDBObjectStore.prototype.getAll = nativeGetAll; };
        }""")
        failed = page.evaluate('() => kashaPlatform.pendingRecordings()')
        assert failed.startswith('ERROR:'), failed
        assert page.evaluate('storageReadAborts') == 1
        page.evaluate('restoreGetAll()')
        assert page.evaluate(SNAPSHOT) == original
        assert json.loads(page.evaluate('() => kashaPlatform.pendingRecordings()')) == pending
        checks.append('real transaction abort after getAll success never reconciles an incomplete read')

        context.close()
        context, page = launch()  # New Chromium process, same isolated on-disk profile.
        page.add_script_tag(url=BASE + '/browser-platform.js')
        for _ in range(2):
            assert json.loads(page.evaluate('() => kashaPlatform.pendingRecordings()')) == pending
            assert page.evaluate(SNAPSHOT) == original
        checks.append('browser process restart and repeated recovery preserve journal without duplicates')
        # Corrupt a real on-disk journal by leaving indices 0 and 2. Recovery must
        # fail before fetch, not acknowledge a shortened file and clear the source.
        def put_chunk(index, text):
            page.evaluate("""async ({index, text}) => {
                const db = await new Promise((resolve, reject) => {
                    const r = indexedDB.open('kasha-audio-v1', 1);
                    r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error);
                });
                try {
                    await new Promise((resolve, reject) => {
                        const tx = db.transaction('chunks', 'readwrite');
                        tx.objectStore('chunks').put({id: 'kept', index, blob: new Blob([text])});
                        tx.oncomplete = resolve;
                        tx.onerror = tx.onabort = () => reject(tx.error || Error('Fixture write aborted'));
                    });
                } finally { db.close(); }
            }""", {'index': index, 'text': text})

        def observe_uploads():
            # Only the HTTP boundary is controlled. IndexedDB, Blob, FormData,
            # adapter execution and browser process restart remain real.
            page.evaluate("""() => {
                globalThis.recoveryUploads = [];
                globalThis.originalRecoveryFetch = globalThis.fetch;
                globalThis.fetch = async (_url, options) => {
                    const bytes = Array.from(new Uint8Array(await options.body.get('audio').arrayBuffer()));
                    recoveryUploads.push(bytes);
                    return new Response('Runtime temporarily unavailable', {status: 503});
                };
            }""")

        put_chunk(2, 'last part')
        damaged = page.evaluate(SNAPSHOT)
        assert [c['index'] for c in damaged['chunks']] == [0, 2]
        observe_uploads()
        for _ in range(2):
            failed = page.evaluate('() => kashaPlatform.recover(location.origin, "kept")')
            assert failed.startswith('ERROR:Audio journal is incomplete or corrupt'), failed
            assert page.evaluate('recoveryUploads') == []
            assert page.evaluate(SNAPSHOT) == damaged
        checks.append('gap in the real IndexedDB journal: repeated recovery does not upload or erase source')

        context.close()
        context, page = launch()
        page.add_script_tag(url=BASE + '/browser-platform.js')
        observe_uploads()
        failed = page.evaluate('() => kashaPlatform.recover(location.origin, "kept")')
        assert failed.startswith('ERROR:Audio journal is incomplete or corrupt'), failed
        assert page.evaluate('recoveryUploads') == []
        assert page.evaluate(SNAPSHOT) == damaged
        checks.append('damaged journal and exact bytes survive Chromium process restart')

        put_chunk(1, 'restored middle')
        repaired = page.evaluate(SNAPSHOT)
        assert [c['index'] for c in repaired['chunks']] == [0, 1, 2]
        failed = page.evaluate('() => kashaPlatform.recover(location.origin, "kept")')
        assert failed == 'ERROR:Runtime temporarily unavailable', failed
        assert page.evaluate('recoveryUploads') == [list(b'original audiorestored middlelast part')]
        assert page.evaluate(SNAPSHOT) == repaired
        page.evaluate('() => { globalThis.fetch = originalRecoveryFetch; }')
        checks.append('Retry rereads a repaired journal; rejected upload retains every original fragment')
        assert not errors, errors
        (OUT / 'result.json').write_text(json.dumps(
            {'passed': True, 'checks': checks, 'pageErrors': errors}, ensure_ascii=False, indent=2))
        print('WEB STORAGE RETRY BROWSER PASSED')
    finally:
        (OUT / 'page-errors.json').write_text(json.dumps(errors, ensure_ascii=False, indent=2))
        context.close()
