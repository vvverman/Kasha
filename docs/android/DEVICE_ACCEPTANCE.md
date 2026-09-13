# Kasha Android — device acceptance этапа 4

Назначение: физическая приёмка **этапа 4/11 — Microphone/recording** после merge общего recorder contract/reconciliation. Этот документ не заменяет unit/CI; он проверяет системное поведение Android, которое нельзя достоверно подтвердить JVM-тестами.

## 1. Когда запускать

Device acceptance запускается только после выполнения всех условий:

- Core PR #37 находится в `main`;
- shared #14 использует `RecorderSessionGateway.sessionState()` как source of truth;
- shared #25 подключил permission intent, а Android host реально запрашивает `RECORD_AUDIO` через Activity Result API;
- Android PR #41 retargeted/rebased на актуальный `main`;
- `:androidApp:testDebugUnitTest` и `:androidApp:assembleDebug` зелёные.

До этого результаты permission/interruption UI не считаются финальной приёмкой.

## 2. Тестовая матрица

Минимально:

1. **Современный Android:** Android 14+; здесь проверяются while-in-use microphone/FGS ограничения.
2. **API 29–33:** здесь доступен `AudioRecordingCallback.isClientSilenced`.
3. **API 26–28:** minSdk-ветка без `AudioRecordingCallback`; route/error fallback обязан оставаться рабочим.

Если доступен только один физический телефон, приоритет — Android 14+; остальные API должны оставаться покрыты compile/unit gate и затем проверяются при наличии устройства.

Для Bluetooth-сценариев нужен реальный headset с микрофоном.

## 3. Сборка и базовая диагностика

Application id: `ru.vrmn.kasha`.

Собрать/установить debug:

```bash
gradle :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Зафиксировать устройство:

```bash
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

Для debug build допустимо смотреть app-private файлы без копирования пользовательского аудио наружу:

```bash
adb shell run-as ru.vrmn.kasha find files -maxdepth 4 -type f
```

Проверка process/service состояния:

```bash
adb shell pidof ru.vrmn.kasha
adb shell dumpsys activity services ru.vrmn.kasha
```

При фиксации результатов не прикладывать пользовательские записи. Достаточно ids/размеров/статусов и системных логов без содержимого аудио.

## 4. Формат результата

Для каждого теста фиксировать:

- ID;
- устройство/API;
- PASS / FAIL / BLOCKED;
- фактическое состояние recorder/UI;
- наличие/отсутствие FGS notification либо системного FGS Task Manager entry;
- количество active/pending sources;
- короткое описание отклонения;
- commit Android PR #41 и commit `main`, на которых проводилась проверка.

Любой FAIL из разделов permission, identity, cancel, process recovery или hidden auto-resume — **blocker этапа 4**.

## 5. Permission

### A4-01 — первый запрос: grant

Предусловие:

```bash
adb shell pm clear ru.vrmn.kasha
```

Шаги:

1. Запустить Kasha.
2. Инициировать запись/autoRecord flow.
3. Убедиться, что до system dialog показано Kasha explanation из shared UI.
4. Нажать действие выдачи доступа.
5. В системном dialog выбрать Allow.

Ожидается:

- system dialog вызывается ровно из Android permission-host, не из recorder;
- до grant нет fake `RECORDING`, таймера или waveform;
- после grant создаётся ровно одна recorder-session;
- создаётся один active pending source;
- запускается один microphone FGS;
- autoRecord не стартует второй раз после возврата из dialog.

Blocker:

- recorder стартовал до grant;
- два active/pending source;
- два `start()` после одного permission flow;
- скрытый system prompt без Kasha explanation.

### A4-02 — первый запрос: deny

1. Очистить app data.
2. Запустить permission flow.
3. Нажать Deny.

Ожидается:

- recorder не создаётся;
- pending source не создаётся;
- FGS не запускается;
- UI остаётся в permission/idle-состоянии;
- нет fake timer/waveform;
- повторный foreground сам не вызывает новый prompt/start.

### A4-03 — Settings return

1. Получить denied state.
2. Открыть Android Settings для Kasha.
3. Изменить microphone permission.
4. Вернуться в Kasha.

Ожидается:

- возврат только reconciles фактический permission state;
- запись сама не стартует только из-за `onResume`/foreground;
- при следующем явном capture intent используется новый permission state;
- второй recorder не создаётся.

