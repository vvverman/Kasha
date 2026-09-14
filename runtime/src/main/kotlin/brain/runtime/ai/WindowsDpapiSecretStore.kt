package brain.runtime.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** Windows DPAPI CurrentUser; на диске только шифротекст, входной ключ передаётся через stdin. */
class WindowsDpapiSecretStore(
    private val root: Path = Path.of(System.getenv("LOCALAPPDATA")
        ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()).resolve("Kasha/secrets"),
) : SecureSecretStore {
    private val shell: String? by lazy {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")) null
        else listOf("powershell.exe", "pwsh.exe").firstOrNull { executable ->
            runCatching {
                runSecretProcess(command(executable, """
                    [Security.Cryptography.ProtectedData]::Protect([byte[]](1),${'$'}null,[Security.Cryptography.DataProtectionScope]::CurrentUser) | Out-Null
                """.trimIndent()), null, true).first == 0
            }.getOrDefault(false)
        }
    }
    override val available: Boolean get() = shell != null

    override suspend fun get(id: String): String? = withContext(Dispatchers.IO) {
        val executable = shell ?: return@withContext null
        val file = file(id)
        if (!Files.exists(file)) return@withContext null
        val encrypted = Base64.getEncoder().encodeToString(Files.readAllBytes(file))
        runSecretProcess(command(executable, """
            ${'$'}encrypted=[Convert]::FromBase64String([Console]::In.ReadToEnd())
            ${'$'}plain=[Security.Cryptography.ProtectedData]::Unprotect(${'$'}encrypted,${'$'}null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Console]::Write([Text.Encoding]::UTF8.GetString(${'$'}plain))
        """.trimIndent()), encrypted, false).second
    }

    override suspend fun put(id: String, value: String): Unit = withContext(Dispatchers.IO) {
        val executable = shell ?: error("Защищённое хранилище Windows недоступно")
        require(value.isNotBlank())
        val protected = runSecretProcess(command(executable, """
            ${'$'}plain=[Text.Encoding]::UTF8.GetBytes([Console]::In.ReadToEnd())
            ${'$'}protected=[Security.Cryptography.ProtectedData]::Protect(${'$'}plain,${'$'}null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Console]::Write([Convert]::ToBase64String(${'$'}protected))
        """.trimIndent()), value, false).second.trim()
        val bytes = Base64.getDecoder().decode(protected)
        check(bytes.isNotEmpty()) { "Защищённое хранилище Windows не сохранило ключ" }
        Files.createDirectories(root)
        val target = file(id)
        val temporary = Files.createTempFile(root, ".dpapi-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    override suspend fun remove(id: String): Unit = withContext(Dispatchers.IO) {
        Files.deleteIfExists(file(id))
        Unit
    }

    private fun file(id: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(UTF_8))
        return root.resolve(digest.joinToString("") { "%02x".format(it) } + ".dpapi")
    }

    private fun command(executable: String, operation: String) = listOf(
        executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
        """
            ${'$'}ErrorActionPreference='Stop'
            [Console]::InputEncoding=[Text.UTF8Encoding]::new(${'$'}false)
            [Console]::OutputEncoding=[Text.UTF8Encoding]::new(${'$'}false)
            Add-Type -AssemblyName System.Security
        """.trimIndent() + "\n" + operation,
    )
}
