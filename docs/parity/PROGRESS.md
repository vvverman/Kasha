# Унификация платформ Kasha — итоговый статус

Дата начала: 14.09.2026.  
Рабочая ветка: `integration/platform-parity`.  
Интеграционный PR: **#74**.

Источники требований: `docs/SPEC.md`, нормативные разделы `docs/design/`, ТЗ владельца «Унификация платформ v1» (разделы 1–16, аудит PAR-01…PAR-10).

## Итог

По реализации и автоматизированной приёмке ТЗ **1–16 закрыто полностью: 16/16**.

| Раздел | Статус |
|---|---|
| 1–5 | DONE |
| 6 — AI | DONE |
| 7–11 | DONE |
| 12 — platform acceptance | DONE для CI / simulator / emulator / собранных пакетов; физическое железо — отдельный manual checklist |
| 13 — CI + functional acceptance | DONE |
| 14 — visual acceptance | DONE |
| 15 — install/reinstall/data preservation | DONE |
| 16 — final integrated revision | DONE |

## Целевая архитектура

Подтверждена и реализована схема:

- один общий Core с платформонезависимой координацией продуктовых сценариев;
- один shared Compose Kasha UI;
- тонкие iOS / Android / Web / Desktop shells и adapters;
- одинаковые бизнес-правила и продуктовые состояния на платформах;
- platform-specific код только для системных API и упаковки;
- local-first граница сохранена;
- конкретные AI-модели и провайдеры находятся вне Core;
- изменение общего правила не требует независимой реализации на каждой ОС.

## Persistence / recovery — ТЗ 4

Закрыто более поздней fail-closed реализацией:

- corrupt/unreadable/missing committed state или preferences не превращаются в пустую базу;
- новая установка отличима от пропавшего существующего хранилища;
- interrupted atomic write блокирует destructive migration/reconcile;
- Retry перечитывает исходные данные после исправления;
- original audio не удаляется при неуспешном startup/recovery;
- failed durable write не публикует новое состояние в памяти;
- Web проверяет реальную IndexedDB, abort/open failures, restart, повреждённый chunk journal и повтор recovery;
- Android/iOS/JVM/Desktop покрыты отдельными storage failure/recovery тестами.

PR #75 с ранней альтернативной `.bak`-схемой закрыт как superseded и не смешивается с текущим recovery policy.

## AI — ТЗ 6

Раздел закрыт.

Реализованы и проверены:

- независимые STT / TEXT / ROUTING роли;
- выбранный engine id соответствует фактическому исполнителю, без скрытой подмены;
- local/native/cloud readiness через общий capability contract;
- реальные локальные Whisper/Qwen интеграции;
- Android real-model execution на API 26 и API 35 без внешней сети;
- iOS native model integration tests;
- Desktop/Web используют общий JVM/runtime путь;
- cloud privacy/consent и secure secret boundaries;
- проверка смысловой сохранности текста: числа, отрицания и факты не должны незаметно меняться;
- при отклонённом AI-редактировании один безопасный retry строится заново из исходного текста, а не из ошибочного ответа модели.

Последний code head перед документальным обновлением:  
`f8a268a45d28934b02559d6d186d3964d3e18f05` — `test(ai): cover safe retry after rejected local edit — ТЗ 6/16`.

## Последняя полная CI-матрица code head

На `f8a268a45d28934b02559d6d186d3964d3e18f05` все 8 workflow завершились **SUCCESS**:

1. Kotlin Multiplatform;
2. Kasha Desktop Adapters;
3. Android shell verification;
4. Kasha iOS Shared;
5. Kasha iOS SideStore;
6. Автономный установщик macOS;
7. Kasha Desktop Windows Linux;
8. Kasha Android AI offline.

Документальные изменения после этого head не изменяют product/runtime code; после merge требуется новая контрольная CI-матрица уже на `main`.

## Functional / visual / package acceptance

Проверены:

- Web production Wasm/runtime, MediaRecorder, HTMLAudio, recovery, sorting, responsive/accessibility, light/dark;
- Android API 26/35: recorder/player, pause/resume/seek, mutual exclusion, Keystore, reminders, shared UI, reinstall data preservation;
- iOS: shared/native lifecycle и playback tests, SideStore/IPA packaging, production plist, shared visual matrix;
- Desktop: macOS/Windows/Linux adapters, реальные packages/installers, общий UI;
- visual acceptance для общих экранов и light/dark;
- reinstall/data preservation для Android, Windows и Linux.

## Что не считается автоматизированно проверенным

Это не незакрытые пункты общего ТЗ, а отдельная manual hardware/release acceptance:

- физический iPhone/Android microphone;
- AirPods/Bluetooth route change;
- реальный телефонный interruption;
- lock-screen/background на физическом устройстве;
- финальные Developer ID / App Store / Android production credentials;
- production signing/notarization и публикация релиза.

## Следующие операционные действия

1. Обновить статус PR #74 и перевести его из draft.
2. Влить `integration/platform-parity` в `main`.
3. Прогнать полную CI-матрицу на итоговом `main`.
4. После зелёного `main` подготовить release/version/tag и production signing/notarization там, где доступны реальные credentials.
5. Провести отдельный manual hardware checklist.