## 6. Базовая запись

### A4-04 — start → pause → resume → finish

1. Начать запись.
2. Говорить 5–10 секунд.
3. Поставить на паузу минимум на 5 секунд.
4. Продолжить ещё 5–10 секунд.
5. Завершить.

Ожидается:

- session id не меняется;
- пауза не входит в recorder duration;
- waveform на user pause заморожена;
- после Resume waveform продолжает ту же временную шкалу;
- после finish active source становится ровно одним Capture/source;
- processing запускается только после finish.

### A4-05 — навигация и Activity recreation

Во время активной записи:

1. Перейти между разделами Kasha.
2. Свернуть/развернуть UI, не завершая process.
3. Повернуть устройство либо вызвать configuration change, если rotation доступен.

Ожидается:

- session id не меняется;
- recorder не создаётся повторно;
- waveform/elapsed продолжаются из той же process-scoped session;
- навигация не владеет recorder lifecycle.

Blocker: второй recorder/pending source.

## 7. Foreground/background

### A4-06 — background после foreground start

1. Начать запись из **видимой** Kasha Activity.
2. Нажать Home.
3. Оставить запись в background несколько минут.
4. Заблокировать и разблокировать экран.
5. Вернуться в приложение.

Ожидается:

- microphone FGS был создан до ухода Activity в background;
- дальнейшие `recording/paused` updates не вызывают повторный `startService()`/`startForegroundService()` из background, а только обновляют существующую notification;
- в logcat нет `ForegroundServiceStartNotAllowedException`, `BackgroundServiceStartNotAllowedException` и сообщения о microphone FGS, созданном из запрещённого background-state;
- системный FGS остаётся активным всё время незавершённой session;
- process не создаёт второй recorder при возврате;
- elapsed соответствует фактической active recording duration;
- приложение не пытается скрытно restart-ить session.

Blocker:

- новый microphone FGS создаётся после ухода Activity в background;
- system callback пытается повторно стартовать service;
- recorder/session рестартует после foreground return.

### A4-07 — notification / Task Manager entry

Android 13+ отдельно проверяется в двух состояниях notification permission.

#### A4-07a — app notifications разрешены

1. Разрешить notifications для Kasha, если permission уже реализован соответствующим notification-этапом/сборкой.
2. При активной записи уйти из Kasha.
3. Нажать foreground notification.

Ожидается:

- notification видна в drawer;
- открывается текущая `MainActivity`;
- существующая session сохраняется;
- второй recorder/start отсутствует;
- notification использует Kasha icon.

#### A4-07b — app notifications запрещены или ещё не запрашиваются

На Android 13+ отсутствие FGS-card в notification drawer **не является дефектом recorder**, если `POST_NOTIFICATIONS` не выдан.

Ожидается:

- foreground service остаётся видимым системе в Task Manager / Active apps;
- запись продолжает ту же session;
- запрет notifications не останавливает recorder и не создаёт второй recorder;
- Kasha не пытается обойти системный notification permission скрытым prompt.

`POST_NOTIFICATIONS` и продуктовый permission UX не переносятся в этап 4 только ради этого теста; они должны появиться в своём notification flow.

## 8. System interruption / competing capture

### A4-08 — звонок или конкурирующий microphone capture

Предпочтительно проверить реальным входящим/исходящим звонком и отдельно приложением, которое получает microphone priority.

Шаги:

1. Начать запись Kasha.
2. Вызвать системное событие/конкурирующий capture.
3. Дождаться фактического silencing/interruption.

Ожидается:

- adapter выходит из фактического `RECORDING` в typed `INTERRUPTED`;
- timer замораживается shared #14;
- live waveform замораживается и не заполняется нулями;
- `issue=INTERRUPTION`;
- пока Android сообщает silenced, `recoverable=false`;
- playback и новый record start заблокированы;
- FGS отражает незавершённую, но не активно пишущую session.

Blocker: микрофон продолжает считаться recording в product state либо UI продолжает timer/waveform.

### A4-09 — interruption end

1. Завершить звонок/конкурирующий capture.
2. Ничего не нажимать в Kasha.

Ожидается:

