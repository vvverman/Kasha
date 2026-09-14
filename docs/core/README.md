# Kasha Core handoffs

Документы в этой папке фиксируют платформонезависимые контракты Core, которые требуют реализации или потребления в platform/shared слоях.

- [Capture/recovery contract](capture-recovery-contract.md) — identity-aware recorder, безопасная отмена активной записи, точное recovery/discard pending и типизированные permission/interruption/error состояния.
- [Playback/recording contract](playback-recording-contract.md) — typed playback phase, seek с сохранением play/pause и взаимоисключение recorder/playback.
- [Task lifecycle contract](task-lifecycle-contract.md) — save-before-complete, идемпотентный архив, сроки/повторы и системный reminder boundary.
- [AI workflow contract](ai-workflow-contract.md) — три независимые AI-роли, единые Core-проверки local/cloud и неразрушающий routing fallback.
