# Kasha Android — production plan

Статус: рабочий план Android-слоя. Выполнять строго по порядку; после завершения каждого пункта фиксировать результат и переходить к следующему.

## Ограничения

- `docs/SPEC.md` — основной источник истины; для UI/platform UX учитывать `docs/design/`.
- Android — тонкий shell: системные API, permissions, audio, lifecycle, notifications, secure storage, filesystem, packaging.
- Не дублировать `kashaCore` или общий Kasha UI.
- Если логика применима хотя бы к двум платформам, она относится к Core/shared UI.
- Если Android упирается в отсутствующий общий контракт, не обходить его platform-specific костылём.

## Последовательность

- [x] 1. Аудит Android-зоны: SPEC/design/main/Gradle/Core ports и точные gaps.
- [x] 2. Минимальный Android application shell: `androidApp`, Manifest, Activity/composition root, общий `composeApp`.
- [x] 3. Persistence/filesystem adapter на `StudioRepository` и `BrainData`.
- [ ] 4. Microphone/recording: permission, pause/resume/finalize, pending/recovery, levels, lifecycle/interruption/background.
- [ ] 5. Playback/audio focus: playback, pause/resume и поддержанные общим контрактом действия, route/focus.
- [ ] 6. Reminders/notifications: permission, schedule/cancel/resync, restart/reboot semantics.
- [ ] 7. Secure storage: Android Keystore для внешних AI API-ключей.
- [ ] 8. Lifecycle/system integration: foreground/background, Settings return, Back, configuration changes, insets.
- [ ] 9. Accessibility/platform UX: TalkBack, font/display scaling, navigation modes, keyboard/orientation.
- [ ] 10. CI/build/package: Android build pipeline, APK/AAB, debug/release checks.
- [ ] 11. Финальная Android-приёмка по SPEC/design и список общих Core/UI gaps.

## Зафиксированные результаты

### 3. Persistence/filesystem — завершён

- state/preferences лежат в app-private storage;
- JSON заменяется через temp + `fsync` + atomic move там, где файловая система это поддерживает;
- pending и finalized audio используют один UUID capture и восстанавливаются после падения между move и commit state;
- незавершённое staged-delete восстанавливается/завершается по сохранённому Core state;
- рабочий capture после убийства процесса переводится в recoverable failure без потери аудио;
- системный Android backup/cloud backup/device transfer для данных Kasha отключён;
- unit tests покрывают restart persistence и crash-recovery;
- branch-head CI: `:androidApp:testDebugUnitTest` и `:androidApp:assembleDebug` проходят.

### 4. Microphone/recording — Android adapter готов, общий пункт ещё не закрыт

Интеграционная ветка: `platform/android-recorder-contract`, draft PR #41 поверх Core PR #37.

Готово и зелёное в Android shell:

- `AndroidRecorder` реализует общий `RecorderSessionGateway`, не собственную Android state-machine;
- typed `RecorderPermission`, `RecorderPhase`, `RecorderIssue`, stable `activeSessionId`;
- real `MediaRecorder`: AAC/M4A, mono 44.1 kHz, bitrate из общей `Preferences`;
- start/pause/resume/finalize, duration без пауз, app-private active pending;
- safe `cancelActive(sessionId)`: только точный active source, без Capture/STT/AI/`reprocess`;
- exact `recoverPending(pendingId)` и `discardPending(pendingId)`;
- реальные уровни и full-timeline waveform, сведение максимум до 512 точек;
- recovery duration читается из фактического M4A;
- recovery waveform локально декодируется через `MediaExtractor + MediaCodec`; при неподдержанном OEM PCM waveform остаётся неизвестной, синтетические данные не подставляются;
- process-scoped runtime не создаёт второй recorder при Activity recreation;
- foreground service типа `microphone`, `START_NOT_STICKY`, без скрытого restart capture после process death;
- foreground notification использует официальный Kasha asset и immutable `PendingIntent` обратно в приложение;
- `MediaRecorder.OnErrorListener` → typed `INTERRUPTED(IO_FAILURE)`;
- `AudioRouting.OnRoutingChangedListener` → typed `INTERRUPTED(INPUT_UNAVAILABLE)`;
- API 29+ `AudioRecordingCallback.isClientSilenced` → typed `INTERRUPTED(INTERRUPTION)`;
- route/system interruption замораживают recording state, не делают hidden resume;
- route monitoring регистрируется только после успешного `MediaRecorder.start()`, recording callback — до старта capture;
- recorder использует только public Android SDK, hidden permission flags удалены;
- branch-head `:androidApp:testDebugUnitTest :androidApp:assembleDebug` — success;
- unit tests покрывают waveform reduction и PCM recovery buckets.

Permission boundary:

- recorder достоверно определяет `GRANTED` и `UNAVAILABLE`, а до фактического system prompt возвращает `NOT_DETERMINED`;
- `DENIED/RESTRICTED` должен фиксировать Android Activity permission-host после реального `ActivityResultContracts.RequestPermission`, потому что обычному приложению недоступны внутренние permission flags;
- system dialog нельзя запускать внутри recorder: сначала shared Kasha-explanation из #25.

Оставшиеся условия полного закрытия пункта 4:

- Core PR #37 (`RecorderSessionGateway`) должен попасть в `main`;
- #14: shared `StudioState`/presentation должны reconciles фактический `sessionState()` для external `PAUSED/INTERRUPTED`, замораживать timer/waveform, использовать exact ids и запрещать auto-resume;
- Android permission-host подключается к shared permission intent из #25;
- после shared reconciliation — physical-device acceptance: звонок/конкурирующий capture, Bluetooth/headset route loss/return, background/foreground, Settings return, kill/recovery.

#28 со стороны Android больше не требует синтетической waveform: фактическое декодирование реализовано; общий issue остаётся межплатформенным.

Android-specific обход shared gaps запрещён. Пункт 5 не начинать до закрытия общего пункта 4.

Текущий этап: **4. Microphone/recording — Android adapter GREEN, BLOCKED BY shared Core/UI #37/#14/#25**.
