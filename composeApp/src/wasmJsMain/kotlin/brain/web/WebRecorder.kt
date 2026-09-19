@file:OptIn(ExperimentalWasmJsInterop::class)
package brain.web

import brain.domain.PendingRecording
import brain.domain.RecorderPermission
import brain.domain.RecorderSessionGateway
import brain.domain.RecorderSessionState
import brain.model.Capture
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlin.js.*

fun runtimeBaseUrl():String=platformBase().toString()
fun browserLanguage():String=platformLanguage().toString()
private fun platformBase():JsString=js("globalThis.kashaPlatform.baseUrl()")
private fun platformLanguage():JsString=js("navigator.language || 'en'")

/** Только browser API; запуск, подтверждения и сохранение назначения принадлежат Core. */
class BrowserRecorder(private val baseUrl:String):RecorderSessionGateway {
    private val json=Json{ignoreUnknownKeys=true}
    override suspend fun permission():RecorderPermission =
        if(consent().await().toString()=="true") RecorderPermission.GRANTED else RecorderPermission.NOT_DETERMINED
    override fun sessionState():RecorderSessionState=json.decodeFromString(recorderSession().toString())
    override suspend fun pendingRecordings():List<PendingRecording> =
        json.decodeFromString(checked(pendingSessions().await()))
    override fun level()=recorderLevel().toFloat()
    override suspend fun start(){checked(startRecorder().await())}
    override suspend fun pause(){checked(pauseRecorder())}
    override suspend fun resume(){checked(resumeRecorder())}
    override suspend fun stopAndUpload():Capture=json.decodeFromString(checked(stopRecorder(baseUrl.toJsString()).await()))
    override suspend fun recoverPending(pendingId:String):Capture =
        json.decodeFromString(checked(recoverRecorder(baseUrl.toJsString(),pendingId.toJsString()).await()))
    override suspend fun cancelActive(sessionId:String){checked(cancelRecorder(sessionId.toJsString()).await())}
    override suspend fun discardPending(pendingId:String){checked(discardRecorder(pendingId.toJsString()).await())}
    private fun checked(value:JsString):String=value.toString().also{check(!it.startsWith("ERROR:")){"audioFailed"}}
}
private fun consent():Promise<JsString> = js("globalThis.kashaPlatform.consent().then(v => String(v))")
private fun pendingSessions():Promise<JsString> = js("globalThis.kashaPlatform.pendingRecordings()")
private fun recorderSession():JsString=js("globalThis.kashaPlatform.sessionState()")
private fun recorderLevel():Double=js("globalThis.kashaPlatform.level()")
private fun startRecorder():Promise<JsString> = js("globalThis.kashaPlatform.start()")
private fun pauseRecorder():JsString=js("globalThis.kashaPlatform.pause()")
private fun resumeRecorder():JsString=js("globalThis.kashaPlatform.resume()")
private fun stopRecorder(base:JsString):Promise<JsString> = js("globalThis.kashaPlatform.stop(base)")
private fun recoverRecorder(base:JsString,id:JsString):Promise<JsString> = js("globalThis.kashaPlatform.recover(base,id)")
private fun cancelRecorder(id:JsString):Promise<JsString> = js("globalThis.kashaPlatform.cancel(id)")
private fun discardRecorder(id:JsString):Promise<JsString> = js("globalThis.kashaPlatform.discardPending(id)")
