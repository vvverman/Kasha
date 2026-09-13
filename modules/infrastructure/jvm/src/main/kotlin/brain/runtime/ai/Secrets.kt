package brain.runtime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
        val result = run(
            listOf("/usr/bin/security", "find-generic-password", "-s", service, "-a", id, "-w"),
            null,
            allowFailure = true,
        )
        result.takeIf { it.first == 0 }?.second?.trimEnd()
    }

    override suspend fun put(id: String, value: String) = withContext(Dispatchers.IO) {
        check(available)
        require(value.isNotBlank())
        val result = run(
            listOf("/usr/bin/security", "add-generic-password", "-U", "-s", service, "-a", id, "-w", value),
            null,
            allowFailure = false,
        )
        check(result.first == 0)
        Unit
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (available) run(
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
        val result = run(listOf("secret-tool", "lookup", "service", "kasha-ai", "provider", id), null, true)
        result.takeIf { it.first == 0 }?.second?.trimEnd()?.takeIf(String::isNotBlank)
    }

    override suspend fun put(id: String, value: String) = withContext(Dispatchers.IO) {
        check(available)
        require(value.isNotBlank())
        run(
            listOf("secret-tool", "store", "--label=Kasha AI", "service", "kasha-ai", "provider", id),
            value,
            allowFailure = false,
        )
        Unit
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (available) run(listOf("secret-tool", "clear", "service", "kasha-ai", "provider", id), null, true)
        Unit
    }
}

/**
 * Windows DPAPI. На диске лежат только зашифрованные CurrentUser-данные;
 * plaintext передаётся PowerShell только через stdin/stdout и не попадает в аргументы процесса.
 */
class WindowsDpapiSecretStore : SecureSecretStore {
    private val root: Path by lazy {
        val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
        local.resolve("Kasha").resolve("secrets").also(Files::createDirectories)
    }

    private val shell: String? by lazy {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) return@lazy null
        listOf("powershell.exe", "pwsh.exe").firstOrNull { command ->
            runCatching { ProcessBuilder("where.exe", command).start().waitFor() == 0 }.getOrDefault(false)
        }
    }

    override val available: Boolean get() = shell != null

    override suspend fun get(id: String): String? = withContext(Dispatchers.IO) {
        val executable = shell ?: return@withContext null
        val file = file(id)
        if (!Files.isRegularFile(file)) return@withContext null
        val script = """
            ${'$'}bytes=[IO.File]::ReadAllBytes(${'$'}args[0])
            ${'$'}plain=[Security.Cryptography.ProtectedData]::Unprotect(${'$'}bytes,${'$'}null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Console]::Out.Write([Text.Encoding]::UTF8.GetString(${'$'}plain))
        """.trimIndent()
        val result = run(listOf(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script, file.toString()), null, true)
        result.takeIf { it.first == 0 }?.second?.takeIf(String::isNotBlank)
    }

    override suspend fun put(id: String, value: String) = withContext(Dispatchers.IO) {
        val executable = shell ?: error("Защищённое хранилище Windows недоступно")
        require(value.isNotBlank())
        val file = file(id)
        val script = """
            ${'$'}plain=[Console]::In.ReadToEnd()
            ${'$'}bytes=[Text.Encoding]::UTF8.GetBytes(${'$'}plain)
            ${'$'}protected=[Security.Cryptography.ProtectedData]::Protect(${'$'}bytes,${'$'}null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            [IO.File]::WriteAllBytes(${'$'}args[0],${'$'}protected)
        """.trimIndent()
        run(listOf(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script, file.toString()), value, false)
        Unit
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        runCatching { Files.deleteIfExists(file(id)) }
        Unit
    }

    private fun file(id: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(StandardCharsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) } + ".dpapi"
        return root.resolve(name)
    }
}

fun platformSecretStore(): SecureSecretStore {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        os.contains("mac") -> MacKeychainSecretStore()
        os.contains("linux") -> LinuxSecretServiceStore()
        os.contains("win") -> WindowsDpapiSecretStore()
        else -> UnsupportedSecretStore
    }
}

private fun run(command: List<String>, stdin: String?, allowFailure: Boolean): Pair<Int, String> {
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
