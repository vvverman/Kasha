# `modules/` — переиспользуемые слои Kasha

Здесь находится только код, который не является оболочкой конкретной ОС.

```text
modules/
├── core/                 # единственное бизнес-ядро
├── ai/
│   ├── catalog/          # доступные AI engines/providers и metadata
│   └── connectors/       # общий протокол внешних AI API
├── ui/                   # общий Kasha UI + StudioState
└── infrastructure/
    └── jvm/              # общая JVM-инфраструктура: storage/process/http runtime
```

## Правило зависимостей

Зависимости направлены внутрь:

```text
infrastructure ──► ai/connectors ──► core
       │               │
       ├──────────────► ai/catalog ──► core
       └──────────────► core

ui ──► ai/catalog ──► core
 └──────────────────► core
```

`core` ни от кого из остальных модулей не зависит.

В `modules/` запрещены lifecycle/permissions конкретной ОС, системные уведомления, platform secure storage, platform microphone/playback и упаковка приложений. Всё это относится к `platforms/`.
