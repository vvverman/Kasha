# Kasha — local-first ИИ для голосовых заметок и задач

**Записать → расшифровать → привести текст в порядок → отправить в заметки или задачи.**

Главный принцип Kasha: приложение полноценно работает локально, а пользовательские данные принадлежат устройству. Аккаунт, удалённый backend и облачная БД для основной функциональности не нужны. Внешний AI подключается только явно и только после подтверждения передачи данных.

## Архитектура

```text
                              modules/core
                   models · domain · rules · ports
                                   │
             ┌─────────────────────┼────────────────────┐
             │                     │                    │
 modules/ai/catalog      modules/ai/connectors      modules/ui
   AI metadata             provider protocol         Kasha UI
             │                     │                    │
             └─────────────────────┼────────────────────┘
                                   │
                             platforms/*
                                   │
      ┌──────────┬──────────┬──────┴───┬────────┬────────┬───────┐
    macOS      Windows    Linux      Android    iOS      Web
```

В Kasha ровно **одно бизнес-ядро**. Оно не знает Compose, ОС, конкретные AI-модели, provider URL, Keychain/Keystore/DPAPI или packaging.

AI разделён на три независимые роли внутри одного Core: **Speech-to-Text / Text processing / Routing**. Конкретные модели и providers находятся вне Core: metadata — в `modules/ai/catalog`, общий сетевой protocol — в `modules/ai/connectors`.

Продуктовый интерфейс реализован один раз в `modules/ui`. Этот модуль физически platform-neutral: iOS/Web/Android/Desktop wiring находится только под `platforms/`.

Правило проекта: **если код одинаково работает хотя бы на двух платформах, он должен быть поднят в reusable layer, а не скопирован в shell.**

Подробности: [архитектура](docs/ARCHITECTURE.md) · [карта репозитория](docs/REPOSITORY_STRUCTURE.md).

## Структура репозитория

```text
modules/
├── core/                  # единственное бизнес-ядро
├── ai/
│   ├── catalog/           # модели/providers metadata
│   └── connectors/        # OpenAI/Claude/Gemini/OpenRouter/custom protocol
├── ui/                    # общий Kasha UI + StudioState
└── infrastructure/
    └── jvm/               # storage/process/http/localhost infrastructure

platforms/
├── android/               # APK shell
├── desktop/               # общий JVM shell для macOS/Windows/Linux
├── ios/
│   ├── shared/            # Kotlin/Native adapters + framework
│   └── app/               # минимальная Xcode/Swift wrapper
└── web/                   # browser adapters + Wasm entry point
```

macOS, Windows и Linux используют один desktop implementation ради максимального reuse, но имеют независимые системные adapters/resources и отдельные установочные форматы: **DMG / MSI+EXE / DEB+RPM**.

## Интерфейс

Один общий интерфейс на Compose Multiplatform:

- разделы **Главная / Проекты / Задачи / Настройки**;
- после голосового ввода две отдельные команды: **В заметки** и **В задачи**;
- заметка — один текстовый документ без отдельного поля названия; первая непустая строка является названием;
- для заметки после отправки выбирается проект и новая/существующая заметка;
- для задачи задаются срок и повтор напоминаний: 10/30 минут, час, день, неделя, будни или выходные;
- выполненные задачи уходят в Архив;
- проекты, заметки и активные задачи сортируются по алфавиту, дате создания, дате изменения или вручную;
- manual order хранится отдельно и не теряется при переключении сортировки;
- ручное изменение порядка — long-press + drag;
- `Kasha UI` — единственный слой продуктовых контролов;
- `Kasha Icons` — единый собственный набор иконок; параллельные icon packs запрещены;
- светлая тема почти белая с тёплым смещением, тёмная почти чёрная с коричневым смещением.

Восемь языков интерфейса: русский, английский, испанский, французский, немецкий, украинский, белорусский, казахский.

## AI

Три роли выбираются независимо:

1. **Speech-to-Text** — аудио → текст;
2. **Text processing** — заголовок и приведение текста в порядок;
3. **Routing** — релевантность заметки проектам.

Локальный режим остаётся базовым. OpenAI, Anthropic Claude, Gemini, OpenRouter, OpenAI-compatible и custom endpoints подключаются через общий `modules/ai/connectors` protocol.

Platform layer предоставляет только транспорт и secure storage. API keys не попадают в Preferences/Core state. Text processing независимо от движка проходит Core-проверки сохранения чисел, имён, отрицаний и смысла; external routing выполняется batch-запросом.

## Шесть delivery targets

| Платформа | Shell | Artifact |
| --- | --- | --- |
| macOS | `platforms/desktop` | DMG |
| Windows | `platforms/desktop` | MSI / EXE |
| Linux | `platforms/desktop` | DEB / RPM |
| Android | `platforms/android` | APK |
| iOS | `platforms/ios/shared` + `platforms/ios/app` | IPA / SideStore |
| Web | `platforms/web` | production Wasm |

## Приватность и архитектурные границы

Запрещены обязательный remote backend, cloud DB/sync и telemetry/analytics SDK с пользовательскими данными. Web UI может общаться с локальным процессом через `127.0.0.1`.

CI автоматически проверяет:

- `check-platform-shells.py` — физическую схему `modules/` + шесть shells и направление зависимостей;
- `check-ai-boundary.py` — один Core, три AI-роли, catalog/connectors вне Core, consent и отсутствие persisted secrets;
- `check-local-only.py` — отсутствие скрытого cloud sync/backend/telemetry;
- `check-ui-boundary.py` — единый Kasha UI и Kasha Icons;
- `check-brand-boundary.py` — отсутствие legacy-бренда.

## Сборка

CI отдельно проверяет Core/AI/UI, Android APK, iOS framework/IPA, production WebAssembly и desktop installers для macOS/Windows/Linux.

Для локального Web-запуска: `bash scripts/run-web.sh`. Для первого запуска на macOS: `scripts/launch-macos.command`.

Каноническое ТЗ продукта: [docs/SPEC.md](docs/SPEC.md).

## Дизайн для разработки

[Полное UX/UI-ТЗ Kasha](docs/design/README.md) объединяет текущий код, визуальный канон и требования продукта. Оно включает экраны и состояния, компоненты, типографику, токены тем, графику/motion, доступность, приватность, тексты, приёмку и план внедрения.

[Каталог Kasha Icons](docs/design/icons/README.md) описывает собственную систему иконок. Графический фон с макросъёмкой гречки используется только на splash screen.