- hidden auto-resume отсутствует;
- session id не меняется;
- adapter может выставить `recoverable=true` только после фактического unsilence/доступного input;
- UI остаётся interrupted до явной команды пользователя.

### A4-10 — explicit Resume после interruption

1. Дождаться `recoverable=true`.
2. Нажать «Продолжить».

Ожидается:

- продолжается та же session id;
- создаётся новый monotonic active segment;
- timer не включает период interruption;
- новый pending/capture не создаётся.

## 9. Input route / Bluetooth

### A4-11 — потеря активного Bluetooth/headset input

1. Подключить headset с микрофоном.
2. Убедиться, что запись реально идёт через него.
3. Во время записи отключить headset/убрать Bluetooth route.

Ожидается:

- фактическая route change приводит к `INTERRUPTED(INPUT_UNAVAILABLE)`;
- запись не продолжает незаметно продуктовую session на fallback mic;
- первый route-switch не делает скрытый Resume;
- новый Capture/pending не создаётся;
- пользователь видит interruption после shared #14.

### A4-12 — route recovery/change после A4-11

1. Вернуть headset либо предоставить новый валидный input route.
2. Не нажимать Resume сразу.

Ожидается:

- route event сам запись не запускает;
- только фактическое adapter recovery может сделать Resume доступным;
- продолжение происходит только по явной команде;
- быстрые последовательные route events не создают новые source/session.

## 10. Process death и recovery

### A4-13 — process kill при незавершённой записи

1. Начать запись и дать файлу получить реальное аудио.
2. Увести приложение в background.
3. Завершить process для debug-проверки, например:

```bash
adb shell am force-stop ru.vrmn.kasha
```

4. Запустить Kasha снова.

Ожидается:

- FGS не restart-ит capture самостоятельно;
- прежний app-private source не теряется;
- source обнаруживается как exact pending id;
- autoRecord не перекрывает pending новой записью.

### A4-14 — recover pending

После A4-13 выбрать восстановление.

Ожидается:

- восстанавливается ровно выбранный pending id;
- duration берётся из фактического M4A;
- waveform восстанавливается из PCM, если OEM decoder поддерживает формат;
- если decoder не поддержан, waveform остаётся неизвестной/пустой, а не синтетической;
- создаётся ровно один Capture;
- повторное восстановление того же id не создаёт дубль.

### A4-15 — unreadable pending

Проверить fixture/повреждённый pending в debug-среде.

Ожидается:

- recovery не выдаёт fake success;
- файл не превращается в фиктивный Capture с выдуманной duration/waveform;
- ошибка не удаляет другой pending source.

## 11. Cancel

### A4-16 — cancel active

1. Начать запись.
2. Отменить её через продуктовый confirmation flow.

Ожидается:

- останавливаются recorder, sampler и FGS;
- удаляется только source active session id;
- Capture не создаётся;
- STT/Text/Routing/внешний AI не запускаются;
- другой pending source, если он существует в recovery fixture, не удаляется.

Blocker: реализация через `finish/stopAndUpload -> discard`.

### A4-17 — discard exact pending

При fixture с несколькими pending ids удалить один выбранный.

Ожидается:

- удалён только заданный id;
- остальные pending остаются;
- current Capture не изменён.

## 12. FGS fail-safe

### A4-18 — platform pause failure / session lost

Сценарий обычно подтверждается instrumented/debug fault injection либо реальным OEM failure.

Ожидается:

- если Android не смог физически pause recorder, Kasha не оставляет ложный `INTERRUPTED(recoverable=true)`;
- hardware освобождается;
- FGS останавливается;
- source остаётся pending, если файл существует;
- adapter сообщает `SESSION_LOST`;
- recovery выполняется отдельным exact pending flow.

## 13. Exit criteria

Этап 4 получает статус **DONE** только если:

- A4-01…A4-18, применимые к доступному устройству/API, пройдены;
- нет blocker FAIL;
- #37/#14/#25 находятся в `main`;
- Android PR #41 retargeted на `main`;
- после retarget `:androidApp:testDebugUnitTest :androidApp:assembleDebug` зелёные;
- результаты теста записаны в PR #41 либо отдельный acceptance comment с device/API/commit matrix.

После этого `docs/android/PLAN.md` пункт 4 можно отметить `[x]` и начинать **этап 5/11 — Playback/audio focus**.
