package ru.vrmn.kasha.android

import android.content.Context
import kotlinx.coroutines.*
import java.io.File

/** Исполняет выбранную локальную модель, не открывая микрофон или системный распознаватель. */
internal class AndroidWhisper(private val context: Context, private val packages: AndroidWhisperPackages) {
    suspend fun transcribe(engine: String, source: String, language: String): String = packages.withModel(engine) { model ->
        check(AndroidWhisperNative.available) { "androidAiNotConfigured" }
        val pcm = decodeSpeechSource(File(source), context.cacheDir)
        try {
            withContext(Dispatchers.IO) {
                val signal = AndroidWhisperCancellation(currentCoroutineContext()[Job])
                val bytes = AndroidWhisperNative.transcribe(model.absolutePath, pcm.file.absolutePath,
                    pcm.sampleRate, pcm.channels, language.substringBefore('-').substringBefore('_'),
                    Runtime.getRuntime().availableProcessors().coerceIn(1, 4), signal)
                currentCoroutineContext().ensureActive()
                bytes.toString(Charsets.UTF_8).trim().also { check(it.isNotBlank()) { "emptyTranscription" } }
            }
        } finally { pcm.file.delete() }
    }
}

