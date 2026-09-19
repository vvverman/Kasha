@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.studio.AiRole
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.NSBundle
import platform.posix.*

/** Библиотеки загружаются только из подписанного app bundle, не из каталога скачанных моделей. */
internal class IosNativeModels(
    private val frameworks: String = NSBundle.mainBundle.privateFrameworksPath.orEmpty(),
) {
    private class Library(val name: String, root: String, symbol: String) {
        // Handle сохраняется на весь процесс: повторный dlclose при активном callback недопустим.
        val handle = dlopen("$root/$name.framework/$name", RTLD_NOW or RTLD_LOCAL)
        val function = handle?.let { dlsym(it, symbol) }
    }
    private val speech by lazy { Library("KashaWhisper", frameworks, "kasha_whisper_run") }
    private val text by lazy { Library("KashaLlama", frameworks, "kasha_llama_run") }
    fun available(role: AiRole): Boolean =
        (if (role == AiRole.SPEECH_TO_TEXT) speech else text).function != null

    suspend fun transcribe(model: String, source: String, language: String): String = withContext(Dispatchers.Default) {
        val function = speech.function?.reinterpret<CFunction<(
            CPointer<ByteVar>?, CPointer<ByteVar>?, CPointer<ByteVar>?, Int,
            CPointer<CFunction<(COpaquePointer?) -> Int>>?, COpaquePointer?, CPointer<IntVar>?
        ) -> CPointer<ByteVar>?>>() ?: error("runtimeUnavailable")
        call { ref, status -> memScoped {
            function(model.cstr.ptr, source.cstr.ptr, language.cstr.ptr, 4, abort, ref, status)
        } }
    }

    suspend fun generate(model: String, prompt: String, tokens: Int): String = withContext(Dispatchers.Default) {
        require('\u0000' !in prompt) { "aiUnavailable" }
        val function = text.function?.reinterpret<CFunction<(
            CPointer<ByteVar>?, CPointer<ByteVar>?, Int, Int,
            CPointer<CFunction<(COpaquePointer?) -> Int>>?, COpaquePointer?, CPointer<IntVar>?
        ) -> CPointer<ByteVar>?>>() ?: error("runtimeUnavailable")
        call { ref, status -> memScoped {
            function(model.cstr.ptr, prompt.cstr.ptr, tokens, 4, abort, ref, status)
        } }
    }

    private suspend fun call(invoke: (COpaquePointer, CPointer<IntVar>) -> CPointer<ByteVar>?): String {
        val job = currentCoroutineContext().job
        val ref = StableRef.create(job)
        try {
            job.ensureActive()
            return memScoped {
                val status = alloc<IntVar>(); status.value = 1
                val result = invoke(ref.asCPointer(), status.ptr) ?: error("aiUnavailable")
                try {
                    job.ensureActive()
                    val value = result.toKString()
                    check(status.value == 0) { value }
                    value
                } finally { free(result) }
            }
        } finally { ref.dispose() }
    }
    private companion object {
        val abort = staticCFunction { ref: COpaquePointer? ->
            if (ref?.asStableRef<Job>()?.get()?.isActive == false) 1 else 0
        }
    }
}
