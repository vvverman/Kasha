package ru.vrmn.kasha.android

import kotlinx.coroutines.Job

/** Вызывается из native-потоков; без системного UI и пользовательских данных в логах. */
internal class AndroidWhisperCancellation(private val job: Job?) {
    fun isCancelled(): Boolean = job?.isActive == false
}

internal object AndroidWhisperNative {
    val available: Boolean = try { System.loadLibrary("kasha_whisper"); true } catch (_: UnsatisfiedLinkError) { false }
    external fun transcribe(model: String, pcm: String, rate: Int, channels: Int,
        language: String, threads: Int, signal: AndroidWhisperCancellation): ByteArray
}
