package brain.runtime.system

import brain.model.Task
import brain.studio.ReminderGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Один системный notifier для Desktop и локального Web runtime; расписание остаётся в Core. */
class JvmReminderGateway(
    private val process: JvmProcessGateway = SystemJvmProcessGateway,
    private val os: String = System.getProperty("os.name"),
) : ReminderGateway {
    private val platform = when {
        os.startsWith("Windows", true) -> "WINDOWS"
        os.startsWith("Mac", true) || os.equals("Darwin", true) -> "MACOS"
        os.startsWith("Linux", true) -> "LINUX"
        else -> "OTHER"
    }
    override val available: Boolean by lazy {
        when (platform) {
            "MACOS" -> process.available("/usr/bin/osascript")
            "LINUX" -> process.available("notify-send")
            "WINDOWS" -> process.available("powershell.exe") || process.available("pwsh.exe")
            else -> false
        }
    }

    override suspend fun notify(task: Task) {
        check(available) { "Уведомления недоступны" }
        val body = task.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(180).orEmpty()
        withContext(Dispatchers.IO) {
            val result = when (platform) {
                "MACOS" -> {
                    val safe = body.replace("\\", "\\\\").replace("\"", "\\\"")
                    process.run(listOf("/usr/bin/osascript", "-e", "display notification \"$safe\" with title \"Kasha\" sound name \"Glass\""))
                }
                "LINUX" -> process.run(
                    listOf("sh", "-c", "IFS= read -r body; exec notify-send --app-name=Kasha 'Kasha' \"\$body\""), body,
                )
                "WINDOWS" -> {
                    val shell = if (process.available("powershell.exe")) "powershell.exe" else "pwsh.exe"
                    val script = """
                        ${'$'}ErrorActionPreference='Stop'
                        ${'$'}body=[Console]::In.ReadToEnd()
                        [Windows.UI.Notifications.ToastNotificationManager,Windows.UI.Notifications,ContentType=WindowsRuntime] > ${'$'}null
                        [Windows.Data.Xml.Dom.XmlDocument,Windows.Data.Xml.Dom.XmlDocument,ContentType=WindowsRuntime] > ${'$'}null
                        ${'$'}safe=[Security.SecurityElement]::Escape(${'$'}body)
                        ${'$'}xml=New-Object Windows.Data.Xml.Dom.XmlDocument
                        ${'$'}xml.LoadXml("<toast><visual><binding template='ToastGeneric'><text>Kasha</text><text>${'$'}safe</text></binding></visual></toast>")
                        ${'$'}toast=[Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
                        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('ru.vrmn.kasha').Show(${'$'}toast)
                    """.trimIndent()
                    process.run(listOf(shell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script), body)
                }
                else -> error("Уведомления недоступны")
            }
            check(result.exitCode == 0) { "Не удалось передать уведомление системе" }
        }
    }
}
