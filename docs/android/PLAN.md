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

- Core PR #37 с typed `RecorderSessionGateway` merged в `main`;
- основная shared recorder reconciliation уже в `main`: typed session source of truth, freeze timer/waveform, no auto-resume, interrupted session блокирует playback/new recording, exact recovery id, IDLE reconciliation без второго autoRecord;
- Android recorder и filesystem/pending integration реализованы;
- route loss, system silencing, recorder errors, safe cancel и targeted recovery реализованы без Android-only business state-machine;
- recovery восстанавливает фактические duration и waveform из M4A там, где Android decoder это позволяет;
- foreground microphone service, notification и process-scoped runtime реализованы;
- FGS стартует один раз из user-visible capture flow; дальнейшие system/user pause/resume updates только обновляют существующую notification и не делают background `startService()`/`startForegroundService()`;
- Android code-bearing head `0d817abe6c36bb4bcc555550710b1110334d54cf` прошёл `:androidApp:testDebugUnitTest` + `:androidApp:assembleDebug`;
- старый pre-main draft PR #41 заменён чистым main-based draft PR #50;
- PR #50 не переносит stale shared `composeApp`/Core файлы: только Android-owned код, Android CI/docs и минимальное Gradle-подключение модуля;
- физическая acceptance matrix A4-01…A4-18 зафиксирована в `docs/android/DEVICE_ACCEPTANCE.md`.

### Делается

- draft PR #50 (`platform/android-main-integration` → `main`) проходит Android/KMP/iOS Shared CI уже вместе с актуальным shared recorder reconciliation;
- issue #14 уточнён до двух реально оставшихся shared gaps: interruption presentation и active-recording Cancel через exact session id;
- Android-specific требования #35 считаются platform-implemented, но issue не закрывается до shared/device acceptance.

### Ожидает

1. Shared #14 — отдельное interruption presentation-state в Kasha UI.
2. Shared #14 — канонический active-recording Cancel через `cancelActive(activeSessionId)` + confirmation/common tests.
3. Shared #25 — permission intent + Android permission-host после реального system prompt.
4. Physical-device acceptance A4-01…A4-18.
5. Только после полного закрытия пункта 4 разрешён переход к пункту 5 Playback/audio focus.

## Последовательность

- [x] 1. Аудит Android-зоны: SPEC/design/main/Gradle/Core ports и точные gaps.
- [x] 2. Минимальный Android application shell: `androidApp`, Manifest, Activity/composition root, общий `composeApp`.
- [x] 3. Persistence/filesystem adapter на `StudioRepository` и `BrainData`.
- [ ] 4. Microphone/recording: permission, pause/resume/finalize, pending/recovery, levels, lifecycle/interruption/background. **Android platform layer готов; shared presentation/cancel, permission-host и device acceptance pending.**
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
- unit tests покрывают restart persistence и crash-recovery.

### 4. Microphone/recording — Android platform layer готов, общий пункт ещё не закрыт

Основная интеграция: `platform/android-main-integration`, draft PR #50 → `main`.

Старый draft PR #41 был нужен до merge Core #37 и больше не является основной интеграционной веткой.

#### 4.1. Recorder и session identity — сделано

- [x] `AndroidRecorder` реализует общий `RecorderSessionGateway`, не собственную Android business state-machine;
- [x] typed `RecorderPermission`, `RecorderPhase`, `RecorderIssue`, stable `activeSessionId`;
- [x] active session id = UUID app-private pending source;
- [x] real `MediaRecorder`: AAC/M4A, mono 44.1 kHz, bitrate из общей `Preferences`;
- [x] start/pause/resume/finalize;
- [x] duration исключает пользовательские и системные паузы;
- [x] process-scoped runtime не создаёт второй recorder при Activity recreation.

#### 4.2. Cancel/pending/recovery — Android adapter сделано

- [x] `cancelActive(sessionId)` останавливает recorder/sampler/FGS и удаляет только active source;
- [x] cancel не создаёт Capture и не запускает STT/Text/Routing/`reprocess`;
- [x] exact `recoverPending(pendingId)`;
- [x] exact `discardPending(pendingId)`;
- [x] current capture и отдельный pending не удаляются/merge-ятся друг с другом автоматически;
- [x] recovery duration читается из фактического M4A;
- [x] recovery waveform локально декодируется через `MediaExtractor + MediaCodec`;
- [x] если OEM decoder не отдаёт поддерживаемый PCM, waveform остаётся неизвестной — синтетические пики не создаются;
- [ ] shared product flow активной кнопки «Отменить запись» должен вызвать именно `cancelActive(activeSessionId)` — это оставшийся #14, не Android workaround.

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
- [x] route/silencing callbacks сериализованы под recorder mutex, чтобы итог не зависел от гонки callbacks;
- [x] `IO_FAILURE`/`SESSION_LOST` не перетираются более поздним route callback;
- [x] если platform pause не применился, recorder hardware освобождается, source остаётся pending и состояние переводится в `SESSION_LOST` вместо ложной паузы;
- [x] никакого hidden `MediaRecorder.resume()` после system event;
- [x] route monitoring регистрируется после успешного `MediaRecorder.start()`.

Важно: Android product model не продолжает запись сразу на fallback input после первого route loss. Любое продолжение требует фактического recovery adapter + явного действия пользователя.

#### 4.5. Foreground/background — Android infrastructure сделано

