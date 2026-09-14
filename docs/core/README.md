# Kasha Core handoffs

Документы в этой папке фиксируют платформонезависимые контракты Core, которые требуют реализации или потребления в platform/shared слоях.

- [Capture/recovery contract](capture-recovery-contract.md) — identity-aware recorder, безопасная отмена активной записи, точное recovery/discard pending и типизированные permission/interruption/error состояния.
- [Playback/recording contract](playback-recording-contract.md) — typed playback phase, seek с сохранением play/pause и взаимоисключение recorder/playback.
