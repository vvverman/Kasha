"""ТЗ 6.4: настоящий общий Wasm UI с контролируемыми ответами порта готовности."""
import json
import os
import pathlib
import shutil
import time
import urllib.request
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'
OUT = pathlib.Path('test-output/ai-readiness')
OUT.mkdir(parents=True, exist_ok=True)


def api(path, value=None):
    headers = {'X-Kasha-Client': 'web'}
    if value is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(BASE + '/api/' + path,
        data=None if value is None else json.dumps(value).encode(), headers=headers,
        method='GET' if value is None else 'PUT')
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


original = api('preferences')
selection = {'speechToText': 'local.default.stt', 'text': 'local.default.text', 'routing': 'local.default.text'}
configured = dict(original, language='ru', theme='light', autoRecord=False, ai=selection)
api('preferences', configured)
try:
    with sync_playwright() as pw:
        executable = os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium')
        browser = pw.chromium.launch(executable_path=executable, headless=True,
            args=['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'])
        page = browser.new_page(viewport={'width': 1280, 'height': 1000}, locale='ru-RU')
        errors, external, requests = [], [], []
        mode = {'ready': False}
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.on('request', lambda request: external.append(request.url)
                if request.url.startswith(('http://', 'https://')) and not request.url.startswith(BASE + '/') else None)

        def answer(route):
            selected = route.request.post_data_json
            requests.append(selected)
            reasons = {'SPEECH_TO_TEXT': 'runtimeUnavailable', 'TEXT': 'modelNotInstalled', 'ROUTING': 'languageUnsupported'}
            fields = {'SPEECH_TO_TEXT': 'speechToText', 'TEXT': 'text', 'ROUTING': 'routing'}
            result = [{'role': role, 'selectedEngineId': selected[field], 'executable': mode['ready'],
                       'reason': None if mode['ready'] else reasons[role]} for role, field in fields.items()]
            route.fulfill(status=200, content_type='application/json', body=json.dumps(result))

        page.route('**/api/ai/capabilities', answer)
        page.route('**/api/ai/models', lambda route: route.fulfill(status=200, content_type='application/json', body=json.dumps([
            {'engineId': 'local.default.stt', 'installed': True},
            {'engineId': 'local.default.text', 'installed': True},
        ])))
        page.route('**/api/ai/cloud', lambda route: route.fulfill(status=200, content_type='application/json', body='[]'))

        def visible(locator):
            for index in range(locator.count()):
                item = locator.nth(index)
                if item.is_visible():
                    box = item.bounding_box()
                    if box and box['width'] > 0 and box['height'] > 0:
                        return box
            return None

        def wait_for(check, label):
            deadline = time.monotonic() + 35
            while time.monotonic() < deadline:
                result = check()
                if result:
                    return result
                page.wait_for_timeout(100)
            raise AssertionError('Не дождались: ' + label)

        def click_button(label):
            box = wait_for(lambda: visible(page.get_by_role('button', name=label, exact=True)), label)
            page.mouse.click(box['x'] + box['width']/2, box['y'] + box['height']/2)

        try:
            page.goto(BASE, wait_until='networkidle', timeout=60000)
            page.locator('canvas').first.wait_for(state='visible')
            click_button('Настройки')
            wait_for(lambda: 'Движок не запускается' in page.locator('body').aria_snapshot(), 'причина runtime')
            before = page.locator('body').aria_snapshot()
            assert 'Модель не установлена' in before, before
            assert 'Недоступно для выбранного языка' in before, before
            assert 'Готово к запуску' not in before, before
            page.screenshot(path=str(OUT / 'not-ready.png'))
            mode['ready'] = True
            click_button('Проверить снова')
            wait_for(lambda: 'Готово к запуску' in page.locator('body').aria_snapshot(), 'новый ответ готовности')
            after = page.locator('body').aria_snapshot()
            assert 'Движок не запускается' not in after, after
            assert api('preferences')['ai'] == selection
            assert requests and all(request == selection for request in requests)
            assert not errors, errors
            assert not external, external
            page.screenshot(path=str(OUT / 'ready-after-retry.png'))
            (OUT / 'semantics-before.txt').write_text(before)
            (OUT / 'semantics-after.txt').write_text(after)
            (OUT / 'result.json').write_text(json.dumps({'passed': True, 'checks': [
                'установленный пакет не подменяет готовность', 'конкретные причины по ролям',
                'повтор получает новый статус', 'выбор не переписывается', 'нет внешних запросов'
            ]}, ensure_ascii=False, indent=2))
        except Exception:
            page.screenshot(path=str(OUT / 'failure.png'))
            (OUT / 'failure-semantics.txt').write_text(page.locator('body').aria_snapshot())
            raise
        finally:
            browser.close()
finally:
    api('preferences', original)
