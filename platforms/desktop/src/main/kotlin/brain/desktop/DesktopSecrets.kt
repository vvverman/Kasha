package brain.desktop

import brain.runtime.ai.SecureSecretStore
import brain.runtime.ai.UnsupportedSecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** macOS Keychain adapter. Metadata stays in the shared gateway; only the secret reaches Keychain. */
internal class MacKeychainSecretStore(
    private val process: DesktopProcessGateway = SystemDesktopProcessGateway,
    private val service: String = "ru.vrmn.kasha.ai",
) : SecureSecretStore {
    override val available: Boolean get() = process.available("/usr/bin/security")

    override suspend fun get(id: String): String? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        val result = process.run(listOf("/usr/bin/security", "find-generic-password", "-s", service, "-a", id, "-w"))
        result.output.takeIf { result.exitCode == 0 }?.trimEnd()
    }

    override suspend fun put(id: String, value: String) = withContext(Dispatchers.IO) {
        check(available) { "Защищённое хранилище macOS недоступно" }
        require(value.isNotBlank())
        val result = process.run(listOf("/usr/bin/security", "add-generic-password", "-U", "-s", service, "-a", id, "-w", value))
        require(result.exitCode == 0) { "Keychain command failed" }
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (available) process.run(listOf("/usr/bin/security", "delete-generic-password", "-s", service, "-a", id))
        Unit
    }
}

/** Linux Secret Service through secret-tool; the secret itself is sent through stdin. */
internal class LinuxSecretServiceStore(
    private val process: DesktopProcessGateway = SystemDesktopProcessGateway,
) : SecureSecretStore {
    override val available: Boolean get() = process.available("secret-tool")

    override suspend fun get(id: String): String? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        val result = process.run(listOf("secret-tool", "lookup", "service", "kasha-ai", "provider", id))
        result.output.takeIf { result.exitCode == 0 }?.trimEnd()?.takeIf(String::isNotBlank)
    }

    override suspend fun put(id: String, value: String) = withContext(Dispatchers.IO) {
        check(available) { "Защищённое хранилище Linux недоступно" }
        require(value.isNotBlank())
        val result = process.run(
            listOf("secret-tool", "store", "--label=Kasha AI", "service", "kasha-ai", "provider", id),
            value,
        )
        require(result.exitCode == 0) { "Secret Service command failed" }
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        if (available) process.run(listOf("secret-tool", "clear", "service", "kasha-ai", "provider", id))
        Unit
    }
}

/** Windows DPAPI CurrentUser adapter. Only the encrypted blob is persisted on disk. */
internal class WindowsDpapiSecretStore(
    private val root: Path,
    private val process: DesktopProcessGateway = SystemDesktopProcessGateway,
) : SecureSecretStore {
    private val shell: String? get() = listOf("powershell.exe", "pwsh.exe").firstOrNull(process::available)
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
        val result = process.run(listOf(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script, file.toString()))
        result.output.takeIf { result.exitCode == 0 }?.takeIf(String::isNotBlank)
    }

    override suspend fun put(id: String, value: String) = withContext(Dispatchers.IO) {
        val executable = shell ?: error("Защищённое хранилище Windows недоступно")
        require(value.isNotBlank())
        val file = file(id)
        Files.createDirectories(file.parent)
        val script = """
            ${'$'}plain=[Console]::In.ReadToEnd()
            ${'$'}bytes=[Text.Encoding]::UTF8.GetBytes(${'$'}plain)
            ${'$'}protected=[Security.Cryptography.ProtectedData]::Protect(${'$'}bytes,${'$'}null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            [IO.File]::WriteAllBytes(${'$'}args[0],${'$'}protected)
        """.trimIndent()
        val result = process.run(
            listOf(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script, file.toString()),
            value,
        )
        require(result.exitCode == 0) { "DPAPI command failed" }
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(file(id))
        Unit
    }

    internal fun file(id: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(StandardCharsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) } + ".dpapi"
        return root.resolve(name)
    }
}

internal fun desktopSecretStore(
    os: DesktopOs = DesktopPlatform.os,
    process: DesktopProcessGateway = SystemDesktopProcessGateway,
    windowsRoot: Path = DesktopPlatform.localDataRoot("Kasha").resolve("secrets"),
): SecureSecretStore = when (os) {
    DesktopOs.MACOS -> MacKeychainSecretStore(process)
    DesktopOs.LINUX -> LinuxSecretServiceStore(process)
    DesktopOs.WINDOWS -> WindowsDpapiSecretStore(windowsRoot, process)
    DesktopOs.OTHER -> UnsupportedSecretStore
}
