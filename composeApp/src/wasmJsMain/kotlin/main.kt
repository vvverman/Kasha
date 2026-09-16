import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import brain.studio.*
import brain.web.*

@OptIn(ExperimentalComposeUiApi::class)
fun main(){
    // CMP-10732: official workaround for the Web snapshot-cache crash in 1.13.0-alpha01.
    // Keep the shared UI unchanged; remove only after a verified upstream fix.
    ComposeUiFlags.useSnapshotCache = false
    val base=runtimeBaseUrl()
    val state=StudioState(WebBrainRepository(base),BrowserRecorder(base),WebAudioGateway(base),browserLanguage(),WebRuntimeReminders(base))
    ComposeViewport(viewportContainerId="webApp"){StudioApp(state)}
}
