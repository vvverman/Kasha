# Kasha UI

Kasha UI — единственный продуктовый UI-слой Kasha поверх Compose Foundation/UI. Он общий для Android, iOS, Desktop и Web и не должен визуально превращаться в Material Design.

Подробная целевая спецификация от 13.09.2026: [дизайн-ТЗ](design/README.md), [компоненты](design/03-components.md), [графика и motion](design/08-graphics-motion.md). Эта страница описывает границы существующего слоя; наличие компонента в списке ниже не означает соответствие новому внешнему виду.

## Граница

Продуктовые экраны не создают кнопки, поля, переключатели, слайдеры или навигацию напрямую. Эти элементы берутся только из `composeApp/src/commonMain/kotlin/brain/studio/ui`.

`MaterialTheme` используется как контейнер токенов цвета и типографики. CI запускает `scripts/check-ui-boundary.py` и запрещает:

- базовые Material-контролы вне Kasha UI;
- Material Icons, SF Symbols, Phosphor, Lucide и другие сторонние icon packs;
- возврат старых `BrainUi` / `BrainNavigation`.

## Компоненты

- `KashaButton` — primary/secondary + hover/pressed/focus/disabled;
- `KashaIconButton` — компактное действие;
- `KashaQuietButton` — тихое текстовое действие;
- `KashaField` — общее поле ввода/read-only;
- `KashaNoteText` — единый редактор заметки или текста задачи; отдельного title-поля нет;
- `KashaSwitchRow`;
- `KashaSlider`;
- `KashaPanel`;
- `KashaListCard`;
- `KashaNavigationItem`;
- `KashaWaveform`;
- `KashaProcessingRing`;
- `KashaCaptureMark`;
- `KashaSortBar`;
- `KashaReorderableList` — manual order через long-press + drag.

Текущий `KashaSortBar` — segmented control через `selectableGroup` + `selectable`. В целевом дизайне он заменяется одной компактной командой с текущим режимом и выбором в sheet/popover; четыре режима и семантика выбранного значения сохраняются.

## Заметка

Заметка визуально является одним текстовым документом. Первая непустая строка автоматически используется как название в списках и header, остальные строки — тело. Пользователь не синхронизирует два отдельных поля и не редактирует title отдельно.

## Иконки

`Kasha Icons` — собственный небольшой набор на Compose Canvas. Геометрия и короткий motion живут в одном файле `ui/KashaIcons.kt`. Сторонняя библиотека иконок не является частью runtime.

Motion запускается на hover/press/focus/drag или явном изменении состояния. Движение короткое и функциональное: send двигается по направлению отправки, gear поворачивается, стрелки смещаются в направлении перехода и т. п.

## Типографика и тема

- **Geologica Variable** — целевой UI и display; текущий Commissioner подлежит миграции вместе с pipeline шрифтов;
- точные настройки существующих осей Geologica, веса и размеры задаются в [основах](design/02-foundations.md) и [JSON-токенах](design/tokens/design-tokens.json);
- сборка автоматически проверяет кириллицу и все восемь языков Kasha, включая казахские `Ә Ғ Қ Ң Ө Ұ Ү Һ`;
- основной body 16/23sp, body large 17/25sp;
- нижняя навигация и вспомогательные подписи не меньше 11sp;
- display scale ограничен 46sp;
- light — почти белый с едва жёлтым/бумажным смещением;
- dark — почти чёрный с едва коричневым смещением.

## Принцип

Экран отвечает за продуктовый сценарий. Геометрия контролов, motion, typography, цвета, interaction states и iconography принадлежат Kasha UI. Если одно и то же решение появляется на двух экранах — его нужно поднимать в Kasha UI, а не копировать.
