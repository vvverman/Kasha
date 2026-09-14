package brain.desktop

import brain.runtime.system.JvmReminderGateway
import brain.studio.ReminderGateway

internal class DesktopReminder(
    process: DesktopProcessGateway = SystemDesktopProcessGateway,
    os: DesktopOs = DesktopPlatform.os,
) : ReminderGateway by JvmReminderGateway(process, os.name)
