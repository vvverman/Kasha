"""E2E текущего Kasha UI: однопольные заметки, отдельный task flow, сроки и архив."""
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
OUT = pathlib.Path('test-output/studio')
OUT.mkdir(parents=True, exist_ok=True)


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


for _ in range(90):
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
        args=['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    )
    page = browser.new_page(viewport={'width': 1280, 'height': 1000}, locale='ru-RU')
    errors = []
    checks = []
    page.on('pageerror', lambda error: errors.append(str(error)))

    def wait(check, description, seconds=35):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = check()
            if value:
                return value
            page.wait_for_timeout(100)
        raise AssertionError('Не дождались: ' + description)

    def role_locator(role, name):
        return page.get_by_role(role, name=name) if hasattr(name, 'search') else page.get_by_role(role, name=name, exact=True)

    def visible_item(locator, description, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                count = locator.count()
            except Exception:
                count = 0
            for index in range(count):
                item = locator.nth(index)
                try:
                    if item.is_visible():
                        box = item.bounding_box()
                        if box and box['width'] > 0 and box['height'] > 0:
                            return item, box
                except Exception:
                    pass
            page.wait_for_timeout(100)
        raise AssertionError('Не найден видимый элемент: ' + description)

    def click(role, name):
        _, box = visible_item(role_locator(role, name), str(name))
        page.mouse.click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2)
        page.wait_for_timeout(250)

    def button(name):
        click('button', name)

    def text_click(name):
        _, box = visible_item(page.get_by_text(name, exact=True), name)
        page.mouse.click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2)
        page.wait_for_timeout(250)

    def field(label, value):
        _, box = visible_item(page.get_by_role('textbox', name=label, exact=True), label)
        page.mouse.click(box['x'] + min(24, box['width'] / 2), box['y'] + min(24, box['height'] / 2))
        page.wait_for_timeout(160)
        page.keyboard.press('Control+a')
        page.wait_for_timeout(80)
        page.keyboard.press('Backspace')
        page.wait_for_timeout(80)
        page.keyboard.insert_text(value)
        page.wait_for_timeout(650)

    def current():
        return next((c for c in api('snapshot')['captures'] if c['noteId'] is None and c.get('taskId') is None), None)

    def ready():
        return wait(lambda: (c if (c := current()) and c['status'] == 'READY' else None), 'готовая тестовая запись')

    def card_locator(prefix):
        return page.get_by_role('button', name=re.compile(r'^' + re.escape(prefix) + r'(?:\s|$)'))

    def click_card(prefix):
        _, box = visible_item(card_locator(prefix), prefix)
        page.mouse.click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2)
        page.wait_for_timeout(250)

    def wait_card(prefix):
        return visible_item(card_locator(prefix), prefix)[0]

    def screen(name):
        page.screenshot(path=str(OUT / (name + '.png')))

    try:
        page.goto(BASE, wait_until='networkidle', timeout=60000)
        page.locator('canvas').first.wait_for(state='visible')
        visible_item(page.get_by_role('button', name='Главная', exact=True), 'Главная')
        assert page.locator('#webApp').bounding_box()['width'] == 430
        snapshot = api('snapshot')
        assert len(snapshot['projects']) == 1 and snapshot['projects'][0]['title'] == 'Твой первый проект'
        checks.append('старт и первый проект')

        aria = page.locator('body').aria_snapshot()
        assert 'Скорость' not in aria and '1.0×' not in aria and '1×' not in aria
        checks.append('скорость воспроизведения убрана из плеера')

        button('Попробовать без микрофона')
        ready()
        visible_item(page.get_by_role('textbox', name='Текст заметки', exact=True), 'Текст заметки')
        assert page.get_by_role('textbox', name='Название заметки', exact=True).count() == 0
        field('Текст заметки', 'Моя первая строка\nЭто тело заметки. Отдельного заголовка больше нет.')
        screen('capture-one-field-note')
        button('В заметки')
        assert page.get_by_text('Задача', exact=True).count() == 0
        click('button', re.compile(r'^Твой первый проект'))
        button('Новая заметка')
        wait(lambda: current() is None, 'сохранение заметки')
        note = api('snapshot')['notes'][0]
        assert note['title'] == 'Моя первая строка', note
        assert note['body'].startswith('Моя первая строка\nЭто тело заметки')
        checks.append('первая строка заметки является названием')

        button('Проекты')
        click('button', re.compile(r'^Твой первый проект'))
        click_card('Моя первая строка')
        button('Править')
        field('Текст заметки', 'Новое название из первой строки\nИсправленное тело заметки.')
        button('Сохранить')
        edited = wait(lambda: api('snapshot')['notes'][0] if api('snapshot')['notes'][0]['title'] == 'Новое название из первой строки' else None, 'пересчёт названия заметки')
        assert edited['body'].startswith('Новое название из первой строки')
        checks.append('редактирование заметки без отдельного title')

        button('Главная')
        button('Попробовать без микрофона')
        ready()
        field('Текст заметки', 'Позвонить в сервис\nУточнить статус ремонта и записать ответ.')
        button('В задачи')
        visible_item(page.get_by_role('textbox', name='Дата', exact=True), 'Дата')
        assert page.get_by_text('Выберите проект', exact=True).count() == 0
        for label in ['Раз в 10 минут', 'Раз в полчаса', 'Раз в час', 'Каждый день', 'Каждую неделю', 'По выходным', 'По будням']:
            visible_item(page.get_by_text(label, exact=True), label)
        field('Дата', '31.12.2099')
        field('Время', '12:00')
        text_click('Раз в 10 минут')
        screen('task-schedule')
        button('Сохранить задачу')
        wait(lambda: current() is None, 'сохранение задачи')
        task = api('snapshot')['tasks'][0]
        assert task['text'].startswith('Позвонить в сервис')
        assert task['projectId'] is None
        assert task['reminderRepeat'] == 'TEN_MINUTES'
        assert task['dueAt'] > int(time.time() * 1000)
        assert task['completedAt'] is None
        checks.append('задача со сроком и частотой напоминаний')

        visible_item(page.get_by_role('button', name='Задачи', exact=True), 'Задачи')
        click_card('Позвонить в сервис')
        field('Задача', 'Позвонить в сервис повторно\nЗапросить письменное подтверждение.')
        button('Сохранить')
        wait(lambda: api('snapshot')['tasks'][0]['text'].startswith('Позвонить в сервис повторно'), 'редактирование задачи')
        button('Изменить время')
        field('Дата', '31.12.2099')
        field('Время', '13:00')
        text_click('Каждый день')
        button('Сохранить задачу')
        task = wait(lambda: api('snapshot')['tasks'][0] if api('snapshot')['tasks'][0]['reminderRepeat'] == 'DAILY' else None, 'перенос срока')
        assert task['completedAt'] is None
        checks.append('редактирование текста и повторное расписание задачи')

        button('Выполнить')
        wait(lambda: api('snapshot')['tasks'][0]['completedAt'] is not None, 'выполнение задачи')
        button('Архив')
        wait_card('Позвонить в сервис повторно')
        screen('task-archive')
        click_card('Позвонить в сервис повторно')
        assert page.get_by_role('button', name='Выполнить', exact=True).count() == 0
        button('Удалить')
        wait(lambda: not api('snapshot')['tasks'], 'удаление задачи из архива')
        checks.append('выполнение, архив и удаление задачи')

        assert not errors, errors
        (OUT / 'result.json').write_text(json.dumps({
            'passed': True,
            'checks': checks,
            'pageErrors': errors,
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print('STUDIO BROWSER PASSED')
    finally:
        with suppress(Exception):
            (OUT / 'semantics.txt').write_text(page.locator('body').aria_snapshot(), encoding='utf-8')
        browser.close()
