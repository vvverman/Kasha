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
        locator = role_locator(role, name)
        deadline = time.monotonic() + 10
        previous = None
        stable = 0
        selected = None
        while time.monotonic() < deadline:
            item, box = visible_item(locator, str(name), seconds=1)
            signature = tuple(round(box[key], 1) for key in ('x', 'y', 'width', 'height'))
            if signature == previous:
                stable += 1
            else:
                previous = signature
                stable = 1
            selected = box
            if stable >= 3:
                break
            page.wait_for_timeout(100)
        assert stable >= 3 and selected, f'Не стабилизировались bounds: {name}'
        page.mouse.click(selected['x'] + selected['width'] / 2, selected['y'] + selected['height'] / 2)
        page.wait_for_timeout(250)

    def button(name):
        click('button', name)

    def text_click(name):
        _, box = visible_item(page.get_by_text(name, exact=True), name)
        page.mouse.click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2)
        page.wait_for_timeout(250)

    def settle_input():
        # Дожидаемся обработки предыдущего клавиатурного события общим Compose UI.
        page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')

    def field(label, value):
        _, box = visible_item(page.get_by_role('textbox', name=label, exact=True), label)
        page.mouse.click(box['x'] + min(24, box['width'] / 2), box['y'] + min(24, box['height'] / 2))
        page.wait_for_function("""() => {
            let node = document.activeElement;
            while (node?.shadowRoot?.activeElement) node = node.shadowRoot.activeElement;
            return node?.isConnected && !node.disabled && !node.readOnly &&
                (node.matches?.('input,textarea') || node.isContentEditable);
        }""", timeout=5000)
        settle_input()
        page.keyboard.press('Control+a')
        settle_input()
        page.keyboard.press('Backspace')
        settle_input()
        page.keyboard.insert_text(value)
        settle_input()
        page.wait_for_timeout(650)

    def current():
        return next((c for c in api('snapshot')['captures'] if c['noteId'] is None and c.get('taskId') is None), None)

    def ready():
        return wait(lambda: (c if (c := current()) and c['status'] == 'READY' and c['audioFinalized'] else None), 'готовая тестовая запись')

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
        visible_item(page.get_by_role('tab', name='Главная', exact=True), 'Главная')
        web_app_box = page.locator('#webApp').bounding_box()
        assert web_app_box and web_app_box['width'] == 1280, web_app_box
        snapshot = api('snapshot')
        assert len(snapshot['projects']) == 1 and snapshot['projects'][0]['title'] == 'Твой первый проект'
        checks.append('старт и первый проект')

        aria = page.locator('body').aria_snapshot()
        assert 'Скорость' not in aria and '1.0×' not in aria and '1×' not in aria
        checks.append('скорость воспроизведения убрана из плеера')

        # Настоящие UI-команды и MediaRecorder; Chrome подаёт тестовый аудиосигнал.
        # Текст формирует существующий demo-режим, не приёмка STT из раздела 6.
        button('Запись')
        wait(lambda: page.evaluate('kashaPlatform.phase()') == 'recording', 'запись через UI')
        recording_id = json.loads(page.evaluate('kashaPlatform.sessionState()'))['activeSessionId']
        assert recording_id
        page.wait_for_timeout(1100)  # Накопить настоящие аудиофрагменты MediaRecorder.
        screen('recording')
        click('tab', 'Проекты')
        assert json.loads(page.evaluate('kashaPlatform.sessionState()'))['activeSessionId'] == recording_id
        assert page.evaluate('kashaPlatform.phase()') == 'recording'
        button('Пауза')
        wait(lambda: page.evaluate('kashaPlatform.phase()') == 'paused', 'пауза через UI')
        click('tab', 'Главная')
        screen('recording-paused')
        button('Продолжить')
        wait(lambda: page.evaluate('kashaPlatform.phase()') == 'recording', 'продолжение через UI')
        page.wait_for_timeout(700)
        button('Отправить')
        recorded = ready()
        assert recorded['id'] == recording_id, recorded
        wait(lambda: not page.evaluate('kashaPlatform.pending()'), 'подтверждение сохранённого аудио')
        assert page.evaluate('kashaPlatform.phase()') == 'idle'
        checks.append('реальная запись через UI: pause/resume, навигация, stop; один идентификатор и подтверждённое аудио')
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

        click('tab', 'Проекты')
        click('button', re.compile(r'^Твой первый проект'))
        click_card('Моя первая строка')
        button('Править')
        field('Текст заметки', 'Новое название из первой строки\nИсправленное тело заметки.')
        button('Сохранить')
        edited = wait(lambda: api('snapshot')['notes'][0] if api('snapshot')['notes'][0]['title'] == 'Новое название из первой строки' else None, 'пересчёт названия заметки')
        assert edited['body'].startswith('Новое название из первой строки')
        checks.append('редактирование заметки без отдельного title')

        # Существующая заметка: реальный выбор в UI, без подмены команды HTTP-записью.
        before_append = edited.copy()
        addition = 'Дополнение к существующей заметке.\nСтарый текст остаётся без изменений.'
        click('tab', 'Главная')
        button('Попробовать без микрофона')
        appended_capture = ready()
        field('Текст заметки', addition)
        wait(lambda: (c := current()) and c['id'] == appended_capture['id'] and
             c['transcript'] == addition and c['selectedTextVariant'] == 'TRANSCRIPTION' and
             not c['preparedText'] and not c['llmApplied'] and c['draftEdited'], 'автосохранение дополнения в транскрибации')
        button('В заметки')
        click('button', re.compile(r'^Твой первый проект'))
        click_card('Новое название из первой строки')
        wait(lambda: current() is None, 'добавление в существующую заметку')
        after_append = api('snapshot')
        assert len(after_append['notes']) == 1, after_append['notes']
        appended_note = after_append['notes'][0]
        assert appended_note['id'] == before_append['id']
        assert appended_note['body'] == before_append['body'] + '\n\n' + addition
        for name in ['title', 'projectId', 'createdAt', 'manualOrder', 'pinned', 'pinOrder']:
            assert appended_note[name] == before_append[name], name
        sources = [c for c in after_append['captures'] if c['noteId'] == appended_note['id']]
        assert len(sources) == 2 and len({c['audioFileName'] for c in sources}) == 2, sources
        assert sum(c['id'] == appended_capture['id'] for c in sources) == 1
        page.reload(wait_until='networkidle')
        visible_item(page.get_by_role('tab', name='Главная', exact=True), 'Главная после перезапуска')
        click('tab', 'Проекты')
        click('button', re.compile(r'^Твой первый проект'))
        click_card('Новое название из первой строки')
        visible_item(page.get_by_text(addition, exact=False), 'дополнение после перезапуска')
        assert api('snapshot')['notes'][0] == appended_note
        screen('appended-note-after-reload')
        checks.append('append сохраняет старый текст, идентификатор, manual/pinned order и два аудиоисточника')
        checks.append('дополненная заметка и её порядок сохраняются после перезапуска Web')

        click('tab', 'Главная')
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

        visible_item(page.get_by_role('tab', name='Задачи', exact=True), 'Задачи')
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
        visible_item(page.get_by_role('button', name='Отмена', exact=True), 'подтверждение удаления задачи')
        _, confirm_delete_box = visible_item(
            page.get_by_role('button', name='Удалить', exact=True).last,
            'кнопка удаления в подтверждении',
        )
        page.mouse.click(
            confirm_delete_box['x'] + confirm_delete_box['width'] / 2,
            confirm_delete_box['y'] + confirm_delete_box['height'] / 2,
        )
        page.wait_for_timeout(250)
        wait(lambda: not api('snapshot')['tasks'], 'удаление задачи из архива')
        checks.append('выполнение, архив и подтверждённое удаление задачи')

        assert not errors, errors
        (OUT / 'result.json').write_text(json.dumps({
            'passed': True,
            'checks': checks,
            'pageErrors': errors,
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print('STUDIO BROWSER PASSED')
    finally:
        with suppress(Exception):
            page.screenshot(path=str(OUT / 'final.png'))
        with suppress(Exception):
            (OUT / 'page-errors.json').write_text(json.dumps(errors, ensure_ascii=False, indent=2), encoding='utf-8')
        with suppress(Exception):
            (OUT / 'semantics.txt').write_text(page.locator('body').aria_snapshot(), encoding='utf-8')
        browser.close()
