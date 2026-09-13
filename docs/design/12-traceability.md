# 12. Покрытие исходных требований

Матрица связывает весь пользовательский канон **0–44**, уточнения из этого диалога и действующие правила репозитория с конкретными документами. Это проверка полноты документации, не отметка готовности приложения.

## 1. Канон пользователя

| Раздел | Требование | Спецификация | Приёмка / решение |
|---|---|---|---|
| C00 | Local-first, спокойный дорогой личный инструмент | [Решения](00-decisions.md), [Основы](02-foundations.md) | D-01…D-31; VIS-12 |
| C01 | Только существующие logo/solid, размеры splash и app icon | [Основы](02-foundations.md), [Ассеты](assets/README.md) | VIS-01/09; D-01/02 |
| C02 | Geologica Variable, одна гарнитура, display axes и полная шкала | [Основы](02-foundations.md), [Токены](tokens/design-tokens.json) | VIS-02/03 |
| C03 | Температура, свет, глубина вместо яркой палитры | [Основы](02-foundations.md) | VIS-04/05 |
| C04 | Dark Canvas/Surface/Text/Disabled | [Основы](02-foundations.md), [Токены](tokens/design-tokens.json) | VIS-04/05 |
| C05 | Дозированный песочный accent и варианты | [Основы](02-foundations.md) | VIS-04/12 |
| C06 | Тёплая бумажная Light Theme | [Основы](02-foundations.md) | VIS-04/05 |
| C07 | Сдержанные success/warning/error | [Основы](02-foundations.md), [Компоненты](03-components.md) | VIS-05; A11Y-06 |
| C08 | Четырёхслойный procedural Home background | [Графика](08-graphics-motion.md) | VIS-10 |
| C09 | Гигантская слабая форма из настоящего знака | [Графика](08-graphics-motion.md) | D-01; VIS-10/12 |
| C10 | Только тёплый свет | [Основы](02-foundations.md), [Графика](08-graphics-motion.md) | VIS-04 |
| C11 | Recording Orb, idle/recording/paused/processing | [Запись](04-capture-player.md), [Графика](08-graphics-motion.md) | UX-02; VIS-10/11 |
| C12 | Waveform по реальному аудио, градиент, playback, freeze | [Графика](08-graphics-motion.md), [Запись](04-capture-player.md) | UX-02; GAP-01/04 |
| C13 | Home Idle/Recording/Processing/Result, две команды | [Запись](04-capture-player.md) | UX-07/11/15; VIS-12 |
| C14 | Редактирование напрямую, первая непустая строка = title | [Контент](05-projects-notes-tasks.md), [Компоненты](03-components.md) | UX-23/24 |
| C15 | Глобальный плеер, все record/playback/processing states | [Запись](04-capture-player.md) | UX-03/04; D-15/16 |
| C16 | Четыре пункта в одной nav surface, без FAB | [Компоненты](03-components.md), [Адаптивность](07-adaptive-accessibility.md) | VIS-07; D-17 |
| C17 | Проекты, заголовок/add/sort, pinned, осмысленная строка | [Контент](05-projects-notes-tasks.md) | UX-20; GAP-09 |
| C18 | Четыре режима сортировки, текущий режим виден | [Контент](05-projects-notes-tasks.md), [Компоненты](03-components.md) | UX-19; D-21 |
| C19 | Long press lift, scale/shadow, haptics, drag | [Компоненты](03-components.md), [Графика](08-graphics-motion.md) | UX-21/22 |
| C20 | Строка заметки: title/preview/date/audio | [Контент](05-projects-notes-tasks.md) | UX-23; VIS-06 |
| C21 | Заметка: header/text/sources/player/metadata/actions | [Контент](05-projects-notes-tasks.md) | UX-12/23/24 |
| C22 | Простая задача, completion/text/due/reminder | [Контент](05-projects-notes-tasks.md) | UX-15; D-24/25 |
| C23 | Собственный completion glyph и уход в архив | [Компоненты](03-components.md), [Контент](05-projects-notes-tasks.md) | UX-17; VIS-08 |
| C24 | Редактор задачи, срок, напоминание, 7 повторов | [Контент](05-projects-notes-tasks.md) | UX-16/18; D-25; GAP-08 |
| C25 | Группы настроек Recording/AI/UI/Privacy/About | [Настройки](06-settings-privacy.md) | PRI-02/08; A11Y-02 |
| C26 | Явное согласие external AI: кому/что/покидает устройство | [Настройки](06-settings-privacy.md), [Тексты](09-content-language.md) | PRI-03…07; GAP-10 |
| C27 | Canvas/Surface/Floating, нет карточек в карточках | [Основы](02-foundations.md), [Компоненты](03-components.md) | VIS-06 |
| C28 | Hairline в двух темах | [Основы](02-foundations.md) | VIS-05; A11Y-09 |
| C29 | Тени light и restrained depth dark | [Основы](02-foundations.md), [Графика](08-graphics-motion.md) | VIS-06/10 |
| C30 | Primary/Secondary/Quiet/IconButton размеры | [Компоненты](03-components.md), [Токены](tokens/design-tokens.json) | A11Y-01; VIS-06 |
| C31 | Press scale, luminance, down/release motion | [Компоненты](03-components.md), [Графика](08-graphics-motion.md) | VIS-11; A11Y-07 |
| C32 | Surface inputs, внешний label, focus accent | [Компоненты](03-components.md), [Тексты](09-content-language.md) | A11Y-03/09 |
| C33 | Собственные glyph 24×24, stroke, round caps | [Компоненты](03-components.md), [Комплект иконок](icons/README.md) | VIS-08 |
| C34 | Полный базовый icon set по категориям | [Комплект иконок](icons/README.md), [Компоненты](03-components.md) | VIS-08; реестр/экспорты |
| C35 | Motion для навигации/sheets/reorder/orb/completion/processing | [Графика](08-graphics-motion.md) | VIS-11 |
| C36 | Reduce Motion: отключение декора, функциональная waveform | [Графика](08-graphics-motion.md), [Доступность](07-adaptive-accessibility.md) | A11Y-07 |
| C37 | Haptics только на смысловых событиях | [Графика](08-graphics-motion.md) | A11Y-10 |
| C38 | Targets/AA/200%/screen reader/состояние waveform | [Доступность](07-adaptive-accessibility.md), [Основы](02-foundations.md) | A11Y-01…10 |
| C39 | SVG для векторов, процедурная графика, noise | [Графика](08-graphics-motion.md), [Ассеты](assets/README.md) | VIS-10; исключение U-03 |
| C40 | Не растрировать UI, waveform, orb и controls | [Графика](08-graphics-motion.md), [Компоненты](03-components.md) | VIS-10 |
| C41 | Композиция Home с сильным центром | [Запись](04-capture-player.md), [Адаптивность](07-adaptive-accessibility.md) | VIS-12; A11Y-02 |
| C42 | Премиальность через воздух/пропорции/type/motion | [Решения](00-decisions.md), [Основы](02-foundations.md) | VIS-01…12 |
| C43 | Все визуальные запреты | [Решения](00-decisions.md) | D-01…31; фото-исключение U-03 |
| C44 | Industrial/editorial/analog warmth/precise tool | [Решения](00-decisions.md), [Референсы](references/README.md) | VIS-12; визуальная приёмка |

