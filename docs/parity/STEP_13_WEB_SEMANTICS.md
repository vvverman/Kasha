# ТЗ 13.1 — восстановление Web accessibility после диалога

## Исходный дефект

Head `d944e34a21035747d6d5cb6b27bcd41055df15eb`, KMP run `35081898373`, job `104747612920`: JVM/Core/shared/runtime tests и production Wasm собраны, но `tests/non_ai_parity_browser.py` упал после закрытия ошибки сохранения порядка. На приложенном CI screenshot исходный порядок восстановлен, `semantics.txt` пуст.

В Compose 1.12.0 `ComposeWebSemanticsListener` хранит одного `SemanticsOwner`: новый Dialog/Popup заменяет основного владельца, а удаление слоя обнуляет ссылку без восстановления предыдущего. Это соответствует upstream fix https://github.com/JetBrains/compose-multiplatform-core/pull/3298.

## Изменение и ограничения

- Compose закреплён на `1.13.0-alpha01`, содержащем официальный fix #3298. Это **предварительная версия**, не стабильный релиз и не подтверждение готовности Kasha к выпуску.
- Только в Web entry point применён официальный workaround `ComposeUiFlags.useSnapshotCache = false` для известного CMP-10732 этой версии. Источник: https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.13.0-alpha01.
- Общий UI, Core, модель порядка, данные пользователя, providers и раздел 6 не меняются.
- Не создаётся копия Compose, DOM-подмена элементов или перезагрузка страницы при закрытии модального окна.
- Требуется проверка всех non-AI workflow на одном head; изменение общей зависимости затрагивает все платформы. Дальнейший переход на стабильный Compose допустим только при наличии fix #3298 и повторной приёмке.

## Регрессия

`tests/non_ai_parity_browser.py`: две подряд ошибки записи порядка; закрытие кнопкой и Escape; возврат исходных строк и порядка в доступное дерево без reload; успешный retry; сохранность после reload; клавиатурная перестановка. При сбое сохраняются screenshot, ARIA tree, DOM и page errors.

До результата CI новая ревизия не считается принятой. Локально выполнены синтаксическая проверка Python, проверки архитектурных границ и Node-контракты Web-плеера. Локальный браузер этой среды блокирует переход на localhost; браузерная проверка выполняется в GitHub Actions, не подменяется unit-тестами.

Раздел 6: `DEFERRED: section 6`. `main` не изменяется, PR #74 не сливается.
