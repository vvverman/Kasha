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

    def stable_nav_boxes(width, height):
        deadline = time.time() + 10
        last = {}
        while time.time() < deadline:
            boxes = []
            complete = True
            last = {}
            for label in NAV:
                locator = page.get_by_role('button', name=label, exact=True)
                candidates = []
                for index in range(locator.count()):
                    with suppress(Exception):
                        box = locator.nth(index).bounding_box(timeout=500)
                        if not box:
                            continue
                        last.setdefault(label, []).append(box)
                        inside = (
                            box['width'] >= 44 and box['height'] >= 44 and
                            box['x'] >= -1 and box['y'] >= -1 and
                            box['x'] + box['width'] <= width + 1 and
                            box['y'] + box['height'] <= height + 1
                        )
                        expected_region = (
                            box['x'] + box['width'] < 320 if width >= 1024
                            else box['y'] > height / 2
                        )
                        if inside and expected_region:
                            candidates.append(box)
                if not candidates:
                    complete = False
                    break
                boxes.append(candidates[0])
            if complete:
                return boxes
            page.wait_for_timeout(100)
        raise AssertionError(('navigation did not stabilize after resize', width, height, last))

    try:
        page.goto(BASE, wait_until='networkidle', timeout=60000)
        page.locator('canvas').first.wait_for(state='visible', timeout=30000)

        viewport_results = []
        for name, width, height in VIEWPORTS:
            page.set_viewport_size({'width': width, 'height': height})
            page.wait_for_timeout(100)
            root = page.locator('#webApp').bounding_box()
            assert root and abs(root['width'] - width) <= 1, (name, root)
            assert abs(root['height'] - height) <= 1, (name, root)

            boxes = stable_nav_boxes(width, height)

            no_horizontal_scroll = page.evaluate(
                'document.documentElement.scrollWidth <= window.innerWidth + 1 && document.body.scrollWidth <= window.innerWidth + 1'
            )
            assert no_horizontal_scroll, name

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
                'web root follows current viewport width and height',
                '320/390/844/1024/1280 without clipped navigation',
                'navigation targets are at least 44x44',
                'no horizontal page scroll',
                'desktop navigation stays in sidebar area',
                'navigation exposes accessible names',
                'navigation semantics stabilize after breakpoint resize',
            ],
            'pageErrors': errors,
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print('RESPONSIVE ACCESSIBILITY BROWSER PASSED')
    finally:
        with suppress(Exception):
            (OUT / 'semantics.txt').write_text(page.locator('body').aria_snapshot(), encoding='utf-8')
        browser.close()
