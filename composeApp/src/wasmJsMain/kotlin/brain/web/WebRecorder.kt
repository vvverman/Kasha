@file:OptIn(ExperimentalWasmJsInterop::class)
package brain.web

import brain.domain.RecorderGateway
import brain.model.Capture
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlin.js.*

fun runtimeBaseUrl():String=platformBase().toString()
fun browserLanguage():String=platformLanguage().toString()
private fun platformBase():JsString=js("globalThis.kashaPlatform.baseUrl()")
private fun platformLanguage():JsString=js("navigator.language || 'en'")
class BrowserRecorder(private val baseUrl:String):RecorderGateway {
    private val json=Json{ignoreUnknownKeys=true}
    override suspend fun hasConsent()=consent()
    override suspend fun hasPending()=pending().await().toString()=="true"
    override fun phase()=recorderPhase().toString()
    override fun level()=recorderLevel().toFloat()
    override suspend fun start(){checked(startRecorder().await())}
    override suspend fun pause(){checked(pauseRecorder())}
    override suspend fun resume(){checked(resumeRecorder())}
    override suspend fun stopAndUpload():Capture=json.decodeFromString(checked(stopRecorder(baseUrl.toJsString()).await()))
    override suspend fun recoverPending():Capture=json.decodeFromString(checked(recoverRecorder(baseUrl.toJsString()).await()))
    private fun checked(value:JsString):String=value.toString().also{check(!it.startsWith("ERROR:")){"audioFailed"}}
}
private fun consent():Boolean=js("globalThis.kashaPlatform.consent()")
private fun pending():Promise<JsString> = js("globalThis.kashaPlatform.pending().then(v => String(v))")
private fun recorderPhase():JsString=js("globalThis.kashaPlatform.phase()")
private fun recorderLevel():Double=js("globalThis.kashaPlatform.level()")
private fun startRecorder():Promise<JsString> = js("globalThis.kashaPlatform.start()")
private fun pauseRecorder():JsString=js("globalThis.kashaPlatform.pause()")
private fun resumeRecorder():JsString=js("globalThis.kashaPlatform.resume()")
private fun stopRecorder(base:JsString):Promise<JsString> = js("globalThis.kashaPlatform.stop(base)")
private fun recoverRecorder(base:JsString):Promise<JsString> = js("globalThis.kashaPlatform.recover(base)")
