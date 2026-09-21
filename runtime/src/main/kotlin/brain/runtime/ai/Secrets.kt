package brain.runtime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

interface SecureSecretStore {
    val available: Boolean
    suspend fun get(id: String): String?
    suspend fun put(id: String, value: String)
    suspend fun remove(id: String)
}

object UnsupportedSecretStore : SecureSecretStore {
    override val available = false
    override suspend fun get(id: String): String? = null
    override suspend fun put(id: String, value: String) = error("Защищённое хранилище недоступно")
    override suspend fun remove(id: String) = Unit
}

/** macOS Keychain. Ключ не пишется в файл, prefs или stdout. */
class MacKeychainSecretStore(
    private val service: String = "ru.vrmn.kasha.ai",
) : SecureSecretStore {
    override val available: Boolean get() = System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")

    override suspend fun get(id: String): String? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        val result = runSecretProcess(
            listOf("/usr/bin/security", "find-generic-password", "-s", service, "-a", id, "-w"),
            null,
            allowFailure = true,
        )
        result.takeIf { it.first == 0 }?.second?.trimEnd()
    }

    override suspend fun put(id: String, value: String) = withContext(Dispatchers.IO) {
        check(available)
        require(value.isNotBlank())
        // security(1) не предоставляет stdin-вариант для add-generic-password. Аргументы никогда
        // не логируются Kasha; сам секрет сразу сохраняется Keychain и нигде больше не живёт.
        val result = runSecretProcess(
            listOf("/usr/bin/security", "add-generic-password", "-U", "-s", service, "-a", id, "-w", value),
            null,
            allowFailure = false,
        )
        check(result.first == 0)
        Unit
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (available) runSecretProcess(
            listOf("/usr/bin/security", "delete-generic-password", "-s", service, "-a", id),
            null,
            allowFailure = true,
        )
        Unit
    }
}

/** Linux Secret Service через secret-tool; секрет передаётся только stdin. */
class LinuxSecretServiceStore : SecureSecretStore {
    override val available: Boolean by lazy {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("linux")) false
        else runCatching { ProcessBuilder("secret-tool", "--version").start().waitFor() == 0 }.getOrDefault(false)
    }

    override suspend fun get(id: String): String? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        val result = runSecretProcess(listOf("secret-tool", "lookup", "service", "kasha-ai", "provider", id), null, true)
        result.takeIf { it.first == 0 }?.second?.trimEnd()?.takeIf(String::isNotBlank)
    }

    override suspend fun put(id: String, value: String) = withContext(Dispatchers.IO) {
        check(available)
        require(value.isNotBlank())
        runSecretProcess(
            listOf("secret-tool", "store", "--label=Kasha AI", "service", "kasha-ai", "provider", id),
            value,
            allowFailure = false,
        )
        Unit
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (available) runSecretProcess(listOf("secret-tool", "clear", "service", "kasha-ai", "provider", id), null, true)
        Unit
    }
}

fun platformSecretStore(): SecureSecretStore {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        os.contains("mac") -> MacKeychainSecretStore()
        os.contains("linux") -> LinuxSecretServiceStore()
        os.contains("windows") -> WindowsDpapiSecretStore()
        else -> UnsupportedSecretStore
    }
}

internal fun runSecretProcess(command: List<String>, stdin: String?, allowFailure: Boolean): Pair<Int, String> {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (stdin != null) {
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(stdin) }
    } else process.outputStream.close()
    val out = ByteArrayOutputStream()
    process.inputStream.use { it.copyTo(out) }
    val code = process.waitFor()
    if (!allowFailure) require(code == 0) { "Secure storage command failed" }
    return code to out.toString(StandardCharsets.UTF_8)
}
