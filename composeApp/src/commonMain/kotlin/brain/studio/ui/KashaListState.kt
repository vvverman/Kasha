package brain.studio

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * UI-only scroll memory. It deliberately stores no domain data and is shared by every platform.
 * Returning from detail/editor screens restores the list context that the user left.
 */
private object KashaListScrollMemory {
    val positions = mutableMapOf<String, Pair<Int, Int>>()
}

@Composable
fun rememberKashaListState(key: String): LazyListState {
    val saved = KashaListScrollMemory.positions[key] ?: (0 to 0)
    val state = rememberLazyListState(saved.first, saved.second)
    DisposableEffect(key, state) {
        onDispose {
            KashaListScrollMemory.positions[key] =
                state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        }
    }
    return state
}
