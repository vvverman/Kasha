"""Общий Wasm UI + настоящий HTTP/runtime, demo AI вместо проверки качества моделей.

Первый ответ Cleanup задерживается на 33 секунды без изменения содержимого:
это регрессия прежнего 30-секундного обрыва запроса, не имитация успеха модели.
"""
import json
import os
from pathlib import Path
import shutil
import time
from urllib.parse import urlsplit
import urllib.request
from contextlib import suppress
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'
OUT = Path('test-output/capture-variants')
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


for _ in range(60):
    try:
        api('health')
        break
    except Exception:
        time.sleep(1)
else:
    raise AssertionError('Локальный runtime не запустился')

original = api('preferences')
api('preferences', dict(original, autoRecord=False, autoRoute=False, language='ru', theme='light', demoExample='idea'))

try:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(
            executable_path=os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium'),
            headless=True,
            args=['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'])
        page = browser.new_page(viewport={'width': 1280, 'height': 1000}, locale='ru-RU')
        calls, failures, errors, external = [], [], [], []
        checks = []
        delayed = {'done': False}

        def request_seen(request):
            if request.url.startswith(('http://', 'https://')) and not request.url.startswith(BASE + '/'):
                external.append(request.url)
            if request.method == 'POST' and '/api/captures/' in request.url:
                calls.append(urlsplit(request.url).path.rsplit('/', 1)[-1])

        page.on('request', request_seen)
        page.on('requestfailed', lambda request: failures.append({'url': request.url, 'failure': request.failure})
                if request.method == 'POST' and '/api/captures/' in request.url else None)
        page.on('pageerror', lambda error: errors.append(str(error)))

        def slow_first_cleanup(route):
            # Ответ настоящего локального runtime; меняется только задержка доставки.
            response = route.fetch(timeout=60000)
            if not delayed['done']:
                delayed['done'] = True
                time.sleep(33)
            route.fulfill(response=response)

        page.route('**/api/captures/*/tidy', slow_first_cleanup)

        def wait(check, label, seconds=60):
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                value = check()
                if value:
                    return value
                page.wait_for_timeout(100)
            raise AssertionError('Не дождались: ' + label)

        def target(role, name):
            locator = page.get_by_role(role, name=name, exact=True)
            previous, stable = None, 0
            end = time.monotonic() + 60
            while time.monotonic() < end:
                candidate = None
                for index in range(locator.count()):
                    item = locator.nth(index)
                    with suppress(Exception):
                        box = item.bounding_box(timeout=500)
                        if item.is_visible() and item.is_enabled() and box and box['width'] > 0 and box['height'] > 0:
                            candidate = box
                            break
                signature = None if candidate is None else tuple(round(candidate[k], 1) for k in ('x', 'y', 'width', 'height'))
                stable = stable + 1 if signature is not None and signature == previous else 0
                previous = signature
                if stable >= 2:
                    return candidate
                page.wait_for_timeout(100)
            raise AssertionError('Нет доступного элемента: ' + name)

        def click(role, name):
            box = target(role, name)
            page.mouse.click(box['x'] + box['width']/2, box['y'] + box['height']/2, delay=50)
            page.wait_for_timeout(150)

        def current():
            return next((c for c in api('snapshot')['captures'] if c['noteId'] is None and c.get('taskId') is None), None)

        def ready_variant(variant):
            result = wait(lambda: (c if (c := current()) and c['status'] == 'READY' and
                c['audioFinalized'] and c['selectedTextVariant'] == variant else None), variant)
            target('button', 'Обработать заново')
            assert not failures, failures
            return result

        try:
            page.goto(BASE, wait_until='networkidle', timeout=60000)
            page.locator('canvas').first.wait_for(state='visible')
            click('button', 'Попробовать без микрофона')
            capture = ready_variant('TRANSCRIPTION')
            transcript = capture['transcript']
            assert transcript and not capture['llmApplied'] and not capture['rankingApplied']
            assert 'tidy' not in calls and 'rank' not in calls
            checks.append('после STT нет автоматической нормализации или routing')

            click('tab', 'Нормализация')
            normalized = ready_variant('NORMALIZATION')
            assert normalized['transcript'] == transcript
            assert normalized['llmApplied'] and normalized['preparedText']
            assert calls.count('tidy') == 1 and 'retranscribe' not in calls
            assert delayed['done']
            checks.append('первое открытие нормализации запускает только TEXT; ответ позже 30 секунд принят')

            click('tab', 'Транскрибация')
            assert ready_variant('TRANSCRIPTION')['transcript'] == transcript
            click('tab', 'Нормализация')
            cached = ready_variant('NORMALIZATION')
            assert cached['preparedText'] == normalized['preparedText']
            assert calls.count('tidy') == 1
            checks.append('переключение вкладок использует сохранённую нормализацию')

            click('button', 'Обработать заново')
            ready_variant('NORMALIZATION')
            assert calls.count('tidy') == 2 and 'retranscribe' not in calls
            checks.append('повтор нормализации не запускает STT')

            click('tab', 'Транскрибация')
            ready_variant('TRANSCRIPTION')
            # Детерминированный новый ответ demo-STT доказывает настоящий повтор этапа,
            # а не возврат старого текста с успешным HTTP-кодом.
            api('preferences', dict(api('preferences'), demoExample='cooking'))
            click('button', 'Обработать заново')
            repeated = ready_variant('TRANSCRIPTION')
            assert repeated['id'] == capture['id']
            assert repeated['transcript'].startswith('Рецепт ужина'), repeated
            assert repeated['transcript'] != transcript
            assert repeated['audioFileName'] == capture['audioFileName']
            assert not repeated['preparedText'] and not repeated['llmApplied']
            assert not repeated['rankingApplied'] and not repeated['relevance']
            assert calls.count('retranscribe') == 1 and calls.count('tidy') == 2
            assert 'process' not in calls and 'rank' not in calls
            checks.append('повтор транскрибации вызывает отдельный STT и сбрасывает устаревшие результаты')

            click('tab', 'Нормализация')
            fresh = ready_variant('NORMALIZATION')
            assert fresh['transcript'] == repeated['transcript']
            assert calls.count('tidy') == 3 and calls.count('retranscribe') == 1
            assert 'Рецепт ужина' in fresh['preparedText']
            assert not failures and not errors and not external, (failures, errors, external)
            checks.append('новая нормализация использует повторно распознанный текст')
            (OUT / 'result.json').write_text(json.dumps({'passed': True, 'demoAi': True,
                'checks': checks, 'stageRequests': calls, 'delayedResponseSeconds': 33}, ensure_ascii=False, indent=2))
            print('CAPTURE VARIANTS BROWSER PASSED')
        finally:
            with suppress(Exception):
                page.screenshot(path=str(OUT / 'final.png'))
                (OUT / 'semantics.txt').write_text(page.locator('body').aria_snapshot())
            with suppress(Exception):
                (OUT / 'snapshot.json').write_text(json.dumps(api('snapshot'), ensure_ascii=False, indent=2))
            (OUT / 'events.json').write_text(json.dumps({'calls': calls, 'failures': failures,
                'errors': errors, 'external': external, 'checks': checks}, ensure_ascii=False, indent=2))
            browser.close()
finally:
    api('preferences', original)
