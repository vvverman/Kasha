# Task lifecycle contract handoff

Источник истины: `kashaCore` — `BrainData`, `TaskSchedule`, task-модели и `ReminderGateway`; shared application flow — `StudioState` + `TaskLifecycleActions.kt`.

## Обязательные правила

- task из capture создаётся идемпотентно: повторная отправка одного capture возвращает уже созданную задачу;
- текст активной задачи непустой; completed task read-only и не может редактироваться или переноситься по сроку;
- Complete идемпотентен: первый вызов фиксирует `completedAt`, обнуляет `nextReminderAt` и переносит задачу в архив; повторный вызов не меняет timestamps/state;
- при Complete из detail-screen актуальная рабочая копия текста сохраняется первой; если save не подтверждён, `completeTask` не вызывается;
- срок новой/перенесённой задачи обязан быть в будущем;
- `nextReminderAt` при создании/переносе равен выбранному сроку;
- due reminder забирается атомарно вместе с переносом `nextReminderAt`, поэтому тот же момент нельзя выдать повторно;
- completed task никогда не участвует в reminder claim;
- интервалы reminder определяются только `ReminderRepeat`/`TaskSchedule` в Core;
- ручной порядок изменяется только для активных задач; архив может его показывать, но не переставлять.

## Системные reminders

`ReminderGateway` — единственная platform boundary уведомлений.

- `notify(task)` — foreground/fallback доставка уже наступившего reminder;
- `sync(tasks)` — reconciliation системной очереди для платформ, умеющих заранее планировать local notifications;
- completed/deleted/unscheduled task не должны оставаться в pending system notifications;
- platform notification failure не переносит бизнес-правила задач из Core.

Текущее состояние:

- iOS: `IosReminderRepository` синхронизирует `UNUserNotificationCenter` после create/edit/reschedule/complete/delete и reminder claim; `IosReminder` ставит только active task с `nextReminderAt > 0`;
- Desktop: foreground reminder через `StudioState.poll()`/`DesktopReminder`; системное планирование при закрытом приложении пока не заявляется этим adapter;
- Web/Android: при появлении system reminder adapter используют тот же `ReminderGateway`, без копирования календарных правил.

Persistent schema и migration для этого контракта не меняются.
