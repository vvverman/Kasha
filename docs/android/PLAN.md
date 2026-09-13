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

### 4. Microphone/recording — в работе, Android-часть реализована

Готово в Android shell:

- реальный `MediaRecorder`, AAC/M4A, mono 44.1 kHz, bitrate из общей `Preferences.quality`;
- `RECORD_AUDIO` проверяется платформой, но system permission не запрашивается скрытно внутри recorder;
- start/pause/resume/finalize и app-private pending;
- duration исключает паузы;
- реальные амплитуды собираются самим recorder независимо от UI lifecycle;
- финальная waveform представляет всю временную шкалу и сводится максимум к 512 точкам;
- process-scoped runtime не создаёт второй recorder при пересоздании Activity;
- background capture защищён foreground service типа `microphone`; сервис запускается только вместе с уже инициированной записью и не рестартует её после process death;
- recovery измеряет фактическую duration M4A и не выдумывает успешное восстановление нечитаемого файла;
- до подключения реального Android AI capture остаётся сохранённым с честным `NEEDS_MODEL`, без demo/fake STT.

Пункт пока **не закрывается** из-за общих межплатформенных контрактов:

- #25 — shared Core/UI permission intent, безопасный cancel active recording, типизированные interruption/route/hardware причины и background capability;
- #28 — общий контракт полного waveform/duration при recovery из готового файла.

Android-specific обход этих разрывов запрещён. После появления общих контрактов Android должен только реализовать соответствующие системные операции и пройти device acceptance.

Интеграционный PR-check отдельно может отражать свежие изменения shared Kasha UI в `main`; такие ошибки не обходятся Android-specific кодом.

Текущий этап: **4. Microphone/recording**. Пункт 5 не начинать до закрытия пункта 4.
