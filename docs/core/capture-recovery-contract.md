# Capture/recovery contract handoff

Источник истины: `kashaCore` — `RecorderContract.kt` и `CaptureRecovery.kt`.

Platform recorder, который поддерживает безопасную отмену и восстановление, реализует `RecorderSessionGateway`.

Обязательные гарантии:

- `activeSessionId` и `PendingRecording.id` — opaque идентификаторы, не file path и не системный handle;
- `cancelActive(sessionId)` отменяет и удаляет только указанную активную сессию, не создаёт `Capture` и не запускает STT/AI;
- `recoverPending(pendingId)` принимает только указанный pending;
- `discardPending(pendingId)` удаляет только указанный pending;
- `sessionState()` возвращает фактическую фазу после pause/resume/stop/interruption;
- permission и ошибка отображаются через `RecorderPermission` / `RecorderIssue`, без platform-specific типов в Core;
- несколько pending не выбираются автоматически;
- `current + pending` сохраняются оба и восстанавливаются последовательно;
- новая запись недоступна при current, active recorder или любом pending.

Платформенные реализации должны сохранить существующее локальное хранение и не переносить бизнес-правила из Core.

Целевые владельцы:

- iOS: AVAudioSession/AVAudioRecorder, interruptions, route changes, pending `.m4a`;
- Desktop: TargetDataLine/WavJournal, device errors, pending `.wav`;
- Web: MediaRecorder/IndexedDB, permission/device errors, persisted sessions/chunks;
- Android: будущий/текущий recorder adapter с теми же общими гарантиями.

Shared UI/StudioState должны использовать identity-aware destructive/recovery actions только когда recorder реализует `RecorderSessionGateway`. Для legacy `RecorderGateway` нельзя имитировать безопасную отмену или удаление конкретного pending.
