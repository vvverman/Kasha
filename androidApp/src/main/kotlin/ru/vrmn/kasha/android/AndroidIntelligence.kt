package ru.vrmn.kasha.android

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import brain.domain.NoteText
import brain.model.Project
import brain.studio.Intelligence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android-native AI adapter. STT is strictly on-device and never falls back to network. */
internal class AndroidIntelligence(private val context: Context) : Intelligence {
    override val simulated: Boolean = false

    fun supportsOnDevice(): Boolean = Build.VERSION.SDK_INT >= 33 &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override suspend fun transcribe(file: String, language: String, example: String): String {
        check(supportsOnDevice()) { "onDeviceSpeechUnavailable" }
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val pfd = ParcelFileDescriptor.open(File(file), ParcelFileDescriptor.MODE_READ_ONLY)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, normalizeLocale(language))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onError(error: Int) {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("speechRecognitionFailed:$error")
                        )
                        pfd.close()
                        recognizer.destroy()
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (continuation.isActive) {
                            if (text.isNotBlank()) continuation.resume(text)
                            else continuation.resumeWithException(IllegalStateException("emptyTranscription"))
                        }
                        pfd.close()
                        recognizer.destroy()
                    }
                })
                continuation.invokeOnCancellation {
                    recognizer.cancel()
                    recognizer.destroy()
                    pfd.close()
                }
                recognizer.startListening(intent)
            }
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
            .findAll(value.lowercase(Locale.ROOT))
            .map { it.value.take(7) }
            .toSet()
        val source = words(text)
        return projects.associate { project ->
            val titleHits = words(project.title).count { it in source }
            val detailHits = words(project.description + " " + project.instruction).count { it in source }
            project.id to (titleHits * 3 + detailHits).coerceIn(0, 4)
        }
    }

    private fun normalizeLocale(language: String): String = when (language.substringBefore('-').lowercase(Locale.ROOT)) {
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

    companion object { const val SAMPLE_RATE = 16_000 }
}
