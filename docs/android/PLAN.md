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

Интеграционный PR-check отдельно может быть красным из-за текущих изменений shared Kasha UI в `main`; такие ошибки не обходятся Android-specific кодом и остаются ответственностью общего UI/Gradle слоя.

Текущий этап: **4. Microphone/recording**.
