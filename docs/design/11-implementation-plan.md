# 11. Внедрение в существующее приложение

## 1. Границы поставки документации

Этот пакет меняет требования и добавляет дизайн-ассеты. Он не подменяет текущую параллельную разработку production iOS и не объявляет редизайн внедрённым. Исходники Core, экранов и адаптеров сохраняются; отдельные runtime-изменения выполняются по этапам ниже.

Архитектура остаётся прежней: `kashaCore` хранит предметные правила и контракты; `aiCatalog` — метаданные конкретных движков; `composeApp` — общие экраны и Kasha UI; platform adapters — устройство, permissions, storage, audio, notifications, secure storage. Дизайн-токены, иконки, motion и shared layout не переносятся в Core.

## 2. Этапы и зависимости

| Этап | Состав | Завершён, когда |
|---|---|---|
| E0. Зафиксировать поведение | Сопоставить свежий `main` и активный PR с картой; сохранить existing tests и assets | Известны capabilities и сценарии без предположений о незапушенном коде |
| E1. Основа Kasha UI | Geologica pipeline, токены двух тем, контрастные aliases, spacing/radius, focus и scaling | Один каталог показывает компоненты в нужных состояниях; проверки шрифта и контраста проходят |
| E2. Иконки и графика | Перенести целевую геометрию в единственный `KashaIcons.kt`, убрать MAGIC, добавить нужные glyph, motion/Reduce Motion, фон/orb/wave | SVG и runtime геометрия согласованы; нет второй библиотеки и бесконечных анимаций |
| E3. Общая оболочка | Навигация, safe area, адаптивная структура, sheets/dialogs, единый transport | Управление доступно в компактном/широком окне и с клавиатурой |
| E4. Запись → результат | State-specific Home, freeze pause, cancel/recovery, truthful processing, редактор и сохранение | Пройден capture E2E на реальных adapters; ошибки не теряют данные |
| E5. Контент | Проекты, picker, заметки, задачи/архив, сортировка/reorder, новый pinned Manual | Общие правила и persisted order совпадают на всех платформах |
| E6. Настройки и AI | Capabilities, независимые роли, model lifecycle, consent и permission flows | Настройка действительно влияет на исполнение; privacy-проверки проходят |
| E7. Финальная приёмка | 8 локалей, темы, 200%, screen reader, performance, runtime screenshots | Доказательства по [критериям приёмки](10-acceptance.md), без подмены их статическим чтением |

E1/E2 можно разрабатывать параллельно через общий token contract. E4 требует реальных recorder/audio capabilities и может выполняться одновременно с визуальной частью E5. Нельзя завершить E6 только красивыми переключателями поверх адаптера, игнорирующего выбор.

## 3. Точки изменений

| Область | Существующая точка | Целевое изменение |
|---|---|---|
| Шрифт | [scripts/prepare-fonts.py](../../scripts/prepare-fonts.py) | Заменить источник Commissioner, фиксировать Geologica revision/hash/license, сохранить проверку всех алфавитов; поддержать оси/инстансы согласно foundations |
| Тема | [Design.kt](../../composeApp/src/commonMain/kotlin/brain/studio/Design.kt) | Семантические tokens двух тем и новая typography scale без inline-палитр по экранам |
| Бренд | [Brand.kt](../../composeApp/src/commonMain/kotlin/brain/studio/Brand.kt) | Настоящий знак правильной пропорции; splash-only macro; без обычного текстового логотипа в общей шапке |
| Базовые компоненты | [KashaUi.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaUi.kt) | Размеры, состояния, доступность, surface hierarchy, не создавать альтернативные controls |
| Иконки | [KashaIcons.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaIcons.kt) | Геометрия и motion текущей библиотеки; aliases существующих enum, новые semantic names, Reduce Motion |
| Навигация | [KashaNavigation.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaNavigation.kt), [StudioApp.kt](../../composeApp/src/commonMain/kotlin/brain/studio/StudioApp.kt) | Единая nav surface, adaptive shell; state не привязывать к перерисовке layout |
| Запись/плеер | [HomeAndPlayer.kt](../../composeApp/src/commonMain/kotlin/brain/studio/HomeAndPlayer.kt), [StudioState.kt](../../composeApp/src/commonMain/kotlin/brain/studio/StudioState.kt) | Один transport controller, новые состояния причин отказа, no duplicate controls, freeze pause |
| Редактор | [KashaNoteText.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaNoteText.kt) | Прямой единый текст, cursor/selection/IME, ясный save error, без отдельного title |
| Списки | [ProjectScreens.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ProjectScreens.kt), [TasksScreen.kt](../../composeApp/src/commonMain/kotlin/brain/studio/TasksScreen.kt) | Новые screen blueprints и строки без лишних карточек и метрик |
| Сортировки | [KashaSortControls.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaSortControls.kt), [KashaReorder.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaReorder.kt) | Одна команда sort; drag only Manual, focus и доступная альтернатива |
| Настройки | [SettingsScreen.kt](../../composeApp/src/commonMain/kotlin/brain/studio/SettingsScreen.kt), [AiSettings.kt](../../composeApp/src/commonMain/kotlin/brain/studio/AiSettings.kt) | Группы, реальные capabilities, consent scope, без ложного локального статуса |
| Тексты | [Copy.kt](../../composeApp/src/commonMain/kotlin/brain/studio/Copy.kt), [KashaCopy.kt](../../composeApp/src/commonMain/kotlin/brain/studio/KashaCopy.kt) | Общие точные строки 8 локалей; ошибки по причине, корректный demo/privacy copy |

