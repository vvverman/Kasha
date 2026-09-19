"""Дополнительный E2E: четыре сортировки и сохранность Manual для проектов/заметок/задач."""
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
OUT = pathlib.Path('test-output/sorting')
OUT.mkdir(parents=True, exist_ok=True)

SORT_LABELS = {
    'ALPHABETICAL': 'А-Я',
    'CREATED': 'Создано',
    'UPDATED': 'Изменено',
    'MANUAL': 'Вручную',
}


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
        args=['--no-sandbox', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    )
    page = browser.new_page(viewport={'width': 1280, 'height': 1000}, locale='ru-RU')
    errors = []
    interactions = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    # Trace the actual shadow-DOM input receiver, not only the accessibility proxy.
    page.add_init_script(r"""(() => {
        const describe = node => node && ({
            tag: node.tagName, role: node.getAttribute?.('role'),
            label: node.getAttribute?.('aria-label'), connected: node.isConnected,
            editable: node.isContentEditable, readOnly: node.readOnly,
            disabled: node.disabled, value: typeof node.value === 'string' ? node.value : null,
        });
        const active = () => {
            let node = document.activeElement;
            while (node?.shadowRoot?.activeElement) node = node.shadowRoot.activeElement;
            return node;
        };
        window.kashaTestInputTrace = [];
        window.kashaTestActiveInput = () => describe(active());
        for (const type of ['focusin', 'focusout', 'keydown', 'keyup', 'beforeinput', 'input']) {
            document.addEventListener(type, event => {
                window.kashaTestInputTrace.push({type, key: event.key, inputType: event.inputType,
                    data: event.data, target: describe(event.composedPath()[0]), active: describe(active())});
                if (window.kashaTestInputTrace.length > 300) window.kashaTestInputTrace.shift();
            }, true);
        }
    })()""")

    def wait(check, description, seconds=35):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = check()
            if value:
                return value
            page.wait_for_timeout(100)
        raise AssertionError('Не дождались: ' + description)

    def locator(role, name):
        return page.get_by_role(role, name=name) if hasattr(name, 'search') else page.get_by_role(role, name=name, exact=True)

    def visible_item(items, description, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                count = items.count()
            except Exception:
                count = 0
            for index in range(count):
                item = items.nth(index)
                try:
                    if item.is_visible():
                        box = item.bounding_box()
                        if box and box['width'] > 0 and box['height'] > 0:
                            return item, box
                except Exception:
                    pass
            page.wait_for_timeout(100)
        raise AssertionError('Не найден видимый элемент: ' + description)

    def interaction_target(role, name, seconds=30):
        # Canvas pointer input does not get Playwright locator.click auto-waiting.
        # An accessible node may exist before Compose has enabled/laid out that control.
        items = locator(role, name)
        deadline = time.monotonic() + seconds
        previous = None
        stable = 0
        while time.monotonic() < deadline:
            candidate = None
            for index in range(items.count()):
                item = items.nth(index)
                with suppress(Exception):
                    box = item.bounding_box(timeout=500)
                    if item.is_visible() and item.is_enabled() and box and box['width'] > 0 and box['height'] > 0:
                        viewport = page.viewport_size
                        if (box['x'] >= 0 and box['y'] >= 0 and
                                box['x'] + box['width'] <= viewport['width'] + 1 and
                                box['y'] + box['height'] <= viewport['height'] + 1):
                            candidate = item, box
                            break
            if candidate:
                signature = tuple(round(candidate[1][key], 1) for key in ('x', 'y', 'width', 'height'))
                stable = stable + 1 if signature == previous else 1
                previous = signature
                if stable >= 3:
                    return candidate
            else:
                previous, stable = None, 0
            page.wait_for_timeout(100)
        raise AssertionError('Элемент не стал доступен для ввода: ' + str(name))

    def click(role, name):
        _, box = interaction_target(role, name)
        interactions.append({'action': 'click', 'role': role, 'name': str(name), 'bounds': box})
        # Exactly one real pointer click; do not repeat an unacknowledged command.
        page.mouse.click(box['x'] + box['width'] / 2, box['y'] + box['height'] / 2)

    def button(name):
        click('button', name)

    def restore_sort_screen(pref_key):
        visible_item(page.get_by_role('tab', name='Главная', exact=True), 'Главная')
        if pref_key == 'projectSort':
            click('tab', 'Проекты')
        elif pref_key == 'noteSort':
            click('tab', 'Проекты')
            click('button', re.compile(r'^Альфа сортировка'))
        elif pref_key == 'taskSort':
            click('tab', 'Задачи')

    def select_sort(pref_key, target):
        current = api('preferences')[pref_key]
        if current == target:
            return
        button(SORT_LABELS[current])
        button(SORT_LABELS[target])
        wait(lambda: api('preferences')[pref_key] == target, f'{pref_key}={target}')
        # Compose Web DropdownMenu может оставаться отдельным accessibility popup
        # после выбора. Reload одновременно проверяет persistence и возвращает чистый UI tree.
        page.reload(wait_until='networkidle')
        restore_sort_screen(pref_key)

    def settle_input():
        # Compose processes native keyboard events at requestAnimationFrame.
        # Finish one user action before sending the next; no sleeps or repeated input.
        page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')

    def field(label, value):
        _, box = interaction_target('textbox', label)
        interactions.append({'action': 'field', 'name': label, 'value': value, 'bounds': box})
        page.mouse.click(box['x'] + min(20, box['width'] / 2), box['y'] + min(20, box['height'] / 2))
        # Compose accessibility bounds can be ready before its native text-input session.
        # Await the receiver created by this pointer interaction; never focus it by script.
        page.wait_for_function("""() => {
            let node = document.activeElement;
            while (node?.shadowRoot?.activeElement) node = node.shadowRoot.activeElement;
            return node?.isConnected && !node.disabled && !node.readOnly &&
                (node.matches?.('input,textarea') || node.isContentEditable);
        }""", timeout=5000)
        interactions.append({'action': 'input-focused', 'name': label,
                             'receiver': page.evaluate('kashaTestActiveInput()')})
        settle_input()
        page.keyboard.press('Control+a')
        settle_input()
        page.keyboard.press('Backspace')
        settle_input()
        page.keyboard.insert_text(value)
        settle_input()
        interactions.append({'action': 'input-sent', 'name': label,
                             'receiver': page.evaluate('kashaTestActiveInput()')})
        page.wait_for_timeout(550)

    def card(label):
        items = page.get_by_role('button', name=re.compile(r'^' + re.escape(label) + r'(?:\s|$)'))
        return visible_item(items, label)[0]

    def y(label):
        item = card(label)
        box = item.bounding_box(); assert box, label
        return box['y']

    def drag(source, target):
        a = card(source); b = card(target)
        aa = a.bounding_box(); bb = b.bounding_box(); assert aa and bb
        page.mouse.move(aa['x'] + aa['width']/2, aa['y'] + aa['height']/2)
        page.mouse.down(); page.wait_for_timeout(700)
        page.mouse.move(bb['x'] + bb['width']/2, bb['y'] + bb['height']/2, steps=16)
        page.wait_for_timeout(180); page.mouse.up(); page.wait_for_timeout(350)

    def current():
        return next((c for c in api('snapshot')['captures'] if c['noteId'] is None and c.get('taskId') is None), None)

    def ready():
        capture = wait(lambda: (c if (c := current()) and c['status'] == 'READY' and c['audioFinalized'] else None),
                       'готовая demo-запись с финализированным аудио')
        interaction_target('button', 'В задачи')
        return capture

    def capture_text(text):
        capture = ready()
        field('Текст заметки', text)
        # Observe the actual autosave, not a fixed delay or a direct API replacement.
        wait(lambda: (c := current()) and c['id'] == capture['id'] and c['preparedText'] == text and c['draftEdited'],
             'текст захвата сохранён перед выбором назначения')

    def task_manual_order():
        tasks = [t for t in api('snapshot')['tasks'] if t.get('completedAt') is None]
        return [t['text'].splitlines()[0] for t in sorted(tasks, key=lambda t: t['manualOrder'])]

    def make_note(text, project_title):
        click('tab', 'Главная'); button('Попробовать без микрофона')
        capture_text(text)
        button('В заметки'); click('button', re.compile(r'^' + re.escape(project_title)))
        button('Новая заметка'); wait(lambda: current() is None, 'сохранение заметки ' + text.splitlines()[0])

    def make_task(text):
        click('tab', 'Главная'); button('Попробовать без микрофона')
        capture_text(text)
        button('В задачи')
        field('Дата', '31.12.2099'); field('Время', '15:00')
        button('Сохранить задачу'); wait(lambda: current() is None, 'сохранение задачи ' + text.splitlines()[0])

    try:
        page.goto(BASE, wait_until='networkidle', timeout=60000)
        page.locator('canvas').first.wait_for(state='visible')
        visible_item(page.get_by_role('tab', name='Главная', exact=True), 'Главная')

        # --- Проекты ---
        click('tab', 'Проекты')
        for title in ['Бета сортировка', 'Альфа сортировка']:
            button('Новый проект')
            field('Название проекта', title)
            field('Что сюда складывать', 'Проверка сортировки')
            button('Сохранить')
        for title in ['Твой первый проект', 'Бета сортировка', 'Альфа сортировка']:
            card(title)

        select_sort('projectSort', 'ALPHABETICAL')
        wait(lambda: y('Альфа сортировка') < y('Бета сортировка') < y('Твой первый проект'), 'алфавит проектов')
        select_sort('projectSort', 'CREATED')
        wait(lambda: y('Альфа сортировка') < y('Бета сортировка') < y('Твой первый проект'), 'дата создания проектов')
        select_sort('projectSort', 'UPDATED')
        wait(lambda: y('Альфа сортировка') < y('Бета сортировка') < y('Твой первый проект'), 'дата изменения проектов')
        select_sort('projectSort', 'MANUAL')
        drag('Альфа сортировка', 'Твой первый проект')
        wait(lambda: y('Альфа сортировка') < y('Твой первый проект'), 'manual проектов')
        project_manual = [p['title'] for p in sorted(api('snapshot')['projects'], key=lambda p: p['manualOrder'])]
        assert project_manual[0] == 'Альфа сортировка', project_manual

        # --- Заметки в отдельном проекте ---
        make_note('Бета заметка\nВторой текст', 'Альфа сортировка')
        make_note('Альфа заметка\nПервый текст', 'Альфа сортировка')
        click('tab', 'Проекты'); click('button', re.compile(r'^Альфа сортировка'))
        card('Бета заметка'); card('Альфа заметка')
        select_sort('noteSort', 'ALPHABETICAL')
        wait(lambda: y('Альфа заметка') < y('Бета заметка'), 'алфавит заметок')
        select_sort('noteSort', 'CREATED')
        wait(lambda: y('Альфа заметка') < y('Бета заметка'), 'создание заметок')
        select_sort('noteSort', 'UPDATED')
        wait(lambda: y('Альфа заметка') < y('Бета заметка'), 'дата изменения заметок')
        select_sort('noteSort', 'MANUAL')
        drag('Альфа заметка', 'Бета заметка')
        wait(lambda: y('Альфа заметка') < y('Бета заметка'), 'manual заметок')

        # --- Задачи ---
        make_task('Бета задача\nТело бета')
        make_task('Альфа задача\nТело альфа')
        click('tab', 'Задачи')
        card('Бета задача'); card('Альфа задача')
        select_sort('taskSort', 'ALPHABETICAL')
        wait(lambda: y('Альфа задача') < y('Бета задача'), 'алфавит задач')
        select_sort('taskSort', 'CREATED')
        wait(lambda: y('Альфа задача') < y('Бета задача'), 'создание задач')
        select_sort('taskSort', 'UPDATED')
        wait(lambda: y('Альфа задача') < y('Бета задача'), 'дата изменения задач')
        select_sort('taskSort', 'MANUAL')
        drag('Альфа задача', 'Бета задача')
        wait(lambda: task_manual_order() == ['Альфа задача', 'Бета задача'], 'manual задач в Core')
        saved_manual = task_manual_order()

        select_sort('taskSort', 'ALPHABETICAL')
        assert task_manual_order() == saved_manual
        select_sort('taskSort', 'MANUAL')
        assert task_manual_order() == saved_manual

        page.reload(wait_until='networkidle')
        visible_item(page.get_by_role('tab', name='Главная', exact=True), 'Главная')
        click('tab', 'Задачи')
        wait(lambda: api('preferences')['taskSort'] == 'MANUAL', 'manual режим после reload')
        assert task_manual_order() == saved_manual
        card('Альфа задача'); card('Бета задача')

        snapshot = api('snapshot')
        assert len([t for t in snapshot['tasks'] if t.get('completedAt') is None]) == 2
        assert not errors, errors
        page.screenshot(path=str(OUT / 'sorting-manual-after-reload.png'))
        (OUT / 'result.json').write_text(json.dumps({
            'passed': True,
            'checks': [
                'project alphabetical/created/updated/manual',
                'note alphabetical/created/updated/manual',
                'task alphabetical/created/updated/manual',
                'manual order survives mode switch and reload',
                'capture text autosave acknowledged before distribution',
                'single pointer actions wait for enabled stable Compose geometry',
            ],
            'pageErrors': errors,
        }, ensure_ascii=False, indent=2), encoding='utf-8')
        print('SORTING BROWSER PASSED')
    finally:
        with suppress(Exception):
            page.screenshot(path=str(OUT / 'final.png'))
        with suppress(Exception):
            (OUT / 'semantics.txt').write_text(page.locator('body').aria_snapshot(), encoding='utf-8')
        with suppress(Exception):
            (OUT / 'dom.html').write_text(page.content(), encoding='utf-8')
        with suppress(Exception):
            (OUT / 'snapshot.json').write_text(json.dumps(api('snapshot'), ensure_ascii=False, indent=2), encoding='utf-8')
        with suppress(Exception):
            (OUT / 'input-events.json').write_text(json.dumps(page.evaluate('window.kashaTestInputTrace'), ensure_ascii=False, indent=2), encoding='utf-8')
        (OUT / 'interactions.json').write_text(json.dumps(interactions, ensure_ascii=False, indent=2), encoding='utf-8')
        (OUT / 'page-errors.json').write_text(json.dumps(errors, ensure_ascii=False, indent=2), encoding='utf-8')
        browser.close()
