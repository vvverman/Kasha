package brain.desktop

import brain.model.Task
import brain.studio.ReminderGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/** Тонкий системный notifier для desktop ОС; бизнес-логика сроков остаётся в Core. */
class DesktopReminder : ReminderGateway {
    override val available: Boolean by lazy {
        when (DesktopPlatform.os) {
            DesktopOs.MACOS -> java.io.File("/usr/bin/osascript").canExecute()
            DesktopOs.LINUX -> commandAvailable("notify-send")
            DesktopOs.WINDOWS -> commandAvailable("powershell.exe") || commandAvailable("pwsh.exe")
            DesktopOs.OTHER -> false
        }
    }

    override suspend fun notify(task: Task) {
        if (!available) return
        val body = task.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(180).orEmpty()
        withContext(Dispatchers.IO) {
            when (DesktopPlatform.os) {
                DesktopOs.MACOS -> mac(body)
                DesktopOs.LINUX -> linux(body)
                DesktopOs.WINDOWS -> windows(body)
                DesktopOs.OTHER -> Unit
            }
        }
    }

    private fun mac(body: String) {
        val safe = body.replace("\\", "\\\\").replace("\"", "\\\"")
        run(listOf("/usr/bin/osascript", "-e", "display notification \"$safe\" with title \"Kasha · Задача\" sound name \"Glass\""), null)
    }

    private fun linux(body: String) {
        val script = "IFS= read -r body; exec notify-send --app-name=Kasha 'Kasha · Задача' \"\$body\""
        run(listOf("sh", "-c", script), body)
    }

    private fun windows(body: String) {
        val shell = if (commandAvailable("powershell.exe")) "powershell.exe" else "pwsh.exe"
        val script = """
            ${'$'}body=[Console]::In.ReadToEnd()
            [Windows.UI.Notifications.ToastNotificationManager,Windows.UI.Notifications,ContentType=WindowsRuntime] > ${'$'}null
            [Windows.Data.Xml.Dom.XmlDocument,Windows.Data.Xml.Dom.XmlDocument,ContentType=WindowsRuntime] > ${'$'}null
            ${'$'}safe=[Security.SecurityElement]::Escape(${'$'}body)
            ${'$'}xml=New-Object Windows.Data.Xml.Dom.XmlDocument
            ${'$'}xml.LoadXml("<toast><visual><binding template='ToastGeneric'><text>Kasha · Задача</text><text>${'$'}safe</text></binding></visual></toast>")
            ${'$'}toast=[Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Kasha').Show(${'$'}toast)
        """.trimIndent()
        run(listOf(shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script), body)
    }

    private fun run(command: List<String>, stdin: String?) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            if (stdin != null) writer.write(stdin)
        }
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
    }

    private fun commandAvailable(command: String): Boolean = runCatching {
        val probe = if (DesktopPlatform.os == DesktopOs.WINDOWS) {
            ProcessBuilder("where.exe", command)
        } else {
            ProcessBuilder("sh", "-c", "command -v '$command' >/dev/null 2>&1")
        }.start()
        probe.waitFor() == 0
    }.getOrDefault(false)
}
