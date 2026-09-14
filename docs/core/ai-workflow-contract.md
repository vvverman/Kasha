# AI workflow contract

Этап 6 Core фиксирует только существующий AI pipeline из `docs/SPEC.md`, без новых AI-функций.

## Core invariants

- Core остаётся единым; AI внутри него разделён на три независимые роли: `SPEECH_TO_TEXT`, `TEXT`, `ROUTING`.
- Выбор движка хранится отдельно для каждой роли через opaque engine id. Конкретные модели, providers, endpoints и secrets в Core не находятся.
- Результат `TEXT` независимо от local/cloud проходит `CaptureWorkflow.tidy`: непустой текст, разумный объём и сохранность мыслей, чисел, имён/названий и отрицаний.
- Результат `ROUTING` независимо от движка проверяется Core на точный набор project id и диапазон `0..4`.
- Сбой/некорректный результат routing не уничтожает capture: запись остаётся `READY`, `relevance` очищается, `rankingApplied=false`.
- Отмена coroutine не считается routing fallback и должна пробрасываться дальше.
- Пустая расшифровка не считается успешным `SPEECH_TO_TEXT`.

## Platform/runtime responsibility

- Platform/runtime выбирает конкретную реализацию каждой роли и хранит secrets.
- Cloud execution разрешается только для подключённой роли с актуальным `AiPrivacy` consent.
- iOS и JVM runtime обязаны передавать AI-кандидаты в общие Core-проверки, а не реализовывать отдельные продуктовые правила.
- Provider-specific parsing/transport остаётся за пределами Core.

## Совместимость

Модели данных, persistence/API и миграции не меняются. Новых AI roles, provider contracts или пользовательских сценариев этот этап не добавляет.
