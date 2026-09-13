# Карта репозитория и границы реализации

Этот документ связывает дизайн Kasha с существующим кодом. Он нужен перед изменением экранов, состояния, платформенных адаптеров и ресурсов. Визуальная спецификация находится в [основах](./02-foundations.md), [компонентах](./03-components.md) и [графике и движении](./08-graphics-motion.md); здесь зафиксировано, что уже существует и какие разрывы необходимо закрыть.

## 1. Проверенная база и значение статусов

Статический обзор выполнен 13 сентября 2026 года по двум снимкам репозитория:

| Снимок | Точная база | Назначение |
|---|---|---|
| Основная ветка | `main`, `ca3793e4e603dcf340cdaca565f089bd57c5d77a` | Исходная точка для общего UI и доменных сценариев |
| Параллельная iOS-разработка | `origin/platform/ios-production`, `d22d1dfb3cd9baa801afa4249e48988107998a36`, [PR № 9](https://github.com/vvverman/Kasha/pull/9) | Кандидат замены тестовых iOS-адаптеров |

Статусы в документе:

- **Реализовано в исходниках** — найдены соответствующие классы, ветвления или вызовы. Это не означает, что сценарий проверен на устройстве.
- **В работе, PR № 9** — поведение найдено в коде указанной ветки и не считается частью проверенного `main`.
- **Целевое** — требование утверждённого UX/UI-ТЗ; соответствующий путь отсутствует либо неполон.
- **Улучшение в рамках ТЗ** — устранение разрыва в существующем сценарии без добавления отдельной продуктовой функции.

При подготовке карты приложение не запускалось, сборки, CI и взаимодействие с микрофоном/системными уведомлениями не проверялись. Файлы `docs/VERIFICATION*`, тесты и CI-конфигурации являются историческими свидетельствами и точками будущей проверки; их наличие не доказывает работоспособность этих двух SHA на каждой платформе.

Относительные ссылки ниже ведут к файлам основной ветки. Новые файлы PR даны отдельными ссылками на закреплённый SHA: они ещё не существуют в дереве `main`.

## 2. Реальная структура приложения

| Слой | Файлы и символы | Ответственность | Что сохранять при редизайне |
|---|---|---|---|
| Платформонезависимое ядро | [Models.kt](../../kashaCore/src/commonMain/kotlin/brain/model/Models.kt), [BrainData.kt](../../kashaCore/src/commonMain/kotlin/brain/domain/BrainData.kt), [Domain.kt](../../kashaCore/src/commonMain/kotlin/brain/domain/Domain.kt) | Сущности, сортировка, добавление текста в заметку, завершение и расписание задач | Единые правила для всех платформ; никаких отдельных iOS/Web-копий этих правил |
| Контракты инфраструктуры | [Ports.kt](../../kashaCore/src/commonMain/kotlin/brain/domain/Ports.kt): `BrainRepository`, `RecorderGateway`, `AudioGateway`; [Studio.kt](../../kashaCore/src/commonMain/kotlin/brain/studio/Studio.kt): `StudioRepository`, `Preferences` | Доступ к хранилищу, записи, воспроизведению, настройкам и продуктовым операциям | UI вызывает контракты, не файловую систему, AVFoundation или HTTP напрямую |
| AI-контракты | [AiEngines.kt](../../kashaCore/src/commonMain/kotlin/brain/studio/AiEngines.kt), [CaptureWorkflow.kt](../../kashaCore/src/commonMain/kotlin/brain/domain/CaptureWorkflow.kt) | Независимые роли Speech-to-Text, Text processing, Routing; согласие и виды данных; проверки результата обработки | Три роли остаются независимыми; единые правила сохранения смысла и данных |
| Каталог движков | [KashaAiCatalog.kt](../../aiCatalog/src/commonMain/kotlin/brain/ai/KashaAiCatalog.kt), [AiCatalog.kt](../../aiCatalog/src/commonMain/kotlin/brain/studio/AiCatalog.kt) | Конкретные модели, провайдеры и их метаданные | Названия Whisper, Qwen и внешних провайдеров не переносить в Core |
| Общее состояние продукта | [StudioState.kt](../../composeApp/src/commonMain/kotlin/brain/studio/StudioState.kt): `StudioState`, `Tab` | Навигация, текущий Capture, команды, autosave, опрос аудио, блокировка конфликтующих действий | Один экземпляр состояния и один активный аудиосеанс на приложение |
| Общие экраны | [StudioApp.kt](../../composeApp/src/commonMain/kotlin/brain/studio/StudioApp.kt), `HomeAndPlayer.kt`, `ProjectScreens.kt`, `TasksScreen.kt`, `SettingsScreen.kt`, `AiSettings.kt` | Композиция продукта и вызовы `StudioState` | Один общий UX; адаптация размеров и доступных возможностей без переписывания сценариев |
| Kasha UI | [KashaUi.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaUi.kt), [KashaIcons.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaIcons.kt), [Design.kt](../../composeApp/src/commonMain/kotlin/brain/studio/Design.kt) | Собственные контролы, графика, типографика, цвета и анимации | Единственная точка продуктовых контролов и glyphs |
| JVM-инфраструктура | [Store.kt](../../runtime/src/main/kotlin/brain/runtime/Store.kt): `FileBrainStore`; [StudioProcessing.kt](../../runtime/src/main/kotlin/brain/runtime/StudioProcessing.kt): `PreferenceStore`, `StudioProcessor`, `StudioDiskRepository`; [RoutedIntelligence.kt](../../runtime/src/main/kotlin/brain/runtime/ai/RoutedIntelligence.kt): `RoutedStudioIntelligence` | Локальные файлы, обработка аудио, установка и исполнение моделей, внешний AI через отдельную границу | Это инфраструктура для JVM/Desktop/localhost, а не общий обязательный удалённый backend |
| Desktop-оболочка | [Main.kt](../../desktopApp/src/main/kotlin/brain/desktop/Main.kt), [DesktopServices.kt](../../desktopApp/src/main/kotlin/brain/desktop/DesktopServices.kt), `DesktopRecorder`, `DesktopAudio`, `DesktopReminder` | Сборка зависимостей, устройства ввода/вывода, окно и упаковка | Платформенные вызовы остаются здесь; общий UI не получает зависимость от AWT/Java Sound |
| Web-оболочка | [main.kt](../../composeApp/src/wasmJsMain/kotlin/main.kt), [WebApi.kt](../../composeApp/src/wasmJsMain/kotlin/brain/web/WebApi.kt), [WebRecorder.kt](../../composeApp/src/wasmJsMain/kotlin/brain/web/WebRecorder.kt), [WebAudio.kt](../../composeApp/src/wasmJsMain/kotlin/brain/web/WebAudio.kt) | `ComposeViewport`, HTTP к runtime, вызовы `globalThis.kashaPlatform` | Учитывать наличие локального runtime; не обещать автономный browser-only inference |
| iOS-оболочка в main | [KashaApp.swift](../../iosApp/Sources/KashaApp.swift), [IosEntry.kt](../../composeApp/src/iosMain/kotlin/brain/ios/IosEntry.kt) | SwiftUI-контейнер для общего Compose UI, тестовые repository/recorder/audio | Тестовую имитацию нельзя представлять как настоящую запись или расшифровку |

Состав Gradle-проектов закреплён в [settings.gradle.kts](../../settings.gradle.kts). `kashaCore` и `composeApp` имеют JVM, Android, iOS arm64/simulator arm64 и Wasm browser targets. Объявленный target означает возможность сборки соответствующего модуля, а не наличие готового приложения, адаптеров и установочного пакета.

`compose.material3` уже присутствует в общем модуле. `MaterialTheme`, `Text` и низкоуровневая реализация внутри Kasha UI являются техническим слоем. Запрет типового Material-вида реализуется собственной геометрией, токенами, взаимодействиями и контролами; один импорт Material не является доказательством нарушения дизайна.

## 3. Карта экранов и переходов

### 3.1. Корневая композиция

`StudioApp(state)` запускает `launch()`, `poll()`, debounce-autosave после `editRevision` и привязывает действие к живущему на уровне приложения coroutine scope. Над состоянием экрана расположены брендовый ряд, ниже — `GlobalPlayer` и четыре пункта навигации.

Текущий порядок приоритетов в центральной области важен при переносе в sheets и overlays:

1. `error != null` → `Notice`.
2. `confirmDelete` → подтверждение удаления текущего Capture.
3. `confirmListenId != null` → подтверждение остановки записи перед прослушиванием.
4. `taskScheduleTarget != null` → редактор срока и повторов напоминания.
5. `editingProjectId != null` → редактор проекта.
6. `choosingProject` → выбор места сохранения заметки.
7. Соответствующий экран `Tab.HOME / PROJECTS / TASKS / SETTINGS`.

Сейчас эти состояния заменяют центральный контент, а не открываются настоящими bottom sheets. Переход к целевым sheets должен сохранить блокировку повторной отправки, возврат к тексту и непрерывность аудио.

### 3.2. Экранная карта

| Экран / состояние | Источник и символ | Вход / существующее поведение | Разрыв относительно целевого дизайна |
|---|---|---|---|
| Splash | [Brand.kt](../../composeApp/src/commonMain/kotlin/brain/studio/Brand.kt): `KashaSplash`, `KashaBrandSlot` | До `initialized`; solid-знак 190 × 224 dp и версия | Сохранить официальный знак; проверить пропорции при новой композиции |
| Главная, idle | [HomeAndPlayer.kt](../../composeApp/src/commonMain/kotlin/brain/studio/HomeAndPlayer.kt): `HomeScreen` | Нет текущего Capture и записи; приглашение, большой знак, `KashaCaptureMark`; recovery/demo при соответствующем флаге | Нет Recording Orb и процедурного фирменного фона; текущий capture mark декоративный и не начинает запись |
| Главная, recording / paused | `HomeScreen`, `StudioState.recording`, `recordPhase` | Крупный таймер, статус, `Wave(liveWave)`; pause/resume и завершение находятся в Global Player | Нет команды отмены активной записи; пауза не сохраняет waveform; нет целевого размещения трёх действий |
| Главная, processing | `HomeScreen`, `CaptureStatus`, `working` | `TRANSCRIBING`/`COMPACTING`/прочие стадии отображаются текстом и `ProcessingRing` | Нет orb processing visual; `NEEDS_MODEL` и разные причины отказа не имеют полного собственного UX |
| Главная, result | `HomeScreen`, `KashaNoteText` | Один редактируемый документ; «Привести в порядок», «В заметки», «В задачи», удаление Capture | Сохранить две отдельные команды; заменить glyph `MAGIC`; улучшить saving/error и длинный текст с клавиатурой |
| Global Player | `GlobalPlayer` | Постоянно вне вкладок; запись, пауза, завершение, загруженное аудио, play/pause/stop, waveform и время | Нет управления seek в общем UI; приоритизация processing/loaded audio требует явной таблицы состояний |
| Проекты | [ProjectScreens.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ProjectScreens.kt): `ProjectsScreen`, `ProjectLine` | Список, текущая сортировка, создание, открытие, редактирование | Сейчас каждая строка — `KashaListCard`; требуются спокойные строки с меньшим количеством контейнеров |
| Редактор проекта | `ProjectEditor` | Имя + инструкция для AI, сохранение, pin; у закреплённого проекта — движение вверх/вниз | Сохранить instruction; label/error по новому Field; не вводить лишнее поле описания ради макета |
| Выбор проекта для Capture | `DestinationScreen` | После `sendToNotes()`; релевантность влияет на порядок; можно создать проект и вернуться к текущему Capture | Не превращать рейтинг 0–4 в процент уверенности; сохранять понятный возврат без потери текста |
| Выбор новой / существующей заметки | `DestinationScreen`, `targetProjectId` | Новая заметка или добавление Capture в конец выбранной; без drag в picker | Сохранить append и его идемпотентность, не заменять исходное содержимое заметки |
| Заметки проекта | `ProjectsScreen`, `NoteLine` | Пользовательская сортировка, title из первой строки, preview до 2 строк, pin | В строке отсутствуют modified date и признак прикреплённого аудио из канона |
| Просмотр заметки | `ProjectsScreen`, `selectedNoteId` | Текст, pin/edit, список аудиоисточников; источник запускается через `requestListen` | Уточнить metadata и действия; не добавлять второе title-поле |
| Редактор заметки | `NoteEditorScreen` | Локальный `body`, Save/Cancel, прямое редактирование документа | Это явное сохранение, не autosave Capture; уход со вкладки сейчас может сбросить несохранённую локальную правку |
| Активные задачи / архив | [TasksScreen.kt](../../composeApp/src/commonMain/kotlin/brain/studio/TasksScreen.kt): `TasksScreen` | Сортировка, вход в архив, открытие задачи; manual только для активных | В строке нет completion control и reminder marker; задача завершается в detail |
| Задача | `TaskDetailScreen` | Текст; сохранение изменений; срок и повтор; завершение; удаление; завершённая — read-only | Нет задержанного ухода строки с морфингом; удаление вызывается сразу, без целевого подтверждения |
| Срок и повтор | `TaskScheduleScreen` | Дата и время строками; срок должен быть в будущем; один из 7 `ReminderRepeat`; создание или перенос задачи | Это страница, не sheet; независимые «срок»/«напоминание» не представлены отдельными сохранёнными полями |
| Настройки | [SettingsScreen.kt](../../composeApp/src/commonMain/kotlin/brain/studio/SettingsScreen.kt): `SettingsScreen` | Качество, скорость сохранённого аудио, autoRecord, autoRoute, тема, язык, AI | Нет полной группы «О приложении»; возможности конкретного адаптера должны быть явными |
| AI: роль, модель, провайдер | [AiSettings.kt](../../composeApp/src/commonMain/kotlin/brain/studio/AiSettings.kt): `AiSettingsSection` | Выбор роли, установка/удаление модели при наличии gateway, провайдер, model IDs, endpoint, API key, consent, test connection | Capability-состояние сейчас частично выводится по наличию сервиса; это недостаточно для правдивого iOS UX |
| Язык | `SettingsScreen`, `languagePage` | System + 8 языков; выбор сохраняется в `Preferences` | Сохранить полный набор; проверить 200% текста и длинные локализации |

`StudioState.navigate()` сбрасывает выбранный проект, заметку, задачу и открытые редакторы. Текущее аудио и Capture не сбрасываются. Не следует без отдельного решения обещать сохранение глубины навигации каждой вкладки: это целевое улучшение, которого сейчас нет в состоянии.

## 4. Состояние и модели, на которые опирается UI

### 4.1. Capture и аудиосеанс

В [StudioState.kt](../../composeApp/src/commonMain/kotlin/brain/studio/StudioState.kt) запись до её завершения представлена `recordPhase`, `elapsed`, `liveWave`, `pending` и самим `RecorderGateway`. Она не тождественна объекту `Capture` в хранилище.

После завершения текущий Capture определяется как первый `snapshot.captures`, у которого `Capture.isInbox == true`: у него нет ни `noteId`, ни `taskId`. В интерфейсе не нужен раздел с названием «Входящие»; это внутренний признак жизненного цикла.

| Поле / вычисляемое состояние | Назначение для дизайна |
|---|---|
| `Capture.status` | `RECORDING`, `QUEUED`, `TRANSCRIBING`, `COMPACTING`, `POLISHING`, `READY`, `NEEDS_MODEL`, `FAILED`; enum уже содержит больше состояний, чем четыре композиционных режима Главной |
| `status.isWorking` / `StudioState.working` | Долгая операция; текст и команды должны следовать конкретной стадии, не имитировать процент |
| `Capture.transcript`, `preparedText`, `draftEdited`, `textToSave` | Выбор текущего текста; после пользовательского редактирования именно подготовленный текст является источником сохранения |
| `Capture.title` / `StudioState.title` | Производные данные; пользователь редактирует одну сущность текста, title-поле не возвращается |
| `audioFinalized` | Разрешение отправки из результата и воспроизведения готового источника; наличие текста само по себе не означает завершение работы с файлом |
| `audioFileName`, `durationSeconds`, `waveform`, `savedSpeed` | Доступный источник и его временная шкала; waveform должна соответствовать реально воспроизводимому файлу |
| `noteId`, `taskId`, `appendedAt` | Ровно одно назначение Capture; связь источника с заметкой/задачей и порядок добавления |
| `relevance`, `rankingApplied` | Ранжирование проектов; значения 0–4 не являются вероятностью |
| `simulated` | Явная маркировка демонстрационных данных; нельзя убирать её ради чистоты макета |
| `pending` | На устройстве есть незавершённый аудиофайл; показать восстановление и не начинать новую запись поверх него |
| `busy` / `controlBusy` | Отдельные блокировки продуктовых и аудиокоманд; запрет двойного отправления/повторного старта |
| `AudioTelemetry.phase/position/duration/level` | Фактическое состояние плеера; не выводить прогресс из UI-таймера независимо от gateway |

`StudioApp` запускает autosave Capture через 400 мс после правки; `flush()` использует mutex и revision, чтобы позднее завершение сохранения не стерло новую правку. Перед tidy и распределением вызывается `flush()`. При редизайне этот порядок операций сохраняется.

`startRecording()` требует: нет текущего Capture, нет pending-файла, playback в `idle`. `requestListen()` во время записи открывает подтверждение; `confirmStopAndListen()` сначала завершает и сохраняет запись и только затем запускает источник. Пауза записи также считается активной записью: она не разрешает параллельный playback.

### 4.2. Контент и организация

| Модель / правило | Реальный контракт | Следствие для интерфейса |
|---|---|---|
| `Project` | `title`, `description`, `instruction`, `pinned`, `pinOrder`, `manualOrder`, даты | Редактор использует title + instruction; существующее description сохраняется при обновлении |
| `Note` | `body`, производный `title`, `projectId`, pin/manual order, даты | Первая непустая строка тела — название; preview не повторяет эту строку |
| `NoteText.title()` | Первая непустая строка, с техническим ограничением длины | Использовать общий helper для строки списка и заголовка, не второй алгоритм во view |
| `NoteText.append()` / `BrainData.distribute()` | Добавление нового текста в конец существующего; обработка повторного назначения | Повторное нажатие/повтор запроса не должен добавлять текст дважды |
| `UserSort` | Алфавит, создание, изменение, manual; pin отдельно | Смена сортировки не переписывает `manualOrder`; закреплённая группа остаётся наверху |
| `ProjectOrder.sorted()` | Pin и релевантность для выбора проекта | Порядок picker может отличаться от пользовательской сортировки раздела «Проекты» |
| `Task` | Один `text`, optional `projectId`, `dueAt`, `reminderRepeat`, `nextReminderAt`, `completedAt`, manual order | Один Capture создаёт одну задачу; не разбивать текст на список автоматически |
| `TaskSchedule.rollDeadline()` | Просроченный срок переносится на ближайший следующий день в то же локальное время | Объяснять текущий перенос в UX; не менять его на другую бизнес-логику в рамках внешнего редизайна |
| `TaskSchedule.nextReminder()` | 10/30 минут, час, день, неделя, выходные, будни | Это повтор уведомления той же задачи; не создание следующей экземплярной задачи |
| `BrainData.completeTask()` / `SnapshotQueries.tasks()` | `completedAt` отделяет архив от активного списка | Морфинг и короткая задержка — слой UI; сохранение завершения должно быть подтверждено repository |

В модели нет assignee, priority, тегов, комментариев, отдельного workflow-статуса задачи и процента готовности. Их не вводят ради насыщения макета. В `TaskScheduleScreen` нет самостоятельного выключателя напоминаний или отдельного произвольного времени уведомления до срока; такой контрол нельзя считать работающим без изменения доменного контракта.

### 4.3. AI и настройки

В [AiEngines.kt](../../kashaCore/src/commonMain/kotlin/brain/studio/AiEngines.kt) существуют `SpeechToTextEngine`, `TextProcessingEngine`, `RoutingEngine`, `CompositeIntelligence`, `AiSelection`, `AiPrivacy`, `AiPackageGateway`, `CloudAiGateway`. `Intelligence` в `Studio.kt` — совместимый facade над прежним API, а не три независимых копии всего Core.

`AiPlatformServices` в [AiPlatformServices.kt](../../kashaCore/src/commonMain/kotlin/brain/studio/AiPlatformServices.kt) добавляет package/cloud-сервисы к repository. Его отсутствие включает `NoopAiPackageGateway` и `NoopCloudAiGateway`. Наличие строки модели в `AiCatalog` не доказывает наличие установленной или работающей модели на этой платформе.

`AiPrivacy.dataFor()` задаёт виды передаваемых данных для роли: аудио, текст заметки, названия и инструкции проектов. Провайдер и соответствующее согласие должны отображаться по выбранной роли. API key находится в запросе настройки gateway, не в `Preferences` или `AppSnapshot`; см. [AiRequests.kt](../../kashaCore/src/commonMain/kotlin/brain/studio/AiRequests.kt), [CloudGateway.kt](../../runtime/src/main/kotlin/brain/runtime/ai/CloudGateway.kt), [Secrets.kt](../../runtime/src/main/kotlin/brain/runtime/ai/Secrets.kt), [ExternalAiClient.kt](../../runtime/src/main/kotlin/brain/runtime/ai/external/ExternalAiClient.kt).

`Preferences` уже хранит `autoRecord = true`, `autoRoute = false`, качество 0–3, `savedSpeed = 1.5`, system language/theme и три режима сортировки. `autoRoute` после готовности открывает выбор проекта; это не доказательство автоматической безусловной записи результата в конкретный проект.

Исключение текущего main: `IosTestRepository` принудительно сбрасывает `autoRecord = false` при загрузке и сохранении настроек. Это ограничение тестового адаптера; его нельзя переносить в каноническое поведение production iOS.

## 5. Активы и существующая дизайн-система

| Ресурс | Где находится | Текущее состояние | Требуемое действие при реализации |
|---|---|---|---|
| Официальный знак | [kasha_logo.svg](../../composeApp/src/commonMain/composeResources/drawable/kasha_logo.svg) | Общий SVG | Сохранить геометрию; применять через `KashaBrandSlot` |
| Solid-знак | [kasha_logo_solid.svg](../../composeApp/src/commonMain/composeResources/drawable/kasha_logo_solid.svg) | Общий SVG; используется splash | Сохранить; использовать как основу app icon |
| Шрифты | [prepare-fonts.py](../../scripts/prepare-fonts.py), `Design.kt` | Скрипт скачивает закреплённый Commissioner, проверяет алфавиты 8 языков, генерирует четыре статических экземпляра и OFL | Перевести всю подготовку и загрузку на Geologica Variable; сохранить проверку алфавитов, лицензию и воспроизводимость |
| Генерируемые шрифтовые файлы | `composeApp/src/commonMain/composeResources/font/` | Каталог исключён в [.gitignore](../../.gitignore); сами TTF не являются tracked-активами этого снимка | Не считать замену ссылок в Theme завершённой, пока pipeline не создаёт новые корректные ресурсы |
| Цвет и шкала | `Design.kt`: `StudioTheme` | Тёплая база уже есть, но другая типографика, surfaces и monochrome primary | Внедрить токены канона и проверенные semantic aliases из foundations |
| Glyphs | `ui/KashaIcons.kt`: `Glyph`, `KashaIcon`, `motion` | 22 собственных Canvas-glyphs, сетка 24, stroke 1.9, короткое motion | Расширять единый набор; не подключать второй pack. Подготовить согласованные SVG-исходники/экспорты без расхождения геометрии |
| Обёртки | `Design.kt`: `Action`, `IconAction`, `Editor`, `Wave`, `ProcessingRing` | Делегируют в Kasha UI | Сохранить обратимую точку миграции экранов, не вводить параллельные StyledButton-подсистемы |
| Контролы | `KashaUi.kt`: `KashaButton`, `KashaIconButton`, `KashaQuietButton`, `KashaField`, `KashaSwitchRow`, `KashaSlider`, `KashaPanel`, `KashaListCard` | Собственный внешний слой; ряд размеров и паттернов расходится с новым каноном | Доработать общий слой сначала, затем перевести композиции |
| Редактор документа | [KashaNoteText.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaNoteText.kt) | Один текстовый документ; общий read/edit-вариант | Сохранить единый документ и управление курсором/выделением |
| Waveform | `KashaWaveform` | Canvas из переданных peaks; solid alpha, без канонического маскированного градиента | Добавить требуемую геометрию, цвет, freeze и доступное состояние без искусственной аудиоволны |
| Capture mark | `KashaCaptureMark` | Рисунок карточки с фиксированными декоративными bars | Заменить на процедурный Recording Orb; декоративные bars не объявлять реальным сигналом |
| Navigation | [KashaNavigation.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaNavigation.kt) | Отдельный фон и скругление выбранного пункта | Общая navigation surface с локальным spot, без четырёх отдельных pills |
| Reorder / sort | [KashaReorder.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaReorder.kt), [KashaSortControls.kt](../../composeApp/src/commonMain/kotlin/brain/studio/ui/KashaSortControls.kt) | Long press только в manual; отмена восстанавливает авторитетный порядок | Добавить lift, haptic, аккуратное перемещение и доступную альтернативу жесту |
| Иконки пакета приложения | [make-icon.py](../../desktopApp/packaging/make-icon.py), [KashaAppIcon.svg](../../iosApp/KashaAppIcon.svg), [Contents.json](../../iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json) | Отдельные точки упаковки платформенных app icons | Проверить происхождение от solid-знака; не менять идентичность продукта по платформам |

Ветка PR № 9 не добавляет Geologica, Orb, новый фон, SVG-glyphs или новую navigation bar. Они являются задачами общего редизайна. Текстовый `Text("Kasha")` рядом со знаком в `StudioApp` также не соответствует правилу использования только фирменного знака в роли логотипа.

## 6. Матрица возможностей платформ

Все формулировки ниже относятся к исходникам. Готовность поставки и качество на устройстве требуют отдельной проверки по [критериям приёмки](./10-acceptance.md).

| Возможность | Desktop / JVM main | Web main | iOS main | iOS PR № 9 | Целевой UX |
|---|---|---|---|---|---|
| Общий UI/Core | `StudioApp` + `StudioState` + Core | Те же | Те же | Те же | Один контракт сценариев; адаптивная композиция |
| Хранение контента | `FileBrainStore`, локальные файлы | HTTP к локальному runtime и его FileBrainStore | `IosTestRepository`, NSUserDefaults | `IosRepository` + Application Support JSON, миграция тестовых ключей | Не требовать аккаунт/облако; показывать реальные ошибки сохранения |
| Микрофон | `DesktopRecorder`, Java Sound, WAV journal | `BrowserRecorder` → JS bridge | `IosTestRecorder`, имитация | `AVAudioRecorder`, AAC M4A, разрешение ОС | Реальный сигнал и pause/resume; отказ разрешения не маскируется состоянием записи |
| RMS / waveform записи | Реальный PCM-уровень через `SignalLevel` | Уровень из browser bridge | Постоянный условный уровень `.42` | `averagePowerForChannel`, нормализация dB | Freeze на паузе; одинаковая perceptual-нормализация между платформами |
| Waveform полного аудио | Peaks финального файла в `StudioProcessor` | Из того же runtime | Декоративный паттерн demo | `IosRecorder` держит 512 последних уровней; затем `IosRepository.createAudioCapture()` сохраняет `waveform.takeLast(256)` | Для playback необходима форма всего файла; хвост записи нельзя растянуть как весь источник |
| Восстановление записи | WAV pending journal | Порт `pending/recover` browser bridge | Pending всегда false | Перенос первого pending M4A; создаёт capture с duration 0 и пустой waveform | Проверить восстановленную длительность/волновую форму; recovery не теряет файл |
| Playback | `DesktopAudio`, реальный вывод | `WebAudioGateway` → bridge | `IosTestAudio`, только telemetry | `AVAudioPlayer`, позиция/rate/pause/resume | Запись и playback взаимоисключаются; seek согласован с реальным источником |
| Speech-to-Text | `RoutedStudioIntelligence`, локальный или выбранный внешний адаптер | Через локальный runtime | `DemoIntelligence` | Apple Speech URL request, `requiresOnDeviceRecognition = true`, partial results выключены | Показывать фактический движок/доступность языка; не обещать live transcription |
| Text processing / Routing | Роли читаются независимо из `Preferences.ai` | Через тот же runtime | Demo | Детерминированные локальные замены и совпадение слов; не LLM | Честное описание возможностей; единые проверки сохранения текста |
| Переключение AI-движков | Реальный runtime route по роли | Через runtime | Нет реального inference | `Preferences.ai` сохраняется, но iOS inference жёстко связан с `IosOnDeviceIntelligence` | Выбранное в UI должно совпадать с используемым; недоступный выбор не имитировать |
| Установка моделей / внешний AI | `AiPlatformServices` | Проксируется через `WebBrainRepository` | Noop gateways | Noop gateways | Кнопки и consent-состояния зависят от реальной capability платформы |
| Очистка тишины / скорость сохранения / качество | `StudioProcessor`: compact PCM, `atempo`, кодирование | Через runtime | Имитация | `IosRecorder` использует фиксированную запись 44.1 kHz mono AAC; pipeline настроек не подключён | Не представлять сохранённый параметр как применённый к файлу без адаптера |
| Напоминания при открытом приложении | `DesktopReminder.available` только macOS; polling из `StudioState` | `NoopReminderGateway`; `claimTaskReminders()` возвращает пустой список | Noop gateway | `IosReminderRepository` + `UNUserNotificationCenter` | Срок сохраняется независимо от наличия системной доставки; доступность объясняется |
| Напоминания при закрытом приложении | Нет заранее планируемой очереди; osascript вызывается живым процессом | Не реализованы | Не реализованы | Одно `repeats = false` уведомление на задачу; дальнейший sync зависит от работы приложения | Не обещать все периодические уведомления в фоне до отдельной проверки планирования |
| Системный язык | Из desktop shell | `navigator.language` | Фиксированная системная строка в iOS entry | `systemLanguage = "ru-RU"`, repository использует тот же fallback | «Как в системе» должен следовать реальному языку ОС |
| Reduce Motion / haptics | Отдельной общей capability не найдено | То же | То же | PR не добавляет | Ввести тонкий платформенный доступ и общее правило motion; отсутствие haptic не ломает действие |

Отдельно по установочным платформам:

| Платформа | Наличие в репозитории | Граница утверждений |
|---|---|---|
| macOS | Desktop shell; [desktopApp/build.gradle.kts](../../desktopApp/build.gradle.kts) задаёт `TargetFormat.Dmg` и macOS packaging | Есть код пути поставки; данный обзор не проверял .app/.dmg |
| Windows / Linux | Возможность JVM target и общие модули | Нет отдельного подтверждённого installer target в рассматриваемой desktop-конфигурации; некоторые сервисы macOS-специфичны |
| Android | Android targets библиотек в `kashaCore`/`composeApp` | В `settings.gradle.kts` нет отдельного `androidApp`; нет завершённой Android composition root с recorder/storage/AI/reminders |
| iOS | Swift-оболочка и общий static framework | Main — тестовые адаптеры; PR — новые нативные адаптеры в работе |
| Web | Wasm executable + browser bridge + JVM runtime | Не обещать самостоятельный сайт без локальной инфраструктуры для основного сценария |

Реализация Web bridge находится в [browser-platform.js](../../composeApp/src/wasmJsMain/resources/browser-platform.js): `MediaRecorder`, RMS через `AnalyserNode`, журнал аудиочанков в IndexedDB, проверка receipt перед удалением журнала и `HTMLAudio`. Этот файл — часть обязательной проверки аудиоконтракта при добавлении cancel/seek. В нём также есть остановка после 60 MiB накопленного аудио; интерфейс должен обработать переход в idle/pending как восстановимый результат, а не продолжать показывать работающий микрофон.

### 6.1. Точные файлы PR № 9

Для интеграции iOS использовать содержимое PR, а не копировать тестовые private-классы из старого `IosEntry.kt`:

| Файл PR | Символы и назначение |
|---|---|
| [IosEntry.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/composeApp/src/iosMain/kotlin/brain/ios/IosEntry.kt) | `MainViewController`, соединение общего UI с новыми адаптерами |
| [IosRecorder.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/composeApp/src/iosMain/kotlin/brain/ios/IosRecorder.kt) | `IosRecorder`, микрофон, pause/resume, pending, meter |
| [IosAudio.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/composeApp/src/iosMain/kotlin/brain/ios/IosAudio.kt) | `IosAudio`, AVAudioPlayer, playback telemetry |
| [IosRepository.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/composeApp/src/iosMain/kotlin/brain/ios/IosRepository.kt) | `IosRepository`, persistent BrainData, capture pipeline и миграция |
| [IosPaths.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/composeApp/src/iosMain/kotlin/brain/ios/IosPaths.kt) | `IosPaths`, Application Support, audio/pending, атомарная запись JSON |
| [IosOnDeviceIntelligence.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/composeApp/src/iosMain/kotlin/brain/ios/IosOnDeviceIntelligence.kt) | `IosOnDeviceIntelligence`, Apple STT и детерминированные tidy/rank |
| [IosReminder.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/composeApp/src/iosMain/kotlin/brain/ios/IosReminder.kt) | `IosReminder.sync`, системная очередь локальных уведомлений |
| [IosReminderRepository.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/composeApp/src/iosMain/kotlin/brain/ios/IosReminderRepository.kt) | Декоратор repository; синхронизация очереди после изменения задач |
| [ReminderGateway.kt](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/kashaCore/src/commonMain/kotlin/brain/studio/ReminderGateway.kt) | Добавляет `suspend fun sync(tasks: List<Task>) = Unit` к общему порту |
| [Info.plist](https://github.com/vvverman/Kasha/blob/d22d1dfb3cd9baa801afa4249e48988107998a36/iosApp/Info.plist) | iOS-конфигурация разрешений / системных возможностей |

## 7. Разрывы, которые блокируют точное выполнение ТЗ

| Приоритет | Разрыв, подтверждённый чтением кода | Что должна обеспечить реализация |
|---|---|---|
| P0 | `RecorderGateway` не имеет отмены активной записи; `StudioState.discard()` удаляет только существующий Capture | Явный безопасный контракт отмены до Capture, сохранение текущего журнала при отказе пользователя от отмены; см. capture/player |
| P0 | `StudioState.poll()` при paused сдвигает `liveWave`, добавляя 0 каждые 65 мс; 80 отсчётов исчезают примерно за 5,2 с | Заморозить последнюю реальную форму на всё время паузы; продолжить после resume |
| P0 | `HomeScreen` имеет retry-ветку только для `FAILED`; `NEEDS_MODEL` попадает в общий result | Отдельные доступные состояния для отсутствия движка/языка и запрета системного распознавания; сохранить аудио и ручной ввод |
| P0 | PR iOS сохраняет выбор AI, но исполняет фиксированный `IosOnDeviceIntelligence` | Согласовать capability + выбранную роль + фактический adapter; не показывать ложное подключение модели |
| P0 | PR `IosRepository.tidy/rank` вызывают intelligence напрямую, обходя `CaptureWorkflow` | Применить общие проверки сохранения смысла/размера/чисел/отрицаний и корректности scores на всех путях |
| P0 | PR playback waveform представляет хвост meter-буфера, recovery даёт пустые peaks и длительность 0 | Считать waveform/длительность из всего финального источника; не использовать хвост как полную шкалу seek |
| P1 | Редакторы заметки/проекта/задачи держат часть текста в локальном `remember`; навигация закрывает их | Явная защита несохранённой правки и проверяемый save/error/return; не потерять текст при клике вкладки |
| P1 | `TaskDetailScreen` может сохранить текст, затем завершить задачу без проверки успеха первой операции | Завершение после правки допускается только при успешном сохранении; ошибка оставляет редактирование доступным |
| P1 | Нет завершения задачи прямо из строки; текущий вызов сразу меняет snapshot | Completion control, подтверждение сохранения состояния, motion и уход в архив по целевому сценарию |
| P1 | Общая ширина приложения жёстко ограничена 430 dp; ряд 32/34 dp modifiers в header; отсутствует явная общая политика IME/safe areas | Адаптивный контейнер, 44+ dp цели, 200% текста, доступные действия над клавиатурой; проверить фактический hit area, не только рисунок |
| P1 | Global Player помечен строкой `global-player`; у waveform нет локализованного состояния для screen reader | Человеческие semantic labels и редкое обновление секунд; графика не дублирует каждую полосу в accessibility tree |
| P1 | Не найден общий reduce-motion/haptic adapter; ProcessingRing всегда вращается | Системная настройка ограничивает декоративное движение во всех компонентах; действие остаётся понятным без haptic |
| P1 | Исходные token/font/icon/nav/orb/background не соответствуют канону | Последовательная миграция foundations → Kasha UI → экраны; отдельный набор контролов не создавать |
| P1 | Часть настроек аудио/напоминаний iOS/Web не имеет соответствующего исполнительного пути | Capability-состояние с понятной причиной; не сохранять видимость работающей функции вместо результата |
| P2 | Строки заметок не показывают дату изменения/аудио; проекты и настройки перегружены карточками | Обновить контент строк и иерархию surfaces без новых сущностей |

Эта таблица не является списком разрешений на изменение бизнес-правил. Новая модель срока/напоминания, новая структура Capture или удаление функций требуют согласованного изменения контракта, миграции и тестов, а не только нового экрана.

## 8. Уже существующие сценарии, которые обязательно сохранить

1. Запись запускается автоматически, когда это включено, нет незавершённого Capture и pending-файла. Восстановление имеет приоритет над новым стартом.
2. Во время записи можно переходить между четырьмя разделами; управление записью остаётся в Global Player.
3. Запись и playback не работают одновременно. Прослушивание из заметки во время записи сначала предлагает сохранить текущую запись.
4. Пользователь редактирует один текст; заголовок вычисляется по первой непустой строке.
5. «Привести в порядок» — отдельное действие. Сохранение в заметки/задачи не должно безусловно запускать литературную переработку.
6. «В заметки» и «В задачи» — две отдельные команды. Для задачи не требуется выбирать проект.
7. Для заметки пользователь выбирает проект, затем новую или существующую заметку; новую часть добавляют в конец. Повтор запроса не дублирует добавление.
8. Проект можно создать прямо из выбора назначения и вернуться к тому же Capture, не теряя связь с операцией.
9. У заметки сохраняются аудиоисточники и порядок их добавления; прослушивание не меняет текст заметки.
10. Ручной порядок проектов, заметок и активных задач сохраняется после смены сортировки; pin order остаётся отдельным.
11. Выполненные задачи находятся в архиве; `ReminderRepeat` повторяет напоминание той же задачи. Пропущенный срок переносится по `TaskSchedule.rollDeadline()`.
12. AI-роли выбираются независимо там, где это поддержано исполнительными адаптерами. Внешний AI требует явного выбора и согласия по передаваемым данным.
13. Demo-данные распознаются как demo; локальное хранение и фактическое применение внешнего AI не скрываются за общей декоративной надписью.

## 9. Ограничения параллельной разработки

Общий редизайн и PR № 9 пересекаются через интерфейсы, даже если меняют разные файлы. Без текстового merge-конфликта всё ещё возможен несовместимый runtime-контракт.

| Зона работы | Правило интеграции |
|---|---|
| `commonMain` UI, токены, glyphs | Основной участок редизайна. Изменения держать в Kasha UI; экраны используют общие элементы |
| `StudioState` | Один владелец интеграции для изменения навигации/аудиосостояний; не создавать второй state holder для Orb или Player |
| `Ports.kt`, `ReminderGateway.kt`, `StudioRepository` | Любое новое поле/метод сверять с desktop, web, iOS main и PR-адаптерами. Учесть уже добавленный в PR `sync(tasks)` |
| `composeApp/src/iosMain` | До интеграции PR не переписывать IosEntry целиком и не возвращать `IosTest*` поверх новых файлов. Доработки PR оформлять относительно его актуального SHA |
| `iosApp/Info.plist`, Swift root | Согласовать lifecycle, keyboard/safe area и разрешения с владельцем iOS; визуальный редизайн не отменяет платформенные capabilities |
| Сериализуемые модели | Сохранять совместимость полей и defaults; переименование/удаление — отдельная миграция. Учитывать [Migration.kt](../../kashaCore/src/commonMain/kotlin/brain/domain/Migration.kt) и миграцию NSUserDefaults в PR |
| Шрифтовый pipeline | `prepare-fonts.py`, ресурсы, лицензия, `Design.kt` и CI-сборка обновляются согласованно; иначе ссылки на `Res.font` могут указывать на отсутствующие файлы |
| AI/privacy | Подключение capabilities делать через контракт, а не условие `if (ios)` в каждом экране; secrets и endpoints не переносить в UI state |
| App icon / packaging | Сохранить bundle identifiers, релизные настройки и пути существующего пакета; редизайн меняет графику, не идентичность установки |

Рекомендуемый порядок: стабилизировать общие visual tokens и state-контракт; сверить с актуальным PR; внедрить общие контролы; адаптировать экраны; завершить платформенные capabilities; затем проверить сквозные сценарии на каждой реально поставляемой платформе. Подробные зависимости — в [плане реализации](./11-implementation-plan.md).

## 10. Точки проверки и доказательств

| Область | Существующие файлы | Что подтверждать при реализации |
|---|---|---|
| Core content/order/task rules | [KashaCoreTest.kt](../../kashaCore/src/commonTest/kotlin/brain/domain/KashaCoreTest.kt), [RulesTest.kt](../../kashaCore/src/commonTest/kotlin/brain/domain/RulesTest.kt), [DomainTest.kt](../../kashaCore/src/commonTest/kotlin/brain/domain/DomainTest.kt), [MigrationTest.kt](../../kashaCore/src/commonTest/kotlin/brain/domain/MigrationTest.kt) | Append без дублей, manual/pin order, title, task lifecycle и перенос срока |
| AI boundaries / text preservation | [AiEnginesTest.kt](../../kashaCore/src/commonTest/kotlin/brain/studio/AiEnginesTest.kt), [LocalModelTextTest.kt](../../kashaCore/src/commonTest/kotlin/brain/domain/LocalModelTextTest.kt), [AiRuntimeTest.kt](../../runtime/src/test/kotlin/brain/runtime/AiRuntimeTest.kt) | Независимость ролей, consent, сохранение содержания, подключение правил всеми адаптерами |
| State / переходы / асинхронность | [StudioStateTest.kt](../../composeApp/src/commonTest/kotlin/brain/studio/StudioStateTest.kt) | Навигация при записи, возврат из picker, autosave и отсутствие потери правок |
| Локальные файлы и обработка | [StoreTest.kt](../../runtime/src/test/kotlin/brain/runtime/StoreTest.kt), [StudioProcessingTest.kt](../../runtime/src/test/kotlin/brain/runtime/StudioProcessingTest.kt), [DesktopTest.kt](../../desktopApp/src/test/kotlin/brain/desktop/DesktopTest.kt) | Финализация, восстановление, сроки и waveform реального файла |
| Сквозной Web UX | [studio_browser.py](../../tests/studio_browser.py), [sorting_browser.py](../../tests/sorting_browser.py), [browser_smoke.py](../../tests/browser_smoke.py) | Полные сценарии, сортировка, редакторы и доступность действий после визуальных изменений |
| Архитектурные ограничения | [check-ui-boundary.py](../../scripts/check-ui-boundary.py), [check-ai-boundary.py](../../scripts/check-ai-boundary.py), [check-local-only.py](../../scripts/check-local-only.py), [check-brand-boundary.py](../../scripts/check-brand-boundary.py) | Единственный Kasha UI/icon layer, три AI-роли, local-first, сохранение бренда |
| Сборка платформ | [.github/workflows/kotlin.yml](../../.github/workflows/kotlin.yml), [ios-core.yml](../../.github/workflows/ios-core.yml), [macos.yml](../../.github/workflows/macos.yml), [studio.yml](../../.github/workflows/studio.yml) | Сборка актуального SHA; успех старого workflow не переносится автоматически |

После реализации к статической карте добавляют реальные доказательства: SHA сборки, платформу и версию ОС, источник аудио, выполненные шаги и результат. Особо проверяют системные разрешения, уход в фон, клавиатуру, screen reader, 200% текста, reduce motion, recovery и повторные нажатия. Полный перечень проверяемых UX-результатов находится в [приёмке](./10-acceptance.md).
