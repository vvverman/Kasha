package brain.desktop

import brain.model.Task
import brain.studio.ReminderGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Системная доставка desktop-уведомления. Расписание и задачи остаются в Core. */
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
        check(available) { "Уведомления недоступны" }
        val body = task.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(180).orEmpty()
        withContext(Dispatchers.IO) {
            val result = when (os) {
                DesktopOs.MACOS -> {
                    val safe = body.replace("\\", "\\\\").replace("\"", "\\\"")
                    process.run(listOf("/usr/bin/osascript", "-e", "display notification \"$safe\" with title \"Kasha\" sound name \"Glass\""))
                }
                DesktopOs.LINUX -> process.run(
                    listOf("sh", "-c", "IFS= read -r body; exec notify-send --app-name=Kasha 'Kasha' \"\$body\""), body,
                )
                DesktopOs.WINDOWS -> {
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
                DesktopOs.OTHER -> error("Уведомления недоступны")
            }
            check(result.exitCode == 0) { "Не удалось передать уведомление системе" }
        }
    }
}
