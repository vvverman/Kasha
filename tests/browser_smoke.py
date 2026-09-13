"""Настоящий Chrome, реальный runtime, синтетический микрофон. Веса моделей не используются."""
import hashlib
import json
import os
import pathlib
import shutil
import time
import urllib.request
from contextlib import suppress
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'
OUT = pathlib.Path(os.getenv('KASHA_TEST_OUTPUT', 'test-output'))
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'browser-result.json').unlink(missing_ok=True)


def api(path, data=None):
    request = urllib.request.Request(BASE + '/api/' + path, headers={'X-Kasha-Client': 'web'})
    if data is not None:
        request.data = json.dumps(data).encode()
        request.add_header('Content-Type', 'application/json')
        request.method = 'PUT' if path.endswith('/draft') else 'POST'
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


for _ in range(90):
    try:
        api('health')
        break
    except Exception:
        time.sleep(1)
else:
    raise AssertionError('Локальный сервис не запущен')

with sync_playwright() as p:
    executable = os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium')
    browser = p.chromium.launch(executable_path=executable, headless=True, args=[
        '--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
        '--use-angle=swiftshader', '--enable-unsafe-swiftshader'])
    context = browser.new_context(viewport={'width': 1440, 'height': 900}, locale='ru-RU')
    page = context.new_page()
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))

    def wait_until(check, description, timeout=30):
        # evaluate дожидается Promise. В закреплённой версии wait_for_function
        # принимает сам Promise за truthy, даже если тот разрешается в false.
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = check()
            if value:
                return value
            page.wait_for_timeout(100)
        raise AssertionError('Не дождались: ' + description)

    def click_button(name):
        # Узел семантики находится под canvas. Щёлкаем мышью по его координатам,
        # не вызываем обработчики приложения напрямую и не отключаем проверки.
        button = page.get_by_role('button', name=name, exact=True)
        button.wait_for(state='visible', timeout=30000)
        box = button.bounding_box()
        assert box and box['width'] > 0 and box['height'] > 0, name
        page.mouse.click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2)

    def capture_by_id(capture_id):
        return next((item for item in api('snapshot')['captures'] if item['id'] == capture_id), None)

    try:
        initial_ids = {item['id'] for item in api('snapshot')['captures']}
        page.goto(BASE, wait_until='networkidle', timeout=60000)
        page.locator('canvas').first.wait_for(state='visible', timeout=30000)
        click_button('Пока без записи')
        page.get_by_role('button', name='Пока без записи', exact=True).wait_for(state='hidden')
        assert page.locator('#webApp').bounding_box()['width'] == 430
        page.screenshot(path=str(OUT / 'desktop-mobile-layout.png'))

        click_button('Записать')
        page.wait_for_function('kashaPlatform.phase() === "recording"')
        page.wait_for_timeout(1600)
        click_button('Пауза')
        page.wait_for_function('kashaPlatform.phase() === "paused"')
        click_button('Продолжить')
        page.wait_for_function('kashaPlatform.phase() === "recording"')
        page.route('**/api/captures/audio', lambda route: route.abort())
        click_button('Готово')
        page.wait_for_function('kashaPlatform.phase() === "idle"')
        wait_until(lambda: page.evaluate('kashaPlatform.pending()'), 'аудио осталось в браузере')
        click_button('Понятно')
        assert {item['id'] for item in api('snapshot')['captures']} == initial_ids
        page.unroute('**/api/captures/audio')
        page.reload(wait_until='networkidle')
        page.get_by_role('button', name='Повторить отправку', exact=True).wait_for(state='visible')
        assert page.evaluate('kashaPlatform.phase()') == 'idle'
        assert page.evaluate('kashaPlatform.pending()') is True

        # Контрольная сумма сохранённых фрагментов до повторной отправки.
        pending_source = page.evaluate('''async () => {
            const db = await new Promise((resolve, reject) => {
                const r = indexedDB.open('kasha-audio-v1', 1);
                r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error);
            });
            try {
                const all = store => new Promise((resolve, reject) => {
                    const r = db.transaction(store).objectStore(store).getAll();
                    r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error);
                });
                const saved = (await all('sessions')).sort((a,b) => a.created - b.created)[0];
                const chunks = (await all('chunks')).filter(x => x.id === saved.id).sort((a,b) => a.index - b.index);
                const bytes = await new Blob(chunks.map(x => x.blob)).arrayBuffer();
                const digest = await crypto.subtle.digest('SHA-256', bytes);
                return {id: saved.id, size: bytes.byteLength,
                    sha256: Array.from(new Uint8Array(digest), n => n.toString(16).padStart(2, '0')).join('')};
            } finally { db.close(); }
        }''')
        assert pending_source['size'] > 100
        with page.expect_response(lambda response: response.url.endswith('/api/captures/audio') and response.request.method == 'POST') as saved:
            click_button('Повторить отправку')
        assert saved.value.status == 201, saved.value.text()
        receipt = saved.value.json()
        assert receipt['id'] == pending_source['id']
        wait_until(lambda: not page.evaluate('kashaPlatform.pending()'), 'очередь очищена только после квитанции')
        capture = wait_until(lambda: capture_by_id(receipt['id']), 'запись опубликована сервером')
        wait_until(lambda: capture_by_id(capture['id'])['status'] == 'NEEDS_MODEL', 'явный статус отсутствующей модели')
        capture = capture_by_id(capture['id'])
        assert not capture['transcript']
        assert len(api('snapshot')['captures']) == len(initial_ids) + 1
        with urllib.request.urlopen(BASE + '/api/captures/' + capture['id'] + '/audio') as response:
            audio_bytes = response.read()
        assert len(audio_bytes) == pending_source['size']
        assert hashlib.sha256(audio_bytes).hexdigest() == pending_source['sha256']
        page.screenshot(path=str(OUT / 'recording-source.png'))

        project = api('projects', {'title': 'Тестовый проект', 'instruction': 'Мысли про интерфейс'})
        api('captures/' + capture['id'] + '/draft', {'title': 'Первая мысль', 'text': 'Старый текст  '})
        note = api('captures/' + capture['id'] + '/distribute', {'projectId': project['id']})
        repeated = api('captures/' + capture['id'] + '/distribute', {'projectId': project['id']})
        assert note['id'] == repeated['id'] and note['body'] == 'Старый текст  '

        page.set_viewport_size({'width': 390, 'height': 844})
        page.wait_for_timeout(800)
        assert page.locator('#webApp').bounding_box()['width'] == 390
        page.screenshot(path=str(OUT / 'mobile-layout.png'))
        assert not errors, errors
        checks = ['render-430-and-390', 'record-pause-resume', 'failed-upload-keeps-audio',
                  'reload-and-recover', 'saved-audio-sha256-matches', 'missing-model-honesty',
                  'project-and-idempotent-distribution']
        (OUT / 'browser-result.json').write_text(json.dumps({
            'passed': True, 'checks': checks, 'pageErrors': errors,
            'audioBytes': len(audio_bytes), 'audioSha256': pending_source['sha256']
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print('BROWSER SMOKE PASSED: 7 проверок, без настоящих весов моделей')
    finally:
        # Диагностика не должна скрывать исходную ошибку теста при закрытии страницы.
        with suppress(Exception):
            (OUT / 'browser.html').write_text(page.content(), encoding='utf-8')
        with suppress(Exception):
            (OUT / 'accessibility.txt').write_text(page.locator('body').aria_snapshot(), encoding='utf-8')
        (OUT / 'page-errors.json').write_text(json.dumps(errors, ensure_ascii=False), encoding='utf-8')
        with suppress(Exception):
            page.screenshot(path=str(OUT / 'last-screen.png'))
        browser.close()
