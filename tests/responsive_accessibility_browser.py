"""Web E2E: основные breakpoints и доступные названия навигации."""
import json
import os
import pathlib
import shutil
import time
import urllib.request
from contextlib import suppress

from playwright.sync_api import sync_playwright

BASE = 'http://127.0.0.1:8787'
OUT = pathlib.Path('test-output/responsive')
OUT.mkdir(parents=True, exist_ok=True)
NAV = ['Главная', 'Проекты', 'Задачи', 'Настройки']
VIEWPORTS = [
    ('compact-320', 320, 568),
    ('mobile-390', 390, 844),
    ('landscape-844', 844, 390),
    ('desktop-1024', 1024, 768),
    ('wide-1280', 1280, 800),
]


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
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


for _ in range(60):
    try:
        api('health')
        break
    except Exception:
        time.sleep(1)
else:
    raise AssertionError('Сервис не запустился')

prefs = api('preferences')
prefs.update(autoRecord=False, language='ru', theme='light')
api('preferences', prefs, 'PUT')

with sync_playwright() as pw:
    executable = os.getenv('CHROME_PATH') or shutil.which('google-chrome') or shutil.which('chromium')
    browser = pw.chromium.launch(
        executable_path=executable,
        headless=True,
        args=['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    )
    page = browser.new_page(viewport={'width': 390, 'height': 844}, locale='ru-RU')
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))

    def nav_box(label):
        item = page.get_by_role('button', name=label, exact=True)
        item.wait_for(state='visible', timeout=30000)
        box = item.bounding_box()
        assert box and box['width'] >= 44 and box['height'] >= 44, (label, box)
        return box

    try:
        page.goto(BASE, wait_until='networkidle', timeout=60000)
        page.locator('canvas').first.wait_for(state='visible', timeout=30000)

        viewport_results = []
        for name, width, height in VIEWPORTS:
            page.set_viewport_size({'width': width, 'height': height})
            page.wait_for_timeout(500)
            root = page.locator('#webApp').bounding_box()
            assert root and abs(root['width'] - width) <= 1, (name, root)

            boxes = []
            for label in NAV:
                box = nav_box(label)
                assert box['x'] >= -1 and box['y'] >= -1, (name, label, box)
                assert box['x'] + box['width'] <= width + 1, (name, label, box)
                assert box['y'] + box['height'] <= height + 1, (name, label, box)
                boxes.append(box)

            no_horizontal_scroll = page.evaluate(
                'document.documentElement.scrollWidth <= window.innerWidth + 1 && document.body.scrollWidth <= window.innerWidth + 1'
            )
            assert no_horizontal_scroll, name

            if width >= 1024:
                assert max(box['x'] + box['width'] for box in boxes) < 320, (name, boxes)
            else:
                assert min(box['y'] for box in boxes) > height / 2, (name, boxes)

            page.screenshot(path=str(OUT / f'{name}.png'))
            viewport_results.append({'name': name, 'width': width, 'height': height})

        semantics = page.locator('body').aria_snapshot()
        for label in NAV:
            assert label in semantics, label
        assert not errors, errors

        (OUT / 'result.json').write_text(json.dumps({
            'passed': True,
            'viewports': viewport_results,
            'checks': [
                '320/390/844/1024/1280 without clipped navigation',
                'navigation targets are at least 44x44',
                'no horizontal page scroll',
                'desktop navigation stays in sidebar area',
                'navigation exposes accessible names',
            ],
            'knownSharedGap': 'issue #67: native Tab focus remains on Compose Web canvas',
            'pageErrors': errors,
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print('RESPONSIVE ACCESSIBILITY BROWSER PASSED')
    finally:
        with suppress(Exception):
            (OUT / 'semantics.txt').write_text(page.locator('body').aria_snapshot(), encoding='utf-8')
        browser.close()
