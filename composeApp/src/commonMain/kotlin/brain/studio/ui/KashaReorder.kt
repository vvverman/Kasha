package brain.studio

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Вне drag отображаются только актуальные данные Core, в том числе после ошибки сохранения. */
@Composable
fun <T> KashaReorderableList(
    items: List<T>,
    key: (T) -> String,
    manual: Boolean,
    onManualOrder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(bottom = 20.dp),
    moveUpLabel: String? = null,
    moveDownLabel: String? = null,
    listState: LazyListState? = null,
    enabled: Boolean = true,
    itemContent: @Composable (T, Boolean) -> Unit,
) {
    val state = listState ?: rememberLazyListState()
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(items)
    val latestKey by rememberUpdatedState(key)
    val saveOrder by rememberUpdatedState(onManualOrder)
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var startOrder by remember { mutableStateOf<List<String>?>(null) }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggedCenter by remember { mutableStateOf(0f) }
    val keys = items.map(key)
    val editable = manual && enabled

    fun resetPreview() { preview = null; startOrder = null; draggingKey = null }

    LaunchedEffect(keys, editable) {
        if (!editable || (startOrder != null && startOrder != keys)) resetPreview()
    }

    fun commitMove(id: String, delta: Int): Boolean {
        if (!editable || draggingKey != null) return false
        val next = movedItemKeys(latestItems.map(latestKey), id, delta) ?: return false
        // Не оставляем неподтверждённый локальный порядок после отказа Core/диска.
        saveOrder(next)
        return true
    }

    val dragModifier = if (!editable) Modifier else Modifier.pointerInput(editable, keys) {
        val edge = 56.dp.toPx()
        val scrollStep = 28.dp.toPx()
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val hit = state.layoutInfo.visibleItemsInfo.firstOrNull {
                    offset.y.toInt() in it.offset until (it.offset + it.size)
                }
                val current = latestItems.map(latestKey)
                if (hit != null && hit.index in current.indices) {
                    startOrder = current
                    preview = current
                    draggingKey = current[hit.index]
                    draggedCenter = hit.offset + hit.size / 2f
                }
            },
            onDrag = { change, amount ->
                val active = draggingKey ?: return@detectDragGesturesAfterLongPress
                if (startOrder != latestItems.map(latestKey)) { resetPreview(); return@detectDragGesturesAfterLongPress }
                change.consume()
                draggedCenter += amount.y
                val info = state.layoutInfo
                when {
                    draggedCenter < info.viewportStartOffset + edge -> scope.launch { state.scrollBy(-scrollStep) }
                    draggedCenter > info.viewportEndOffset - edge -> scope.launch { state.scrollBy(scrollStep) }
                }
                val order = preview ?: return@detectDragGesturesAfterLongPress
                val from = order.indexOf(active)
                val to = info.visibleItemsInfo.firstOrNull {
                    draggedCenter.toInt() in it.offset until (it.offset + it.size)
                }?.index
                if (from >= 0 && to != null && to in order.indices && from != to) {
                    preview = order.toMutableList().apply { add(to, removeAt(from)) }
                }
            },
            onDragEnd = {
                val next = reorderCommit(startOrder, latestItems.map(latestKey), preview)
                resetPreview()
                if (next != null) saveOrder(next)
            },
            onDragCancel = ::resetPreview,
        )
    }

    LazyColumn(state = state, modifier = modifier.then(dragModifier),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing), contentPadding = contentPadding) {
        items(reorderPreview(items, preview, key), key = key) { item ->
            val id = key(item)
            val index = keys.indexOf(id)
            val accessibility = if (!editable) Modifier else Modifier
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) false
                    else when (event.key) {
                        Key.DirectionUp -> commitMove(id, -1)
                        Key.DirectionDown -> commitMove(id, 1)
                        else -> false
                    }
                }
                .semantics {
                    customActions = buildList {
                        if (moveUpLabel != null && index > 0) add(CustomAccessibilityAction(moveUpLabel) { commitMove(id, -1) })
                        if (moveDownLabel != null && index >= 0 && index < keys.lastIndex) add(CustomAccessibilityAction(moveDownLabel) { commitMove(id, 1) })
                    }
                }
            Box(accessibility) { itemContent(item, id == draggingKey) }
        }
    }
}
