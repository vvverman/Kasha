@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.NoteText
import brain.model.Project
import brain.studio.Intelligence
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSLocale
import platform.Foundation.NSURL
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechURLRecognitionRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IosOnDeviceIntelligence : Intelligence {
    override val simulated: Boolean = false

    fun supportsOnDevice(language: String): Boolean = recognizer(language)?.supportsOnDeviceRecognition == true

    override suspend fun transcribe(file: String, language: String, example: String): String {
        checkSpeechPermission()
        val recognizer = recognizer(language) ?: error("onDeviceSpeechUnavailable")
        check(recognizer.supportsOnDeviceRecognition) { "onDeviceSpeechUnavailable" }
        check(recognizer.available) { "onDeviceSpeechUnavailable" }

        val request = SFSpeechURLRecognitionRequest(NSURL.fileURLWithPath(file)).apply {
            requiresOnDeviceRecognition = true
            shouldReportPartialResults = false
            addsPunctuation = true
        }

        return suspendCancellableCoroutine { continuation ->
            val task = recognizer.recognitionTaskWithRequest(request) { result, error ->
                when {
                    result?.final == true && continuation.isActive -> {
                        val text = result.bestTranscription.formattedString.trim()
                        if (text.isNotBlank()) continuation.resume(text)
                        else continuation.resumeWithException(IllegalStateException("emptyTranscription"))
                    }
                    error != null && continuation.isActive ->
                        continuation.resumeWithException(IllegalStateException("speechRecognitionFailed: ${error.localizedDescription}"))
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
        }
    }

    override suspend fun title(text: String, language: String) = brain.ai.BuiltInText.title(text, language)
    override suspend fun tidy(text: String, language: String) = brain.ai.BuiltInText.tidy(text, language)
    override suspend fun rank(text: String, projects: List<Project>, language: String) =
        brain.ai.BuiltInText.rank(text, projects, language)

    private fun recognizer(language: String): SFSpeechRecognizer? {
        val localeId = when (language.substringBefore('-').substringBefore('_').lowercase()) {
            "ru" -> "ru-RU"
            "en" -> "en-US"
            "es" -> "es-ES"
            "fr" -> "fr-FR"
            "de" -> "de-DE"
            "uk" -> "uk-UA"
            "be" -> "be-BY"
            "kk" -> "kk-KZ"
            else -> language
        }
        return SFSpeechRecognizer(NSLocale(localeIdentifier = localeId))
    }

    private suspend fun checkSpeechPermission() {
        if (SFSpeechRecognizer.authorizationStatus().value == SPEECH_AUTHORIZED) return
        val granted = suspendCancellableCoroutine { continuation ->
            SFSpeechRecognizer.requestAuthorization { status ->
                if (continuation.isActive) continuation.resume(status.value == SPEECH_AUTHORIZED)
            }
        }
        check(granted) { "speechPermissionDenied" }
    }

    private companion object {
        const val SPEECH_AUTHORIZED = 3L
    }
}
