@file:OptIn(ExperimentalWasmJsInterop::class)
package brain.web

import brain.domain.PlaybackPhase
import brain.domain.PlaybackSessionGateway
import brain.domain.PlaybackSessionState
import brain.studio.AudioTelemetry
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlin.js.*

class WebAudioGateway(private val baseUrl:String):PlaybackSessionGateway {
    private var sourceId:String?=null

    override suspend fun playCapture(captureId:String,compact:Boolean,fromSeconds:Double,rate:Double){
        val previous=sourceId
        sourceId=captureId
        try{checked(play("$baseUrl/api/captures/$captureId/audio?compact=$compact".toJsString(),fromSeconds,rate).await())}
        catch(e:Throwable){sourceId=previous;throw e}
    }
    override suspend fun pause(){checked(pauseAudio())}
    override suspend fun resume(){checked(resumeAudio().await())}
    override fun playbackState():PlaybackSessionState{
        val legacy=Json.decodeFromString<AudioTelemetry>(audioState().toString())
        return PlaybackSessionState(
            phase=PlaybackPhase.fromLegacy(legacy.phase),
            sourceId=sourceId,
            positionSeconds=legacy.position,
            durationSeconds=legacy.duration,
            level=legacy.level,
        )
    }
    override suspend fun seekTo(positionSeconds:Double):PlaybackSessionState{
        val target=playbackState().seekTarget(positionSeconds)?:error("audioFailed")
        checked(seekAudio(target))
        return playbackState()
    }
    override fun telemetry():AudioTelemetry{
        val state=playbackState()
        return AudioTelemetry(state.phase.legacyValue,state.positionSeconds,state.durationSeconds,state.level)
    }
    override fun stop(){stopAudio()}
    private fun checked(value:JsString){val text=value.toString();check(!text.startsWith("ERROR:")){"audioFailed"}}
}
private fun play(url:JsString,from:Double,rate:Double):Promise<JsString> = js("globalThis.kashaPlatform.play(url, from, rate)")
private fun stopAudio():Unit = js("globalThis.kashaPlatform.stopAudio()")
private fun pauseAudio():JsString = js("globalThis.kashaPlatform.pauseAudio()")
private fun resumeAudio():Promise<JsString> = js("globalThis.kashaPlatform.resumeAudio()")
private fun seekAudio(seconds:Double):JsString = js("globalThis.kashaPlatform.seekAudio(seconds)")
private fun audioState():JsString = js("JSON.stringify(globalThis.kashaPlatform.audioState())")