## 4. Недостающие контракты и исправления поведения

| ID | Разрыв по проверенному коду | Что требуется | Приёмка |
|---|---|---|---|
| GAP-01 | `poll()` добавляет нули в waveform при paused | Не сдвигать буфер/время при паузе; сглаживать только новые реальные samples | UX-02 |
| GAP-02 | RecorderGateway не имеет отмены активной сессии | Общий явный cancel/discard contract и реализация каждого adapter; безопасный cleanup и отказоустойчивость | UX-05/06 |
| GAP-03 | Scrub не представлен отдельным UI action/port guarantee | Добавить согласованный seek contract с duration/position в шкале проигрываемого файла; до этого показывать неинтерактивную waveform | Player tests |
| GAP-04 | iOS PR сохраняет короткий буфер meter, recovery создаёт duration=0/empty peaks | Полная/многоуровневая waveform по файлу; чтение фактической duration при recovery | UX-25; длинная запись |
| GAP-05 | В iOS PR выбранные 3 роли не управляют legacy Intelligence | Подключить общие role engines/requests/capability metadata; показать недоступность до подключения | PRI-02/08 |
| GAP-06 | iOS tidy/rank обходят часть общего workflow | Все adapter responses проходят единые проверки текста и privacy-контракт | UX-09/10; PRI-04 |
| GAP-07 | `NEEDS_MODEL` не имеет отдельного понятного Home recovery; также смешивается с Speech denied | Типизированные причины/capability state, нужное действие по причине | UX-08 |
| GAP-08 | iOS notifications — одно ближайшее одноразовое уведомление | Согласовать системное планирование повторов/отмены с Core либо явно ограничить обещание при закрытом приложении | UX-16/18 |
| GAP-09 | В Manual projects Core не поднимает pinned | Общая stable группировка pinned по pinOrder + unpinned по manualOrder, без перезаписи manual order | UX-19/20 |
| GAP-10 | Consent routing не раскрывает все отправляемые поля | Согласовать фактический payload, labels и consent snapshot; description/id включить при отправке | PRI-03/04/05 |
| GAP-11 | iOS audio quality/savedSpeed prefs не полностью применены | Реальная связь выбора и pipeline; STT получает исходник; UI не обещает неподдерживаемое качество/скорость | Audio adapter tests |
| GAP-12 | Текущая общая ширина ограничена 430dp, не полноценная адаптивность | Контейнеры по доступному размеру окна, не по названию ОС; сохранить общий state | A11Y-02/08 |
| GAP-13 | Стандартные hairline/tertiary не гарантируют AA | Семантические aliases, контраст composited states, видимый focus | VIS-05; A11Y-09 |
| GAP-14 | Нет состояния возврата после успешного tidy в текущем UI | Безопасное локальное undo в рамках текущего редактирования или эквивалентный сохранённый оригинал; не создавать новый раздел | UX-09/10 |
| GAP-15 | Нет отдельного порта порядка закреплённых заметок | Согласовать общий pin-order contract заметок по тому же правилу, что проекты; pinned-top в Manual, сохранность полного manualOrder | D-31; UX-19…22 |
| GAP-16 | Ошибка rank прерывает выбор проекта | После неудачного подбора открыть доступный ручной picker с сохранённым capture и сообщением | UX-11/12; PRI-08 |
| GAP-17 | Сохранение текста задачи может не стать обязательным условием последующего выполнения | Ошибка save прекращает цепочку complete; архивируется только подтверждённая актуальная версия | UX-17/24 |
| GAP-18 | Начальные значения расписания могут повторно сбросить ввод | Инициализация один раз на editor session/entity; recomposition и смена темы не пересоздают введённые дату/время | UX-16/26 |

