package brain.desktop

import brain.model.Task
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopPlatformParityTest {
    @Test fun darwinIsNotMisidentifiedAsWindows() {
        assertEquals(DesktopOs.MACOS, DesktopPlatform.detectOs("Darwin"))
        assertEquals(DesktopOs.WINDOWS, DesktopPlatform.detectOs("Windows 11"))
        assertEquals(DesktopOs.LINUX, DesktopPlatform.detectOs("Linux"))
    }

    @Test fun dataLocationsFollowTheirOperatingSystem() {
        val home = Path.of("home").toAbsolutePath()
        val roaming = home.resolve("roaming")
        val xdg = home.resolve("xdg")
        assertEquals(home.resolve("Library/Application Support/Kasha"), DesktopPlatform.dataRoot("Kasha", DesktopOs.MACOS, emptyMap(), home))
        assertEquals(roaming.resolve("Kasha"), DesktopPlatform.dataRoot("Kasha", DesktopOs.WINDOWS, mapOf("APPDATA" to roaming.toString()), home))
        assertEquals(xdg.resolve("kasha"), DesktopPlatform.dataRoot("Kasha", DesktopOs.LINUX, mapOf("XDG_DATA_HOME" to xdg.toString()), home))
    }

    @Test fun explicitHomeOverrideIsNotLost() {
        val home = Path.of("home").toAbsolutePath()
        val chosen = home.resolve("chosen")
        for (os in listOf(DesktopOs.MACOS, DesktopOs.WINDOWS, DesktopOs.LINUX)) {
            assertEquals(chosen, DesktopPlatform.dataRoot("Kasha", os, mapOf("KASHA_HOME" to chosen.toString()), home))
        }
    }

    @Test fun windowsResolvesPackagedExeWithoutPosixPermission() {
        val root = Files.createTempDirectory("kasha-executable-")
        try {
            Files.createDirectories(root.resolve("bin"))
            val exe = Files.write(root.resolve("bin/whisper-cli.exe"), byteArrayOf(1))
            assertEquals(exe.toAbsolutePath().toString(), bundledExecutable(root, "whisper-cli", DesktopOs.WINDOWS))
            assertFailsWith<IllegalStateException> { bundledExecutable(root, "missing", DesktopOs.WINDOWS) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun instanceLockCanBeReleasedAndReacquired() {
        val root = Files.createTempDirectory("kasha-instance-")
        try {
            val first = DesktopInstanceLock.acquire(root)
            assertFails { DesktopInstanceLock.acquire(root) }
            first.close(); first.close()
            DesktopInstanceLock.acquire(root).close()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun notificationTextIsDataNotShellCode() = runBlocking {
        for (os in listOf(DesktopOs.LINUX, DesktopOs.WINDOWS)) {
            val process = FakeProcess()
            val body = "Текст <&> ; не команда"
            DesktopReminder(process, os).notify(Task("t", body, 1, 1))
            assertEquals(body, process.input)
            assertTrue(process.command.none { body in it })
        }
    }

    @Test fun failedNotificationIsNotReportedAsSuccess() = runBlocking {
        val process = FakeProcess(exitCode = 1)
        assertFailsWith<IllegalStateException> {
            DesktopReminder(process, DesktopOs.LINUX).notify(Task("t", "Текст", 1, 1))
        }
    }

    @Test fun unavailableNotificationDoesNotSilentlySucceed() = runBlocking {
        val process = FakeProcess()
        val notifier = DesktopReminder(process, DesktopOs.OTHER)
        assertFalse(notifier.available)
        assertFailsWith<IllegalStateException> { notifier.notify(Task("t", "Текст", 1, 1)) }
        assertTrue(process.command.isEmpty())
    }

    private class FakeProcess(private val exitCode: Int = 0) : DesktopProcessGateway {
        var command: List<String> = emptyList()
        var input: String? = null
        override fun available(command: String) = true
        override fun run(command: List<String>, stdin: String?): DesktopProcessResult {
            this.command = command; input = stdin
            return DesktopProcessResult(exitCode, "")
        }
    }
}
