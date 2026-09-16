"""ТЗ 13.1/8.4: rollback, modal recovery and reorder in the real shared UI, without AI."""
import json
import os
import pathlib
import re
import shutil
import time
import urllib.request
from contextlib import suppress
from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'
OUT = pathlib.Path('test-output/non-ai-parity')
OUT.mkdir(parents=True, exist_ok=True)

def api(path, data=None, method=None):
    headers = {'X-Kasha-Client': 'web'}
    if data is not None:
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(BASE + '/api/' + path,
        data=json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

prefs = api('preferences')
prefs.update(autoRecord=False, language='ru', theme='light', projectSort='MANUAL')
api('preferences', prefs, 'PUT')
first = api('projects', {'title': 'АА Проверка порядка 1', 'instruction': 'Первый'}, 'POST')
second = api('projects', {'title': 'АА Проверка порядка 2', 'instruction': 'Второй'}, 'POST')
all_ids = [first['id'], second['id']] + [p['id'] for p in api('snapshot')['projects'] if p['id'] not in (first['id'], second['id'])]
api('projects/order', {'ids': all_ids}, 'POST')

def manual_order():
    return [p['id'] for p in sorted(api('snapshot')['projects'], key=lambda p: p['manualOrder'])]

with sync_playwright() as pw:
    executable = os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium')
    browser = pw.chromium.launch(executable_path=executable, headless=True,
        args=['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'])
    page = browser.new_page(viewport={'width': 1280, 'height': 1000}, locale='ru-RU')
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))

    def wait(check, description, seconds=25):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            with suppress(Exception):
                value = check()
                if value:
                    return value
            page.wait_for_timeout(100)
        raise AssertionError(description)

    def probe_box(name, exact=True):
        loc = page.get_by_role('button', name=name, exact=exact)
        for index in range(loc.count()):
            value = loc.nth(index).bounding_box(timeout=500)
            if value and value['width'] > 0 and value['height'] > 0:
                return value
        return None

    def box(name, exact=True):
        return wait(lambda: probe_box(name, exact), 'Не найдено: ' + str(name))

    def click(name):
        b = box(name)
        page.mouse.click(b['x'] + b['width'] / 2, b['y'] + b['height'] / 2)
        page.wait_for_timeout(150)

    def card(title):
        return box(re.compile('^' + re.escape(title) + r'(?:\s|$)'), exact=False)

    def expect_order(earlier, later, description):
        # One bounded polling loop: distinguish missing semantics from a wrong visual order.
        def ordered():
            a = probe_box(re.compile('^' + re.escape(earlier) + r'(?:\s|$)'), exact=False)
            b = probe_box(re.compile('^' + re.escape(later) + r'(?:\s|$)'), exact=False)
            return a and b and a['y'] < b['y']
        wait(ordered, description)

    def drag():
        a, b = card(first['title']), card(second['title'])
        page.mouse.move(a['x'] + a['width'] / 2, a['y'] + a['height'] / 2)
        page.mouse.down()
        page.wait_for_timeout(700)
        page.mouse.move(b['x'] + b['width'] / 2, b['y'] + b['height'] / 2, steps=16)
        page.wait_for_timeout(100)
        page.mouse.up()

    checks = []
    try:
        page.goto(BASE, wait_until='networkidle', timeout=60000)
        click('Проекты')
        expect_order(first['title'], second['title'], 'Исходный порядок')
        denied = []
        def deny(route):
            denied.append(route.request.post_data_json)
            route.fulfill(status=503, content_type='application/json', body='{"error":"saveFailed"}')
        page.route('**/api/projects/order', deny)
        for attempt in range(2):
            drag()
            wait(lambda: len(denied) == attempt + 1, 'Не было попытки сохранить drag')
            box('Понятно')
            # Both pointer dismissal and Escape must restore the SAME screen, without reload.
            if attempt == 0:
                click('Понятно')
            else:
                page.keyboard.press('Escape')
            wait(lambda: page.get_by_role('button', name='Понятно', exact=True).count() == 0,
                 'Закрытый диалог остался в дереве доступности')
            assert manual_order() == all_ids
            expect_order(first['title'], second['title'],
                         'После закрытия ошибки не восстановлены строки и исходный порядок')
            assert len(denied) == attempt + 1, 'Повторная скрытая запись порядка'
            page.screenshot(path=str(OUT / f'failed-reorder-restored-{attempt}.png'))
        checks.append('two failed reorders preserve authoritative order and every item')
        checks.append('pointer and Escape dismissal restore accessibility without reload')
        page.unroute('**/api/projects/order', deny)
        drag()
        expected = [second['id'], first['id']] + all_ids[2:]
        wait(lambda: manual_order() == expected, 'Повтор перестановки не сохранился')
        expect_order(second['title'], first['title'], 'Подтверждённый порядок не показан')
        page.reload(wait_until='networkidle')
        click('Проекты')
        expect_order(second['title'], first['title'], 'Порядок потерян после reload')
        checks.append('retry persists once and survives browser restart')

        # Используется реальная аппаратная клавиатура браузера, а не вызов функции перестановки.
        # Ищем доступную строку обычным Tab: layout не обязан иметь фиксированное число Tab-stop.
        before = manual_order()
        for _ in range(60):
            page.keyboard.press('Tab')
            page.keyboard.press('Alt+ArrowDown')
            page.wait_for_timeout(100)
            if manual_order() != before:
                break
        else:
            raise AssertionError('Alt+ArrowDown не переставляет строки через клавиатуру')
        changed = manual_order()
        assert len(changed) == len(before) and set(changed) == set(before)
        checks.append('keyboard reorder is available without drag and preserves membership')
        assert not errors, errors
        (OUT / 'result.json').write_text(json.dumps({'passed': True, 'checks': checks, 'pageErrors': errors}, ensure_ascii=False, indent=2))
        print('NON-AI PARITY BROWSER PASSED')
    finally:
        with suppress(Exception):
            page.screenshot(path=str(OUT / 'final.png'))
            (OUT / 'semantics.txt').write_text(page.locator('body').aria_snapshot())
            (OUT / 'dom.html').write_text(page.content())
            (OUT / 'page-errors.json').write_text(json.dumps(errors, ensure_ascii=False, indent=2))
        browser.close()
