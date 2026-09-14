# Выпуск Kasha для iOS

Канон продукта: `docs/SPEC.md`. Статус iOS: issue #15.

## Два варианта одной программы

| Вариант | Имя | Bundle ID | Подпись |
|---|---|---|---|
| Основной | Kasha | `ru.vrmn.kasha` | Automatic, команда владельца в Xcode |
| SideStore | Kasha Test | `ru.vrmn.kasha.test` | CI выпускает unsigned IPA; SideStore подписывает при установке |

Core, Kasha UI и iOS-адаптеры у вариантов одинаковые. Идентификатор тестовой сборки сохранён: не удаляйте установленную Kasha Test ради обновления. Основной и тестовый идентификаторы обозначают разные приложения; переноса данных между ними эта сборка не добавляет.

## SideStore

Существующий workflow `.github/workflows/ios-sidestore.yml` создаёт архив приложения и `Kasha.ipa`. В PR результат находится в Actions artifacts; сборка `main` публикует его в release `sidestore-latest`.

Адрес источника:

```text
https://github.com/vvverman/Kasha/releases/download/sidestore-latest/source.json
```

Прямой IPA:

```text
https://github.com/vvverman/Kasha/releases/download/sidestore-latest/Kasha.ipa
```

`make-sidestore-source.py` читает версию, build, разрешения и минимальную iOS из готового IPA. Отсутствие executable, разрешений аудио или ресурсов Kasha UI блокирует публикацию. Собранный unsigned IPA не является подписанной сборкой TestFlight.

## Основная сборка в Xcode

Нужны macOS с Xcode, JDK 21, Gradle 9.3.1, Python 3, `xcodegen` и `rsvg-convert` (librsvg). Команды выполняются из корня репозитория:

```sh
python3 -m pip install fonttools==4.59.2
python3 scripts/prepare-fonts.py
gradle :composeApp:linkReleaseFrameworkIosArm64
ICON_DIR=iosApp/Assets.xcassets/AppIcon.appiconset
for size in 40 58 60 80 87 120 180 1024; do
  rsvg-convert -w "$size" -h "$size" iosApp/KashaAppIcon.svg -o "$ICON_DIR/icon-$size.png"
done
cp "$ICON_DIR/icon-120.png" "$ICON_DIR/icon-120-60.png"
xcodegen generate --spec iosApp/project.yml
open iosApp/Kasha.xcodeproj
```

В Signing & Capabilities выбрать свою Team. Назначить уникальный build для загрузки. Выбрать устройство iOS и выполнить Product → Archive; затем в Organizer выполнить Validate App и Distribute App → TestFlight & App Store.

Ресурсы общего UI копируются в приложение штатной задачей Compose из build phase Xcode. Запрет подписи задаётся только аргументами unsigned-сборки CI, не проектом.

Подпись, загрузка в App Store Connect и публикация не выполняются автоматически этим изменением. Для них нужны доступ владельца к Apple Developer Program, зарегистрированный Bundle ID, карточка приложения и заполненные сведения о приватности. Privacy manifest зависимостей, ответы App Privacy и требования Apple к SDK проверяются перед отправкой, а не считаются подтверждёнными фактом сборки IPA.

## Приёмка после завершения реализации

По указанию владельца глубокая проверка вынесена в следующий проход. Пункт 9 issue #15 пока не пройден: на физическом iPhone проверить запись и сохранение, звонок/AirPods, фон и блокировку, отказ разрешений, восстановление после закрытия, конфликт записи и плеера, напоминание и обновление без потери данных. Наличие архива или зелёного CI не заменяет эти результаты.

Справочные источники: Apple Developer Documentation — «Distributing your app for beta testing and releases»; AltStore — «Make a Source»; Compose Multiplatform — `IosResources.kt` (штатная синхронизация ресурсов iOS).
