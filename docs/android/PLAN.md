# Kasha Android — production plan

Статус: рабочий план Android-слоя. Выполнять строго по порядку; после завершения каждого пункта фиксировать результат и переходить к следующему.

## Ограничения

- `docs/SPEC.md` — основной источник истины; для UI/platform UX учитывать `docs/design/`.
- Android — тонкий shell: системные API, permissions, audio, lifecycle, notifications, secure storage, filesystem, packaging.
- Не дублировать `kashaCore` или общий Kasha UI.
- Если логика применима хотя бы к двум платформам, она относится к Core/shared UI.
- Если Android упирается в отсутствующий общий контракт, не обходить его platform-specific костылём.

## Текущий рабочий статус

**Этап: 4/11 — Microphone/recording.**

### Сделано

- Android recorder и filesystem/pending integration реализованы;
- Android adapter переведён на общий typed `RecorderSessionGateway` из Core PR #37;
- route loss, system silencing, recorder errors, safe cancel и targeted recovery реализованы без Android-only business state-machine;
- recovery восстанавливает фактические duration и waveform из M4A там, где Android decoder это позволяет;
- foreground microphone service, notification и process-scoped runtime реализованы;
- branch-head CI `:androidApp:testDebugUnitTest :androidApp:assembleDebug` проходит;
- Android PR #41 остаётся draft поверх #37 до общего reconciliation.

### Делается

- поддерживается интеграционная ветка `platform/android-recorder-contract` синхронно с актуальным head Core #37;
- уточняется и фиксируется acceptance matrix для физического Android-девайса;
- Android-specific требования #35 считаются platform-implemented, но issue не закрывается до shared/device acceptance.

### Ожидает

1. Core PR #37 → `main`.
2. Shared #14: `StudioState`/presentation должны использовать `RecorderSessionGateway.sessionState()` как source of truth.
3. Shared #25: permission intent + Android permission-host после реального system prompt.
4. Physical-device acceptance на Android.
5. Только после полного закрытия пункта 4 разрешён переход к пункту 5 Playback/audio focus.

## Последовательность

- [x] 1. Аудит Android-зоны: SPEC/design/main/Gradle/Core ports и точные gaps.
- [x] 2. Минимальный Android application shell: `androidApp`, Manifest, Activity/composition root, общий `composeApp`.
- [x] 3. Persistence/filesystem adapter на `StudioRepository` и `BrainData`.
- [ ] 4. Microphone/recording: permission, pause/resume/finalize, pending/recovery, levels, lifecycle/interruption/background. **Android adapter GREEN; shared/device acceptance pending.**
- [ ] 5. Playback/audio focus: playback, pause/resume и поддержанные общим контрактом действия, route/focus. **НЕ НАЧИНАТЬ ДО ЗАКРЫТИЯ 4.**
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

#### 4.1. Recorder и session identity — сделано

- [x] `AndroidRecorder` реализует общий `RecorderSessionGateway`, не собственную Android state-machine;
- [x] typed `RecorderPermission`, `RecorderPhase`, `RecorderIssue`, stable `activeSessionId`;
- [x] active session id = UUID app-private pending source;
- [x] real `MediaRecorder`: AAC/M4A, mono 44.1 kHz, bitrate из общей `Preferences`;
- [x] start/pause/resume/finalize;
- [x] duration исключает пользовательские и системные паузы;
- [x] process-scoped runtime не создаёт второй recorder при Activity recreation.

#### 4.2. Cancel/pending/recovery — сделано

- [x] `cancelActive(sessionId)` останавливает recorder/sampler/FGS и удаляет только active source;
- [x] cancel не создаёт Capture и не запускает STT/Text/Routing/`reprocess`;
- [x] exact `recoverPending(pendingId)`;
- [x] exact `discardPending(pendingId)`;
- [x] current capture и отдельный pending не удаляются/merge-ятся друг с другом автоматически;
- [x] recovery duration читается из фактического M4A;
- [x] recovery waveform локально декодируется через `MediaExtractor + MediaCodec`;
- [x] если OEM decoder не отдаёт поддерживаемый PCM, waveform остаётся неизвестной — синтетические пики не создаются.

#### 4.3. Live levels/waveform — сделано

- [x] уровни снимаются самим recorder независимо от Compose lifecycle;
- [x] финальная waveform покрывает всю временную шкалу, а не последние samples;
- [x] waveform редуцируется максимум до 512 точек;
- [x] unit tests покрывают full-timeline peak reduction и recovered PCM buckets.

#### 4.4. Android interruptions и route changes — platform side сделано

- [x] `MediaRecorder.OnErrorListener` → typed `INTERRUPTED(IO_FAILURE)`;
- [x] `AudioRouting.OnRoutingChangedListener` → typed `INTERRUPTED(INPUT_UNAVAILABLE)`;
- [x] API 29+ `AudioRecordingCallback.isClientSilenced` → typed `INTERRUPTED(INTERRUPTION)`;
- [x] первый фактический route-switch замораживает запись и не продолжает её автоматически;
- [x] system silencing выставляет `recoverable=false`; фактический unsilence может только разрешить явный Resume;
- [x] route/silencing callback обрабатываются сериализованно под recorder mutex, чтобы причина не зависела от гонки callbacks;
- [x] `IO_FAILURE`/`SESSION_LOST` не перетираются более поздним route callback;
- [x] если platform pause не применился, recorder hardware освобождается, source остаётся pending и состояние переводится в `SESSION_LOST` вместо ложной паузы;
- [x] никакого hidden `MediaRecorder.resume()` после system event;
- [x] route monitoring регистрируется после успешного `MediaRecorder.start()`.

