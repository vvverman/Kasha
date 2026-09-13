# Capture/recovery contract handoff

Источник истины: `kashaCore` — `RecorderContract.kt` и `CaptureRecovery.kt`.

Platform recorder, который поддерживает безопасную отмену и восстановление, реализует `RecorderSessionGateway`.

Обязательные гарантии:

- `activeSessionId` и `PendingRecording.id` — opaque идентификаторы, не file path и не системный handle;
- `RecorderPhase.IDLE` допустим только при `activeSessionId == null`; любая не-`IDLE` фаза обязана иметь непустой `activeSessionId`;
- `cancelActive(sessionId)` отменяет и удаляет только указанную активную сессию, не создаёт `Capture` и не запускает STT/AI;
- `recoverPending(pendingId)` принимает только указанный pending;
- `discardPending(pendingId)` удаляет только указанный pending;
- `sessionState()` возвращает фактическую фазу после pause/resume/stop/interruption;
- permission и ошибка отображаются через `RecorderPermission` / `RecorderIssue`, без platform-specific типов в Core;
- несколько pending не выбираются автоматически;
- `current + pending` сохраняются оба и восстанавливаются последовательно;
- новая запись недоступна при current, active recorder или любом pending.

`CaptureRecoveryCoordinator` повторно сверяет identity перед destructive/recovery действием. Он не подменяет platform storage и не знает путей к файлам.

## Текущее состояние интеграции

- Shared `StudioState` уже потребляет typed recorder lifecycle: interruption/pause/idle reconciliation, freeze timer/waveform, explicit resume и exact pending identity.
- iOS уже реализует `RecorderSessionGateway`, системные interruptions, route changes и exact pending `.m4a`.
- Desktop, Web и Android должны реализовать тот же capability по мере перехода с legacy `RecorderGateway`; до этого shared UI не должен имитировать безопасную отмену или удаление конкретного pending.

Платформенные реализации обязаны сохранить существующее локальное хранение и не переносить бизнес-правила из Core.