## 2. Уточнения этого диалога

| ID | Указание | Как выполнено в пакете |
|---|---|---|
| U-01 | Объединить текущий код, рисунки и текстовое ТЗ; учесть соседнюю разработку | Проверены `main` и PR #9; [карта](01-repository-map.md) и [план](11-implementation-plan.md) различают реализованное, работу в ветке и целевое |
| U-02 | Подробная документация для разработки, можно улучшать; загрузить в GitHub | Экранные и компонентные спецификации, tokens, состояния, тексты, приёмка, staged implementation; публикация только проверенного пакета |
| U-03 | Сделать фон с тёмной макросъёмкой гречки, затем ограничение «только сплеш» | Реальный [графический ассет](assets/README.md); Home и рабочие экраны остаются процедурными; прежний абсолютный запрет фото уточнён |
| U-04 | Навести порядок в иконках, сделать анимированными, узнаваемыми | [Единый реестр, SVG и анимированный каталог](icons/README.md), соответствие существующим Glyph, инструкция внедрения |
| U-05 | Использовать сделанный пользователем логотип | Ссылки на существующие source SVG, сохранность контуров и пропорций; нет нового знака |

## 3. Требования текущего продукта, которые нельзя потерять

| ID | Сохранённое правило репозитория | Где раскрыто |
|---|---|---|
| R-01 | Один отделяемый Core и один shared UI | [Карта](01-repository-map.md), [Внедрение](11-implementation-plan.md) |
| R-02 | Три независимые AI-роли, конкретные модели вне Core | [Настройки](06-settings-privacy.md), GAP-05/06 |
| R-03 | Local-first, отсутствие аккаунта/обязательного облака, secure keys | [Настройки](06-settings-privacy.md), PRI-01…09 |
| R-04 | Auto-record с разрешением, pending recovery имеет приоритет | [Запись](04-capture-player.md), UX-01/25 |
| R-05 | Запись и playback взаимоисключаются | [Запись](04-capture-player.md), UX-04 |
| R-06 | Один capture → одна note либо task, idempotency | [Контент](05-projects-notes-tasks.md), UX-11…15 |
| R-07 | Append только в конец, отдельные аудиоисточники | [Контент](05-projects-notes-tasks.md), UX-12 |
| R-08 | Проектная инструкция участвует в routing; рейтинг не портит сортировку | [Контент](05-projects-notes-tasks.md), D-23/30 |
| R-09 | Persistent manual order и отдельный pin order | [Контент](05-projects-notes-tasks.md), UX-19…22 |
| R-10 | 7 частот напоминаний, перенос срока, архив | [Контент](05-projects-notes-tasks.md), UX-16…18 |
| R-11 | Исходное аудио перед оптимизацией, качество, savedSpeed 1.5× | [Запись](04-capture-player.md), [Настройки](06-settings-privacy.md), GAP-11 |
| R-12 | 8 языков, system/fallback, проверка шрифтов | [Основы](02-foundations.md), [Тексты](09-content-language.md) |
| R-13 | Общая проверка AI на числа/имена/отрицания | [Запись](04-capture-player.md), GAP-06; UX-09/10 |
| R-14 | Модели: скачивание, проверка, install/remove/errors | [Настройки](06-settings-privacy.md), PRI-08 |
| R-15 | Kasha UI boundary и собственные иконки | [Компоненты](03-components.md), [Иконки](icons/README.md) |
| R-16 | Платформы отличаются адаптерами, а не дублированными экранами | [Карта](01-repository-map.md), [Адаптивность](07-adaptive-accessibility.md) |

## 4. Осознанные изменения прежнего дизайна

Geologica заменяет Commissioner; компактная команда сортировки заменяет четыре pills; единая nav surface заменяет отдельные item backgrounds; прямое редактирование становится нормой; MAGIC-звёзды заменяются обработкой текста; pinned проекты остаются сверху и в Manual через изменение общего правила. Эти отличия являются целевыми решениями, а не ошибочным описанием текущего `main`.

Независимое время первого напоминания и полное выключение напоминаний не выдаются за реализованный baseline: текущая модель привязывает первое напоминание к сроку. Автоматическое создание повторяющихся задач, live STT и новые CRUD-команды без существующих контрактов не появляются только потому, что нарисованы в референсе.