Важно: Android product model не продолжает запись сразу на fallback input после первого route loss. Любое продолжение требует нового фактического состояния adapter + явного действия пользователя после shared reconciliation.

#### 4.5. Foreground/background — Android infrastructure сделано

- [x] foreground service типа `microphone`;
- [x] `START_NOT_STICKY`;
- [x] service стартует вместе с уже инициированной записью, а не сам создаёт capture;
- [x] process death не рестартует запись скрытно;
- [x] notification использует официальный Kasha monochrome asset;
- [x] immutable `PendingIntent` открывает текущую Kasha Activity.

#### 4.6. Permission boundary — частично, ждёт #25

- [x] recorder использует только public Android SDK;
- [x] recorder достоверно определяет `GRANTED` и `UNAVAILABLE`;
- [x] до фактического system prompt recorder возвращает `NOT_DETERMINED`;
- [x] system permission dialog не вызывается скрытно внутри recorder;
- [ ] shared Kasha permission explanation/intent из #25;
- [ ] Android Activity permission-host через `ActivityResultContracts.RequestPermission`;
- [ ] после результата prompt различать `DENIED`/доступность Settings без hidden `PackageManager` flags;
- [ ] возврат из Settings только reconciles status и не повторяет autoRecord.

#### 4.7. Shared reconciliation — BLOCKED BY #14

Общий слой обязан:

- [ ] читать `RecorderSessionGateway.sessionState()` как source of truth; legacy `phase()` оставить fallback;
- [ ] внешний `RECORDING -> PAUSED/INTERRUPTED` один раз замораживает monotonic timer;
- [ ] в `PAUSED/INTERRUPTED` live waveform не сдвигается и не заполняется искусственными нулями;
- [ ] `INTERRUPTED` считается незавершённой recorder-session и блокирует новую запись/playback;
- [ ] interruption end не делает auto-resume;
- [ ] Resume доступен только при `issue.recoverable=true` и только по явному действию;
- [ ] destructive UI использует exact `activeSessionId` для `cancelActive`;
- [ ] recovery/discard используют exact pending id;
- [ ] переход adapter в `IDLE` reconciles current/pending без второго autoRecord;
- [ ] `HomeAndPlayer` показывает interruption как отдельное фактическое состояние, а не маскирует его под обычную запись.

Это **Core/shared UI задача**. Реализовывать её внутри Android shell запрещено.

#### 4.8. Physical-device acceptance — ожидает shared reconciliation

После merge #37/#14/#25 проверить на реальном Android:

- [ ] первая выдача `RECORD_AUDIO`: Kasha explanation → system prompt → ровно один start;
- [ ] deny permission → idle/permission state без fake recording;
- [ ] deny + Settings → возврат только обновляет status, autoRecord не повторяется;
- [ ] запись → user pause → resume → finish: один source, duration без пауз;
- [ ] входящий звонок/конкурирующий capture → timer/waveform заморожены;
- [ ] окончание system interruption не resume запись само;
- [ ] Bluetooth/headset input исчез → interruption/route-loss без незаметного продолжения на fallback mic;
- [ ] input route восстановился/сменился → Resume только после фактического adapter recovery и явной команды;
- [ ] background/lock screen при активной записи → FGS остаётся корректным;
- [ ] foreground return не создаёт второй recorder;
- [ ] kill процесса при незавершённой записи → source обнаруживается как pending;
- [ ] recover pending → duration читается из M4A, waveform восстанавливается при поддержанном decoder;
- [ ] cancel active → source удалён, Capture/AI не создаются;
- [ ] configuration change/Activity recreation → та же process session, без второго recorder;
- [ ] notification tap → возврат в текущую Kasha Activity.

#### 4.9. Автоматическая проверка — GREEN

- [x] `:androidApp:testDebugUnitTest`;
- [x] `:androidApp:assembleDebug`;
- [x] shared fonts preparation;
- [x] Kasha Icons generation;
- [x] Android recorder/recovery unit tests.

#28 со стороны Android больше не требует синтетической waveform: фактическое декодирование реализовано; общий issue остаётся межплатформенным.

Android-specific обход shared gaps запрещён. Пункт 5 не начинать до закрытия общего пункта 4.

## Критерий завершения этапа 4

Этап 4 можно отметить `[x]` только когда одновременно выполнены все условия:

1. #37 merged в `main`;
2. #14 shared reconciliation merged и common tests зелёные;
3. #25 permission intent + Android permission-host подключены;
4. Android PR #41 retargeted на `main` и CI зелёный;
5. physical-device acceptance из §4.8 пройдена без blocker-дефектов.

Текущий этап: **4. Microphone/recording — Android adapter GREEN; shared/device acceptance PENDING (#37/#14/#25)**.
