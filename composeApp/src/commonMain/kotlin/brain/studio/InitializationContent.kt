package brain.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Ошибка чтения не скрывается за сплешем и не подменяет данные пустой базой. */
@Composable
internal fun InitializationContent(state: StudioState, onRetry: () -> Unit) {
    val error = state.error
    if (error == null) {
        KashaSplash()
        return
    }
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text(state.tr(error), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(24.dp))
            Action(state.tr("retry"), onRetry, primary = true,
                enabled = !state.controlBusy, modifier = Modifier.fillMaxWidth())
        }
    }
}
