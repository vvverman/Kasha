package brain.studio

import androidx.compose.foundation.Box
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Long-press + drag используется только для режима MANUAL. */
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
    itemContent: @Composable (T, Boolean) -> Unit,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val local = remember { mutableStateListOf<T>() }
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var draggedCenter by remember { mutableStateOf(0f) }

    LaunchedEffect(items.map(key)) {
        if (draggingKey == null) {
            local.clear()
            local.addAll(items)
        }
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in local.indices || to !in local.indices) return
        val item = local.removeAt(from)
        local.add(to, item)
    }

    fun commitMove(from: Int, to: Int): Boolean {
        if (!manual || from == to || from !in local.indices || to !in local.indices) return false
        move(from, to)
        onManualOrder(local.map(key))
        return true
    }

    fun restoreAuthoritativeOrder() {
        local.clear()
        local.addAll(items)
    }

    val dragModifier = if (!manual) Modifier else Modifier.pointerInput(local.size) {
        val edge = 56.dp.toPx()
        val scrollStep = 28.dp.toPx()
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { offset.y.toInt() in it.offset..(it.offset + it.size) }
                if (hit != null && hit.index in local.indices) {
                    draggingKey = key(local[hit.index])
                    draggedCenter = hit.offset + hit.size / 2f
                }
            },
            onDrag = { change, dragAmount ->
                val active = draggingKey ?: return@detectDragGesturesAfterLongPress
                change.consume()
                draggedCenter += dragAmount.y

                val info = state.layoutInfo
                val start = info.viewportStartOffset.toFloat()
                val end = info.viewportEndOffset.toFloat()
                when {
                    draggedCenter < start + edge -> scope.launch { state.scrollBy(-scrollStep) }
                    draggedCenter > end - edge -> scope.launch { state.scrollBy(scrollStep) }
                }

                val from = local.indexOfFirst { key(it) == active }
                val to = info.visibleItemsInfo.firstOrNull {
                    draggedCenter.toInt() in it.offset..(it.offset + it.size)
                }?.index
                if (from >= 0 && to != null && to in local.indices && to != from) move(from, to)
            },
            onDragEnd = {
                if (draggingKey != null) onManualOrder(local.map(key))
                draggingKey = null
            },
            onDragCancel = {
                restoreAuthoritativeOrder()
                draggingKey = null
            },
        )
    }

    LazyColumn(
        state = state,
        modifier = modifier.then(dragModifier),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
        contentPadding = contentPadding,
    ) {
        items(local, key = key) { item ->
            val itemKey = key(item)
            val index = local.indexOfFirst { key(it) == itemKey }
            val accessibility = if (!manual || (moveUpLabel == null && moveDownLabel == null)) Modifier else Modifier.semantics {
                customActions = buildList {
                    if (moveUpLabel != null && index > 0) {
                        add(CustomAccessibilityAction(moveUpLabel) { commitMove(index, index - 1) })
                    }
                    if (moveDownLabel != null && index >= 0 && index < local.lastIndex) {
                        add(CustomAccessibilityAction(moveDownLabel) { commitMove(index, index + 1) })
                    }
                }
            }
            Box(accessibility) {
                itemContent(item, itemKey == draggingKey)
            }
        }
    }
}
