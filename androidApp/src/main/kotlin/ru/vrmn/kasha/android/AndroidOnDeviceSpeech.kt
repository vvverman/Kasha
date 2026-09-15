package ru.vrmn.kasha.android

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import brain.ai.BuiltInAi
import brain.studio.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Только on-device service с подтверждённым файловым/сегментированным вводом. */
internal class AndroidOnDeviceSpeech(context: Context) {
    private val context = context.applicationContext
    private val permissions = AndroidPermissions(this.context)

    fun available(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun supports(language: String): Boolean = capability(language).executable

    /** Проверка не вызывает startListening, сеть или системный permission-dialog. */
    suspend fun capability(language: String): AiRoleCapability {
        val role = AiRole.SPEECH_TO_TEXT
        val id = BuiltInAi.ANDROID_SPEECH
        if (!available()) return AiReadiness.blocked(role, id, "platformUnavailable")
        val permission = permissions.status(DevicePermissionKind.MICROPHONE)
        if (permission != DevicePermissionState.GRANTED)
            return AiReadiness.native(role, id, true, true, permission, DevicePermissionKind.MICROPHONE, true)
        return withContext(Dispatchers.Main.immediate) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                recognizer.setRecognitionListener(Events())
                checkSupport(recognizer, request(language, pipe[0], 16000, 1), language, detailed = true)
                AiRoleCapability(role, id, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AiReadiness.blocked(role, id, if (error.message == "languageUnsupported") "languageUnsupported" else "capabilityCheckFailed")
            } finally {
                pipe.forEach { runCatching { it.close() } }
                recognizer.destroy()
            }
        }
    }

    suspend fun transcribe(source: String, language: String): String {
        check(available()) { "androidAiNotConfigured" }
        val pcm = decodeSpeechSource(File(source), context.cacheDir)
        try {
            return withContext(Dispatchers.Main.immediate) {
                coroutineScope {
                    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    val pipe = ParcelFileDescriptor.createPipe()
                    val result = CompletableDeferred<String>()
                    val segments = mutableListOf<String>()
                    var writer: Job? = null
                    try {
                        recognizer.setRecognitionListener(object : Events() {
                            override fun onSegmentResults(results: Bundle) {
                                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()?.trim()?.takeIf(String::isNotBlank)?.let(segments::add)
                            }
                            override fun onEndOfSegmentedSession() {
                                val text = segments.joinToString("\n").trim()
                                if (text.isNotEmpty()) result.complete(text)
                                else result.completeExceptionally(IllegalStateException("emptyTranscription"))
                            }
                            override fun onResults(results: Bundle) {
                                // Нельзя выдать первый фрагмент за весь файл при проигнорированном segmented flag.
                                result.completeExceptionally(IllegalStateException("androidAiNotConfigured"))
                            }
                            override fun onError(error: Int) {
                                val code = when (error) {
                                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "androidAiNotConfigured"
                                    else -> "speechRecognitionFailed:$error"
                                }
                                result.completeExceptionally(IllegalStateException(code))
                            }
                        })
                        val intent = request(language, pipe[0], pcm.sampleRate, pcm.channels)
                        checkSupport(recognizer, intent, language)
                        recognizer.startListening(intent)
                        writer = launch(Dispatchers.IO) {
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { sink ->
                                pcm.file.inputStream().use { it.copyTo(sink) }
                            }
                        }
                        result.await()
                    } finally {
                        pipe.forEach { runCatching { it.close() } }
                        writer?.cancel()
                        runCatching { recognizer.cancel() }
                        recognizer.destroy()
                    }
                }
            }
        } finally { pcm.file.delete() }
    }

    private fun request(language: String, source: ParcelFileDescriptor, rate: Int, channels: Int) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.replace('_', '-'))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, channels)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

    private suspend fun checkSupport(recognizer: SpeechRecognizer, request: Intent, language: String, detailed: Boolean = false) =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                recognizer.checkRecognitionSupport(request, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (!continuation.isActive) return
                        val wanted = language.replace('_', '-').substringBefore('-').lowercase()
                        val installed = support.installedOnDeviceLanguages.any {
                            it.replace('_', '-').substringBefore('-').lowercase() == wanted
                        }
                        if (installed) continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException(if (detailed) "languageUnsupported" else "androidAiNotConfigured"))
                    }
                    override fun onError(error: Int) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(
                            if (detailed && error in listOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
                                "languageUnsupported" else "androidAiNotConfigured"))
                    }
                })
            }
        } ?: error("androidAiNotConfigured")

    private open class Events : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) = Unit
        override fun onResults(results: Bundle) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
