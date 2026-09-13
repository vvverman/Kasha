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

    override suspend fun title(text: String, language: String): String = NoteText.title(text)

    override suspend fun tidy(text: String, language: String): String {
        var result = text.trim()
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
        val replacements = mapOf(
            "какая-то фигня" to "проблема",
            "эта хрень" to "эта функция",
            "фигня" to "проблема",
            "хрень" to "проблема",
            "пиздец" to "серьёзная проблема",
            "crap" to "problem",
        )
        replacements.forEach { (from, to) ->
            result = result.replace(
                Regex("(?i)(?<![\\p{L}])${Regex.escape(from)}(?![\\p{L}])"),
                to,
            )
        }
        return result
    }

    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
        fun words(value: String): Set<String> = Regex("[\\p{L}\\p{N}]{3,}")
            .findAll(value.lowercase())
            .map { it.value.take(7) }
            .toSet()
        val source = words(text)
        return projects.associate { project ->
            val titleHits = words(project.title).count { it in source }
            val detailHits = words(project.description + " " + project.instruction).count { it in source }
            project.id to (titleHits * 3 + detailHits).coerceIn(0, 4)
        }
    }

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
