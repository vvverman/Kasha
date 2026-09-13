package brain.desktop

import brain.model.Task
import brain.runtime.ai.UnsupportedSecretStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DesktopAdaptersTest {
    @Test fun linuxSecretUsesStdinAndNeverPutsSecretInArguments() = runBlocking {
        val process = FakeProcess(setOf("secret-tool"))
        LinuxSecretServiceStore(process).put("openai", "SUPER-SECRET")
        val call = process.calls.single()
        assertEquals("SUPER-SECRET", call.stdin)
        assertTrue(call.command.none { "SUPER-SECRET" in it })
        assertEquals("secret-tool", call.command.first())
    }

    @Test fun windowsDpapiUsesStdinAndHashedProviderFile() = runBlocking {
        val root = Files.createTempDirectory("kasha-dpapi-test")
        try {
            val process = FakeProcess(setOf("powershell.exe"))
            val store = WindowsDpapiSecretStore(root, process)
            store.put("openai", "SUPER-SECRET")
            val call = process.calls.single()
            assertEquals("SUPER-SECRET", call.stdin)
            assertTrue(call.command.none { "SUPER-SECRET" in it })
            val file = store.file("openai")
            assertEquals(root, file.parent)
            assertTrue(file.fileName.toString().endsWith(".dpapi"))
            assertFalse(file.fileName.toString().contains("openai", ignoreCase = true))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun desktopSecretFactorySelectsOsAdapters() {
        val process = FakeProcess(setOf("/usr/bin/security", "secret-tool", "powershell.exe"))
        val windowsRoot = Files.createTempDirectory("kasha-secret-factory")
        try {
            assertIs<MacKeychainSecretStore>(desktopSecretStore(DesktopOs.MACOS, process, windowsRoot))
            assertIs<LinuxSecretServiceStore>(desktopSecretStore(DesktopOs.LINUX, process, windowsRoot))
            assertIs<WindowsDpapiSecretStore>(desktopSecretStore(DesktopOs.WINDOWS, process, windowsRoot))
            assertSame(UnsupportedSecretStore, desktopSecretStore(DesktopOs.OTHER, process, windowsRoot))
        } finally { windowsRoot.toFile().deleteRecursively() }
    }

    @Test fun linuxReminderSendsUserTextThroughStdin() = runBlocking {
        val process = FakeProcess(setOf("notify-send"))
        val reminder = DesktopReminder(process, DesktopOs.LINUX)
        assertTrue(reminder.available)
        reminder.notify(task("  Первая строка  \nвторая строка"))
        val call = process.calls.single()
        assertEquals("Первая строка", call.stdin)
        assertTrue(call.command.none { "Первая строка" in it })
        assertEquals("sh", call.command.first())
    }

    @Test fun windowsReminderEscapesUserTextInsidePowerShellAtRuntime() = runBlocking {
        val process = FakeProcess(setOf("powershell.exe"))
        val reminder = DesktopReminder(process, DesktopOs.WINDOWS)
        assertTrue(reminder.available)
        reminder.notify(task("<unsafe & text>"))
        val call = process.calls.single()
        assertEquals("<unsafe & text>", call.stdin)
        assertTrue(call.command.none { "<unsafe & text>" in it })
        assertEquals("powershell.exe", call.command.first())
        assertTrue(call.command.any { "SecurityElement" in it })
    }

    @Test fun unsupportedReminderIsUnavailableAndDoesNothing() = runBlocking {
        val process = FakeProcess()
        val reminder = DesktopReminder(process, DesktopOs.OTHER)
        assertFalse(reminder.available)
        reminder.notify(task("ignored"))
        assertTrue(process.calls.isEmpty())
    }

    private fun task(text: String) = Task(
        id = "task-1",
        text = text,
        createdAt = 1,
        updatedAt = 1,
    )

    private class FakeProcess(
        private val availableCommands: Set<String> = emptySet(),
        private val result: DesktopProcessResult = DesktopProcessResult(0, ""),
    ) : DesktopProcessGateway {
        data class Call(val command: List<String>, val stdin: String?)
        val calls = mutableListOf<Call>()
        override fun available(command: String): Boolean = command in availableCommands
        override fun run(command: List<String>, stdin: String?): DesktopProcessResult {
            calls += Call(command, stdin)
            return result
        }
    }
}
