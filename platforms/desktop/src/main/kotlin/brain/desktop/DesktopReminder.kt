package brain.desktop

import brain.model.Task
import brain.studio.ReminderGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Тонкий системный notifier для desktop ОС; бизнес-логика сроков остаётся в Core. */
internal class DesktopReminder(
    private val process: DesktopProcessGateway = SystemDesktopProcessGateway,
    private val os: DesktopOs = DesktopPlatform.os,
) : ReminderGateway {
    override val available: Boolean by lazy {
        when (os) {
            DesktopOs.MACOS -> process.available("/usr/bin/osascript")
            DesktopOs.LINUX -> process.available("notify-send")
            DesktopOs.WINDOWS -> process.available("powershell.exe") || process.available("pwsh.exe")
            DesktopOs.OTHER -> false
        }
    }

    override suspend fun notify(task: Task) {
        if (!available) return
        val body = task.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(180).orEmpty()
        withContext(Dispatchers.IO) {
            when (os) {
                DesktopOs.MACOS -> mac(body)
                DesktopOs.LINUX -> linux(body)
                DesktopOs.WINDOWS -> windows(body)
                DesktopOs.OTHER -> Unit
            }
        }
    }

    private fun mac(body: String) {
        val safe = body.replace("\\", "\\\\").replace("\"", "\\\"")
        process.run(listOf("/usr/bin/osascript", "-e", "display notification \"$safe\" with title \"Kasha · Задача\" sound name \"Glass\""))
    }

    private fun linux(body: String) {
        val script = "IFS= read -r body; exec notify-send --app-name=Kasha 'Kasha · Задача' \"\$body\""
        process.run(listOf("sh", "-c", script), body)
    }

    private fun windows(body: String) {
        val shell = if (process.available("powershell.exe")) "powershell.exe" else "pwsh.exe"
        val script = """
            ${'$'}body=[Console]::In.ReadToEnd()
            [Windows.UI.Notifications.ToastNotificationManager,Windows.UI.Notifications,ContentType=WindowsRuntime] > ${'$'}null
            [Windows.Data.Xml.Dom.XmlDocument,Windows.Data.Xml.Dom.XmlDocument,ContentType=WindowsRuntime] > ${'$'}null
            ${'$'}safe=[Security.SecurityElement]::Escape(${'$'}body)
            ${'$'}xml=New-Object Windows.Data.Xml.Dom.XmlDocument
            ${'$'}xml.LoadXml("<toast><visual><binding template='ToastGeneric'><text>Kasha · Задача</text><text>${'$'}safe</text></binding></visual></toast>")
            ${'$'}toast=[Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
            [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('ru.vrmn.kasha').Show(${'$'}toast)
        """.trimIndent()
        process.run(listOf(shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script), body)
    }
}
