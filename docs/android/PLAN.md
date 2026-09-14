# Kasha Android — production plan

## Правила
- Источник истины: `docs/SPEC.md`; для UI — `docs/design/`.
- Android остаётся тонким platform-layer. Общие модели, state и UI не дублируются.
- Если общий контракт отсутствует, Android не делает platform-only workaround.

## Текущий этап

**4/11 — Microphone/recording**

### Сделано
- [x] Android application shell.
- [x] App-private persistence/filesystem и crash recovery.
- [x] `AndroidRecorder : RecorderSessionGateway`.
- [x] AAC/M4A start / pause / resume / finalize.
- [x] Stable active session id и exact pending ids.
- [x] Safe active cancel без Capture/STT/AI.
- [x] Duration без пауз, live/final waveform, M4A recovery.
- [x] Recorder error, route loss и system silencing → typed interruption.
- [x] No auto-resume после system event.
- [x] Microphone foreground service, `START_NOT_STICKY`.
- [x] FGS стартует только из user-visible capture flow; callbacks не стартуют service повторно.
- [x] Backup/device transfer для данных Kasha отключены.
- [x] Active-recording Cancel уже подключён в shared `main` через exact session id.
- [x] Android `testDebugUnitTest + assembleDebug` — GREEN.
- [x] iOS Shared regression — GREEN.
- [x] Core/UI/runtime/WASM build — GREEN.

### Ожидает
1. **#14** — отдельное presentation-state для `INTERRUPTED`; сейчас оно выглядит как обычная пауза.
2. **#25** — explicit microphone request через существующий `DeviceCapabilityGateway` и shared flow: объяснение → явное действие → system prompt → один ранее запрошенный start.
3. **Physical Android acceptance** — A4-01…A4-18 из `DEVICE_ACCEPTANCE.md`.

До выполнения этих трёх пунктов этап 4 не закрывается и этап 5 не начинается.

## Дальше
- [ ] 5. Playback/audio focus.
- [ ] 6. Reminders/notifications.
- [ ] 7. Secure storage / Android Keystore.
- [ ] 8. Lifecycle/system integration.
- [ ] 9. Accessibility/platform UX.
- [ ] 10. CI/build/package APK/AAB.
- [ ] 11. Финальная Android-приёмка.

## Основная интеграция
- Android stage 4: PR #50, `platform/android-main-integration` → `main`.
- Старый PR #41 закрыт как superseded.
- Экспериментальный permission PR #53 закрыт без merge: отдельный permission API не нужен; используется существующий `DeviceCapabilityGateway`.

Подробные ручные сценарии не дублируются здесь — они живут в `docs/android/DEVICE_ACCEPTANCE.md`.
