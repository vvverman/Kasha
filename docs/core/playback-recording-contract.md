# Playback/recording contract handoff

Источник истины: `kashaCore` — `PlaybackContract.kt`, `RecorderContract.kt` и platform-agnostic `AudioGateway`/`RecorderGateway`.

## Обязательные правила

- фактический playback state передаётся через `PlaybackSessionState` и `PlaybackPhase`;
- активные `LOADING`, `PLAYING` и `PAUSED` всегда имеют `sourceId`;
- неизвестная legacy-фаза не считается `IDLE` и не должна разрешать старт записи;
- `PAUSED` остаётся занятым transport: пауза playback не разрешает запуск recorder;
- активный recorder блокирует старт playback;
- одновременные активные recorder и playback считаются конфликтным состоянием;
- seek доступен только для текущего стабильного source с известной положительной duration;
- seek clamp-ится в `[0, duration]`;
- seek внутри файла сохраняет фазу: `PLAYING` остаётся `PLAYING`, `PAUSED` остаётся `PAUSED`;
- seek не меняет `sourceId`;
- stop переводит platform playback в `IDLE`; выбранный source на уровне продукта может оставаться загруженным для повторного Play;
- natural end обязан вернуться в `IDLE`, чтобы UI не оставался в состоянии Pause;
- смена source выполняется platform adapter без одновременного воспроизведения двух источников.

`PlaybackSessionGateway` — backward-compatible capability поверх legacy `AudioGateway`. Shared UI может использовать seek только при наличии этого capability; имитировать seek повторным `playCapture()` нельзя, потому что это ломает paused-state.

## Границы платформ

Platform layer отвечает только за реальную аудиосессию, device routing, декодирование/HTMLAudio/AVAudioPlayer/desktop output и подтверждение фактической фазы. Правила взаимоисключения и допустимости transport-действий не дублируются по платформам.

Текущие iOS, Web и Desktop adapters реализуют `PlaybackSessionGateway`. Android при появлении собственного playback adapter должен реализовать тот же контракт.

UI подтверждения вроде «Остановить аудио и начать запись?» остаются задачей shared Kasha UI; Core определяет только допустимость перехода.
