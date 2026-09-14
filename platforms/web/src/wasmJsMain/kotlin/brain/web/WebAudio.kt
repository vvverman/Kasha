@file:OptIn(ExperimentalWasmJsInterop::class)
package brain.web

import brain.domain.AudioGateway
import brain.studio.AudioTelemetry
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlin.js.*

class WebAudioGateway(private val baseUrl:String):AudioGateway {
    override suspend fun playCapture(captureId:String,compact:Boolean,fromSeconds:Double,rate:Double){
        checked(play("$baseUrl/api/captures/$captureId/audio?compact=$compact".toJsString(),fromSeconds,rate).await())
    }
    override suspend fun pause(){checked(pauseAudio())}
    override suspend fun resume(){checked(resumeAudio().await())}
    override fun telemetry():AudioTelemetry=Json.decodeFromString(audioState().toString())
    override fun stop(){stopAudio()}
    private fun checked(value:JsString){val text=value.toString();check(!text.startsWith("ERROR:")){"audioFailed"}}
}
private fun play(url:JsString,from:Double,rate:Double):Promise<JsString> = js("globalThis.kashaPlatform.play(url, from, rate)")
private fun stopAudio():Unit = js("globalThis.kashaPlatform.stopAudio()")
private fun pauseAudio():JsString = js("globalThis.kashaPlatform.pauseAudio()")
private fun resumeAudio():Promise<JsString> = js("globalThis.kashaPlatform.resumeAudio()")
private fun audioState():JsString = js("JSON.stringify(globalThis.kashaPlatform.audioState())")
