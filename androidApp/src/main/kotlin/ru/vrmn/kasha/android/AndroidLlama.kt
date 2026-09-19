package ru.vrmn.kasha.android

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Файловый llama.cpp; без сети, микрофона, хранения текста и правил заметок. */
internal class AndroidLlama(private val packages: AndroidWhisperPackages) {
    private val execution = Mutex()

    suspend fun generate(engine: String, prompt: String, tokens: Int): String =
        execution.withLock {
            check(AndroidLlamaNative.available) { "androidAiNotConfigured" }
            packages.withModel(engine) { model ->
                withContext(Dispatchers.IO) {
                    try {
                        val bytes = AndroidLlamaNative.generate(model.absolutePath,
                            prompt.toByteArray(Charsets.UTF_8),
                            tokens, Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                            AndroidLlamaCancellation(currentCoroutineContext()[Job]))
                        currentCoroutineContext().ensureActive()
                        bytes.toString(Charsets.UTF_8).trim().also {
                            check(it.isNotBlank()) { "aiUnavailable" }
                        }
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        throw error
                    }
                }
            }
        }
}
