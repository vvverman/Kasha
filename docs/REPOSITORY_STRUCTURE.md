# Kasha — карта репозитория

Это физическая карта проекта и правило размещения нового кода.

```text
Kasha/
├── modules/                         # только reusable code
│   ├── core/                        # единственное бизнес-ядро
│   ├── ai/
│   │   ├── catalog/                 # список/metadata AI engines и providers
│   │   └── connectors/              # общий protocol external AI APIs
│   ├── ui/                          # общий Compose Kasha UI + StudioState
│   └── infrastructure/
│       └── jvm/                     # JVM storage/process/http infrastructure
│
├── platforms/                       # только thin shells
│   ├── android/                     # Android app → APK
│   ├── desktop/                     # shared JVM desktop implementation
│   │   └── packaging/               # macOS/Windows/Linux packaging + native bins
│   ├── ios/
│   │   ├── shared/                  # Kotlin/Native platform adapters/framework
│   │   └── app/                     # minimal Swift/Xcode wrapper → IPA
│   └── web/                         # browser adapters + Wasm entry point
│
├── docs/                            # canonical product/design/architecture docs
├── scripts/                         # build, validation and developer tooling
├── tests/                           # end-to-end/integration fixtures and tests
├── gradle/                          # Gradle version catalog/wrapper data
├── .github/workflows/               # CI for all delivery targets
├── settings.gradle.kts              # explicit physical module map
└── build.gradle.kts                 # root plugin declarations only
```

## Как решить, куда класть новый код

### 1. Это бизнес-правило?

Примеры: сортировка, lifecycle задачи, append заметки, правила capture, AI privacy validation.

→ `modules/core`.

### 2. Это список доступных AI или metadata модели/provider?

→ `modules/ai/catalog`.

### 3. Это формат запроса/ответа внешнего AI API?

Примеры: OpenAI Responses, Anthropic Messages, Gemini generateContent, OpenRouter chat completions, multipart STT.

→ `modules/ai/connectors`.

### 4. Это продуктовый экран, состояние UI или Kasha component?

→ `modules/ui`.

`modules/ui` не имеет `iosMain`, `androidMain`, `jvmMain` или `wasmJsMain`: он физически platform-neutral.

### 5. Это reusable техническая реализация, не принадлежащая ОС?

Примеры: JVM disk repository, process runner, localhost runtime, Java HTTP transport.

→ `modules/infrastructure/<runtime>`.

### 6. Это зависит от конкретной платформы?

Примеры: permission, microphone, AVAudio, Android AudioRecord, notification API, Keychain, DPAPI, app directory, Xcode wrapper.

→ `platforms/<platform>`.

## Dependency rule

Единственно допустимое направление:

```text
platforms/*
    │
    ├──────────────► modules/ui ──────────────► modules/core
    │                    │
    │                    └──► modules/ai/catalog ──► modules/core
    │
    ├──────────────► modules/ai/connectors ───► modules/core
    └──────────────► modules/infrastructure/* ─► modules/*
```

Нижний слой никогда не импортирует верхний.

## Шесть оболочек

| Delivery | Physical shell | Общий код |
| --- | --- | --- |
| macOS | `platforms/desktop` + macOS packaging | Core + AI + UI + JVM infra |
| Windows | `platforms/desktop` + Windows packaging | Core + AI + UI + JVM infra |
| Linux | `platforms/desktop` + Linux packaging | Core + AI + UI + JVM infra |
| Android | `platforms/android` | Core + AI + UI |
| iOS | `platforms/ios/shared` + `platforms/ios/app` | Core + AI + UI |
| Web | `platforms/web` | Core + AI + UI + localhost bridge where needed |

Разделение macOS/Windows/Linux на три копии Kotlin-кода запрещено без технической необходимости. Различия ОС изолируются в небольших adapters/resources внутри общего desktop shell.

## Architectural invariants

- `modules/core` — ровно один.
- Provider URL запрещены вне `modules/ai/connectors`.
- API keys запрещены в persisted Core/UI state.
- `modules/ui` не содержит platform source sets.
- Product screens не дублируются в `platforms`.
- Каждый из шести delivery targets имеет собственный CI/build artifact.
- Старые корневые каталоги `kashaCore`, `composeApp`, `runtime`, `desktopApp`, `androidApp`, `iosApp`, `aiCatalog` не возвращаются.

Эти правила проверяются `scripts/check-platform-shells.py`, `check-ai-boundary.py`, `check-local-only.py` и `check-ui-boundary.py`.
