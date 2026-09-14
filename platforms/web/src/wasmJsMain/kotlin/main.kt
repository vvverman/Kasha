import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import brain.studio.*
import brain.web.*

@OptIn(ExperimentalComposeUiApi::class)
fun main(){
    val base=runtimeBaseUrl()
    val state=StudioState(WebBrainRepository(base),BrowserRecorder(base),WebAudioGateway(base),browserLanguage())
    ComposeViewport(viewportContainerId="webApp"){StudioApp(state)}
}
