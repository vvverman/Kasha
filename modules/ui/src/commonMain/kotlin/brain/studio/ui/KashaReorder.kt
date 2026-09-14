package brain.studio

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    itemContent: @Composable (T, Boolean) -> Unit,
) {
    val state = rememberLazyListState()
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

    fun restoreAuthoritativeOrder() {
        local.clear()
        local.addAll(items)
    }

    val dragModifier = if (!manual) Modifier else Modifier.pointerInput(local.size) {
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
                val from = local.indexOfFirst { key(it) == active }
                val to = state.layoutInfo.visibleItemsInfo.firstOrNull {
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
        items(local, key = key) { item -> itemContent(item, key(item) == draggingKey) }
    }
}