Названия предлагаемых контрактов являются проектными решениями на внедрение, а не утверждением, что такой метод уже существует. Их API должен сохранять независимость Core от конкретной ОС и UI.

## 5. Миграция и совместимость

### Существующие данные

Новая тема и шрифт не меняют документы. Первая непустая строка остаётся единственным источником title; legacy поля модели не превращаются обратно в пользовательское поле. Все `captureId`, `noteId`, `projectId`, source relationships и правила идемпотентности сохраняются.

Для Manual сохраняется полная прежняя последовательность. Отрисовка закреплённых сверху не должна уничтожать позиции, к которым элемент возвращается после unpin. Не перезаписывать manualOrder при каждом отображении pinned group. Порядок новых элементов и конфликтов одинаковых порядковых значений разрешается детерминированно общим правилом, описанным в контентных экранах.

### Preferences

Сохранённые `theme`, `language`, `autoRecord`, `autoRoute`, `quality`, `savedSpeed` и сортировки сохраняются. Само внедрение редизайна не меняет пользовательский выбор. Dark — главная тема разработки/эталонных скриншотов, но установленный режим `system` продолжает следовать системе.

Новые настройки не добавляются ради оформления. Reduce Motion берётся из системной настройки через capability; ручной выключатель в HTML-каталоге — инструмент проверки ассетов, не новая обязательная Preference продукта.

### Иконки

Реестр в `docs/design/icons` описывает целевую редакцию Kasha Icons; runtime остаётся в одном `KashaIcons.kt`. Экспорты SVG — материал для дизайна и тестов, не предложение загружать несколько библиотек. Переименование enum выполняется с совместимыми aliases либо обновлением всех call sites в одном PR. Для `MAGIC` смысл меняется на text processing; запрет звёздочки сохраняется независимо от временного имени enum.

### Графика

Макрофон подключается только к `KashaSplash`. Home/result/recording используют procedural layers. Источник изображения не заменяет запуск приложения и не меняет порядок permissions. Официальные logo SVG не перегенерируются.

## 6. Согласование с параллельным iOS PR

При старте этого пакета активен [PR #9](https://github.com/vvverman/Kasha/pull/9) из `platform/ios-production`. Документация читает его файлы на фиксированном SHA, но не сливает ветку и не делает вид, что изменения уже в `main`.

Перед каждым функциональным этапом обновлять репозиторий и сравнивать перечисленные точки изменений. iOS-разработчик отвечает за реальный адаптер и его возможности, shared UI — за единый UX на этих контрактах. Нельзя одновременно вводить два recorder state machine или чинить общую бизнес-логику в платформенной ветке копированием.

При публикации документации добавляются только дизайн-файлы и ссылки в существующих документах. Если `main` продвинулся, создаётся commit на свежей базе с сохранением всех параллельных файлов; force-push и перезапись чужого дерева запрещены. Если активная ветка изменилась, карта сохраняет исходный audited SHA и отдельно сообщает, какие новые изменения требуют повторной сверки.

## 7. Формат PR реализации

Описание PR содержит: конкретный пользовательский недостаток; затронутые требования D/GAP/VIS/UX/PRI/A11Y; файлы общего UI и изменения ports; миграцию данных при наличии; скриншоты до/после в двух темах; доказательства релевантных сценариев; фактические ограничения адаптера. Список всех тестов без связи с изменением не заменяет объяснение результата.

Не обновлять только verification-документ и не объявлять stage completed по картинке. Для функциональной ветки сохраняется обязательное правило общего ТЗ: красный CI не считается готовностью к merge.
