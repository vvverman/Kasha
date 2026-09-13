# Kasha — архитектура

Каноническая физическая схема проекта:

```text
                           kashaCore
          models · domain · rules · contracts · AI ports
                               │
                           aiCatalog
             concrete model/provider descriptors
                               │
                        shared Kasha UI
                               │
       ┌──────────┬──────────┬─┴────────┬────────┬────────┬────────┐
     macOS      Windows    Linux      Android    iOS      Web
       │            │        │           │        │        │
     thin         thin     thin        thin     thin     thin
     shell        shell    shell       shell    shell    shell
```

## Неподвижные правила

1. В продукте ровно **одно бизнес-ядро — `kashaCore`**.
2. `kashaCore` не зависит от Compose UI, платформенных API, конкретных AI-моделей, AI-провайдеров, URL API, Keychain/Keystore/DPAPI/Secret Service.
3. Конкретные AI-модели и провайдеры находятся вне Core. AI подключается через три независимых контракта: `SPEECH_TO_TEXT`, `TEXT`, `ROUTING`.
4. Продуктовый интерфейс реализуется один раз в `composeApp`/Kasha UI.
5. Поддерживаются шесть оболочек: **macOS, Windows, Linux, Android, iOS, Web**.
6. Если код одинаков минимум на двух платформах, он не должен дублироваться в shell. Его место — Core, shared UI или общий infrastructure-модуль вне Core.
7. Shell содержит только то, что действительно платформенное: lifecycle, permissions, filesystem/DB binding, microphone, playback, notifications, secure storage, native AI runtime binding и packaging.
8. Основной режим local-first. Внешний AI опционален, требует явного consent, а API-key хранится только в защищённом хранилище конкретной платформы.

## Физические оболочки

| Платформа | Оболочка | Что остаётся платформенным |
| --- | --- | --- |
| macOS | `desktopApp` / macOS resources | app paths, Keychain, notifications, native executables, DMG |
| Windows | `desktopApp` / Windows resources | app paths, DPAPI, notifications, `.exe` native engines, MSI/EXE |
| Linux | `desktopApp` / Linux resources | XDG paths, Secret Service, notifications, ELF native engines, DEB/RPM |
| Android | `androidApp` | Activity/lifecycle, permissions, files, microphone, playback, notifications, Android native/on-device AI bindings, APK |
| iOS | `iosApp` + `composeApp/iosMain` | lifecycle, permissions, files, AVAudio, notifications, Apple/native AI bindings, IPA |
| Web | `composeApp/wasmJsMain` | browser media/storage/runtime bridge and web packaging |

Desktop использует один JVM implementation там, где API действительно переносим (например Java Sound и общая repository/runtime логика), а различия ОС изолируются в малых platform adapters и OS-specific resources. Это всё равно три оболочки поставки: macOS, Windows и Linux.

## Зависимости

Разрешённое направление:

```text
platform shell ──► shared Kasha UI ──► kashaCore
       │                  │
       ├────────► aiCatalog ─────────► kashaCore
       └────────► infrastructure/runtime ─► kashaCore + aiCatalog
```

Обратные зависимости запрещены.

## Definition of Done для архитектурного этапа

- Core и общий UI собираются независимо от конкретной оболочки.
- Все шесть оболочек физически присутствуют и имеют CI build target.
- macOS, Windows, Linux имеют самостоятельные установочные артефакты.
- Android имеет installable APK.
- iOS имеет installable IPA/SideStore artifact.
- Web имеет production Wasm bundle.
- сторонний AI подключается через общие AI-контракты без добавления provider-specific логики в Core.
- CI запускает `scripts/check-platform-shells.py` и AI/UI boundary guards.