- [x] foreground service типа `microphone`;
- [x] `START_NOT_STICKY`;
- [x] FGS стартует только вместе с уже инициированной из user-visible flow записью;
- [x] system audio callbacks не вызывают `startService()`/`startForegroundService()` повторно;
- [x] paused/recording notification обновляется через `NotificationManager.notify()` на уже существующем FGS;
- [x] `serviceActive` принадлежит реальному `Service.onCreate/onDestroy`, а не предположению вызывающего кода;
- [x] process death не рестартует запись скрытно;
- [x] notification использует официальный Kasha monochrome asset;
- [x] immutable `PendingIntent` открывает текущую Kasha Activity;
- [x] Android 13+ acceptance различает notification permission: без `POST_NOTIFICATIONS` отсутствие drawer-card не считается recorder defect, проверяется system Task Manager / Active apps.

#### 4.6. Permission boundary — частично, ждёт #25

- [x] recorder использует только public Android SDK;
- [x] recorder достоверно определяет `GRANTED` и `UNAVAILABLE`;
- [x] до фактического system prompt recorder возвращает `NOT_DETERMINED`;
- [x] system permission dialog не вызывается скрытно внутри recorder;
- [ ] shared Kasha permission explanation/intent из #25;
- [ ] Android Activity permission-host через `ActivityResultContracts.RequestPermission`;
- [ ] после результата prompt различать `DENIED`/доступность Settings без hidden `PackageManager` flags;
- [ ] возврат из Settings только reconciles status и не повторяет autoRecord.

#### 4.7. Shared reconciliation — основа merged в main, два gap остаются

Уже в `main`:

- [x] `RecorderSessionGateway.sessionState()` — source of truth; legacy `phase()` остаётся fallback;
- [x] внешний `RECORDING -> PAUSED/INTERRUPTED` один раз замораживает monotonic timer;
- [x] в `PAUSED/INTERRUPTED` live waveform не сдвигается и не заполняется искусственными нулями;
- [x] `INTERRUPTED` считается незавершённой recorder-session и блокирует новую запись/playback;
- [x] interruption end не делает auto-resume;
- [x] Resume доступен только при `issue.recoverable=true` и только по явному действию;
- [x] exact pending identity используется при recovery;
- [x] переход adapter в `IDLE` перечитывает pending без второго autoRecord;
- [x] common typed tests покрывают freeze/no-auto-resume, playback block, IDLE reconciliation/no second start и exact recovery id.

Осталось в #14:

- [ ] `HomeAndPlayer` должен показывать `INTERRUPTED` как отдельное фактическое состояние по `recorderIssue`, а не маскировать его под пользовательскую «паузу»/`PAUSED` orb;
- [ ] active-recording Cancel: confirmation → exact `activeSessionId` → `cancelActive(activeSessionId)`; без `stopAndUpload() -> discard()`;
- [ ] common tests на exact active cancel, отсутствие Capture/STT/AI и сохранность non-target pending.

Это **Core/shared UI задача**. Реализовывать её внутри Android shell запрещено.

#### 4.8. Physical-device acceptance — ожидает #14/#25

Полное ТЗ: `docs/android/DEVICE_ACCEPTANCE.md`, тесты A4-01…A4-18.

Ключевые обязательные проверки:

- [ ] permission grant/deny/Settings return без второго start;
- [ ] user pause/resume/finish с одной session identity;
- [ ] Activity recreation/navigation без второго recorder;
- [ ] Android 14+/target 37 background/lock с уже запущенным microphone FGS;
- [ ] отсутствие background service/FGS restart из system callbacks;
- [ ] notification allowed и notification denied/Task Manager сценарии;
- [ ] входящий звонок/конкурирующий capture: freeze + no auto-resume;
- [ ] Bluetooth/headset input loss/return без незаметного fallback continuation;
- [ ] process kill → exact pending recovery;
- [ ] recovery duration/waveform из реального M4A;
- [ ] active cancel не создаёт Capture/AI;
- [ ] exact discard не затрагивает другой pending/current;
- [ ] platform pause failure → `SESSION_LOST` + recoverable pending path.

#### 4.9. Автоматическая проверка

Подтверждено до main-based интеграции:

- [x] Android code-bearing head `0d817abe6c36bb4bcc555550710b1110334d54cf`: `:androidApp:testDebugUnitTest` — success;
- [x] тот же head: `:androidApp:assembleDebug` — success;
- [x] shared fonts preparation;
- [x] Kasha Icons generation;
- [x] Android recorder/recovery unit tests.

Main-based PR #50:

- [ ] Android shell verification — выполняется;
- [ ] Kotlin Multiplatform — выполняется;
- [ ] iOS Shared regression gate — выполняется.

#28 со стороны Android больше не требует синтетической waveform: фактическое декодирование реализовано; общий issue остаётся межплатформенным.

Android-specific обход shared gaps запрещён. Пункт 5 не начинать до закрытия общего пункта 4.

## Критерий завершения этапа 4

Этап 4 можно отметить `[x]` только когда одновременно выполнены все условия:

1. [x] #37 merged в `main`.
2. [ ] #14: отдельный interruption presentation-state + active exact cancel merged, common tests зелёные.
3. [ ] #25 permission intent + Android permission-host подключены.
4. [ ] PR #50 прошёл Android/KMP/iOS Shared regression checks на актуальном `main`.
5. [ ] physical-device acceptance A4-01…A4-18 пройдена без blocker-дефектов.

Текущий этап: **4. Microphone/recording — Android platform layer READY; main-based CI RUNNING; shared #14/#25 и device acceptance PENDING.**
