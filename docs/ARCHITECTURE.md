# Kasha — архитектура

Главный принцип: **одно бизнес-ядро, один общий UI, общие AI-слои и шесть максимально тонких delivery shells**.

```text
                         modules/core
             models · domain · rules · ports
                                │
              ┌─────────────────┼──────────────────┐
              │                 │                  │
      modules/ai/catalog  modules/ai/connectors  modules/ui
        AI metadata        provider protocols     Kasha UI
              │                 │                  │
              └─────────────────┼──────────────────┘
                                │
                  reusable infrastructure
                                │
                         platforms/*
                                │
       ┌──────────┬──────────┬──┴───────┬────────┬────────┬───────┐
     macOS      Windows    Linux      Android    iOS      Web
```

Подробная физическая карта: [`REPOSITORY_STRUCTURE.md`](REPOSITORY_STRUCTURE.md).

## Неподвижные правила

1. В продукте ровно **одно бизнес-ядро**: `modules/core` (Gradle ID `:kashaCore`).
2. Core не зависит от UI, ОС, конкретных AI-моделей/providers, сетевых endpoint, secure storage или packaging.
3. AI имеет три независимые роли: `SPEECH_TO_TEXT`, `TEXT`, `ROUTING`. Это контракты одного Core, а не отдельные ядра.
4. Конкретные модели/providers описывает `modules/ai/catalog`; provider-specific HTTP protocol находится в `modules/ai/connectors`.
5. Продуктовый интерфейс реализуется один раз в `modules/ui`. В нём физически нет platform source sets.
6. Поддерживаются ровно шесть delivery targets: **macOS, Windows, Linux, Android, iOS, Web**.
7. Если код одинаков хотя бы на двух платформах, он поднимается в `modules/`, а не копируется по shells.
8. Platform shell содержит только lifecycle, permissions, platform storage, microphone/playback, notifications, secure secrets, native bindings и packaging.
9. Local-first — базовый режим. Внешний AI включается только явно после consent; API-key не сохраняется в Core/UI Preferences.

## Слои

### `modules/core`

Единственный источник бизнес-правил:

- Project / Note / Task / Capture models;
- сортировки и persistent manual order;
- append/distribution rules;
- task lifecycle/reminder scheduling rules;
- AI role contracts и privacy invariants;
- platform-neutral state/contracts.

### `modules/ai/catalog`

Только каталог возможностей:

- concrete local engine metadata;
- supported roles/languages/sizes;
- external provider descriptors;
- validation выбранного engine ID.

Он не выполняет HTTP-запросы и не хранит секреты.

### `modules/ai/connectors`

Единственное место provider-specific протоколов:

- OpenAI;
- Anthropic;
- Gemini;
- OpenRouter;
- OpenAI-compatible/custom endpoints;
- request/response schemas;
- multipart external STT;
- обязательная consent validation.

Модуль не знает Keychain/Keystore/DPAPI и получает API-key только как transient parameter.

### `modules/ui`

Единый Compose Multiplatform продуктовый UI:

- Kasha UI components;
- Kasha Icons;
- экраны;
- `StudioState`;
- общая UX-логика представления.

Запрещены `iosMain`, `androidMain`, `jvmMain`, `wasmJsMain`: platform-specific UI wiring находится только в shells.

### `modules/infrastructure/jvm`

Переиспользуемая JVM-инфраструктура:

- disk persistence;
- local process execution;
- local AI runtime;
- Java HTTP transport для `aiConnectors`;
- localhost runtime для Web.

Она не содержит продуктовых экранов и provider protocol constants.

## Шесть оболочек

| Delivery | Physical location | Platform-specific responsibility |
| --- | --- | --- |
| macOS | `platforms/desktop` | paths, Keychain, notification adapter, Mach-O engines, DMG |
| Windows | `platforms/desktop` | paths, DPAPI, notifications, `.exe` engines, MSI/EXE |
| Linux | `platforms/desktop` | XDG paths, Secret Service, notifications, ELF engines, DEB/RPM |
| Android | `platforms/android` | Activity/lifecycle, permissions, AudioRecord/AudioTrack, notifications, Android storage, APK |
| iOS | `platforms/ios/shared` + `platforms/ios/app` | AVAudio, Apple Speech, notifications, files, Kotlin framework + minimal Xcode wrapper, IPA |
| Web | `platforms/web` | browser entry point, browser media/runtime adapters, production Wasm |

macOS/Windows/Linux намеренно используют один общий desktop implementation. Три копии одинакового desktop-кода были бы нарушением цели максимального reuse. Отличия изолируются внутри малых OS adapters и `packaging/`.

## Направление зависимостей

```text
platforms/*
    │
    ├────► modules/ui ───────────────► modules/core
    │          │
    │          └────► modules/ai/catalog ──► modules/core
    │
    ├────► modules/ai/connectors ────► modules/core
    │
    └────► modules/infrastructure/* ─► modules/ai/* + modules/core
```

Обратная зависимость запрещена. `modules/core` никогда не импортирует внешний слой.

## Definition of Done

- Core собирается независимо.
- UI физически platform-neutral.
- AI catalog и provider protocol вынесены из Core.
- Все шесть shells присутствуют физически под `platforms/`.
- macOS → DMG, Windows → MSI/EXE, Linux → DEB/RPM, Android → APK, iOS → IPA, Web → production Wasm.
- API keys используют системное secure storage там, где подключается внешний AI.
- CI проверяет архитектуру через `check-platform-shells.py`, `check-ai-boundary.py`, `check-local-only.py`, `check-ui-boundary.py`.
- старые неоднозначные top-level modules (`kashaCore`, `composeApp`, `runtime`, `desktopApp`, `androidApp`, `iosApp`, `aiCatalog`) не возвращаются.
