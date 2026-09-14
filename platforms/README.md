# `platforms/` — тонкие оболочки Kasha

Здесь находится только код, который действительно зависит от платформы или формата поставки.

```text
platforms/
├── android/              # Android app shell → APK
├── desktop/              # общий JVM desktop shell
│   └── packaging/        # macOS / Windows / Linux native bundles
├── ios/
│   ├── shared/           # Kotlin/Native iOS adapters + KashaShared.framework
│   └── app/              # минимальная Xcode/Swift оболочка → IPA
└── web/                  # browser adapters + Wasm entry point
```

`desktop/` является одним общим implementation-слоем ради максимального reuse, но выпускает три самостоятельные platform deliveries:

- macOS → DMG;
- Windows → MSI/EXE;
- Linux → DEB/RPM.

Итого у Kasha ровно шесть целевых оболочек: **macOS, Windows, Linux, Android, iOS, Web**.

## Что разрешено в platform shell

- lifecycle и entry point;
- permissions;
- filesystem/application directories;
- microphone и playback;
- системные notifications;
- Keychain / Keystore / DPAPI / Secret Service;
- platform-native AI runtime binding;
- packaging/signing/install artifacts.

## Что запрещено

В `platforms/` нельзя заново реализовывать модели, сортировки, task lifecycle, note/project rules, AI privacy rules или продуктовые экраны. Это должно переиспользоваться из `modules/`.
