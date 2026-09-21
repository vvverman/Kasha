package ru.vrmn.kasha.android

import kotlinx.coroutines.Job

internal object AndroidLlamaNative {
    val available: Boolean by lazy {
        try { System.loadLibrary("kasha_llama"); true }
        catch (_: UnsatisfiedLinkError) { false }
        catch (_: SecurityException) { false }
    }

    external fun generate(model: String, prompt: ByteArray,
        tokens: Int, threads: Int, signal: AndroidLlamaCancellation): ByteArray

    external fun embed(model: String, text: ByteArray,
        threads: Int, signal: AndroidLlamaCancellation): FloatArray
}

/** Читается JNI также во время загрузки весов и вычисления токена. */
internal class AndroidLlamaCancellation(private val job: Job?) {
    fun isCancelled(): Boolean = job?.isActive == false
}
