package brain.desktop

import brain.runtime.ai.WindowsDpapiSecretStore
import brain.runtime.ai.platformSecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.test.*

/** Реальный DPAPI проверяется на Windows runner; на других ОС этот тест неприменим. */
class WindowsSecretStoreTest {
    @Test fun windowsFactoryUsesSharedRuntimeDpapi() {
        assumeTrue(DesktopPlatform.os == DesktopOs.WINDOWS)
        assertIs<WindowsDpapiSecretStore>(platformSecretStore())
    }

    @Test fun actualDpapiRoundTripAndDeletion(): Unit = runBlocking {
        assumeTrue(DesktopPlatform.os == DesktopOs.WINDOWS)
        val root = Files.createTempDirectory("kasha-dpapi-test-")
        try {
            val store = WindowsDpapiSecretStore(root)
            assertTrue(store.available, "Windows runner must provide CurrentUser DPAPI")
            val id = "../../regression-provider"
            val first = "Kasha test value — не настоящий ключ"
            assertNull(store.get(id))
            store.put(id, first)
            assertEquals(first, WindowsDpapiSecretStore(root).get(id))
            val files = Files.list(root).use { it.toList() }
            assertEquals(1, files.size)
            assertTrue(files.single().fileName.toString().matches(Regex("[a-f0-9]{64}\\.dpapi")))
            assertFalse(Files.readAllBytes(files.single()).contentEquals(first.toByteArray()))
            store.put(id, "Другая тестовая строка")
            assertEquals("Другая тестовая строка", store.get(id))
            assertFailsWith<IllegalArgumentException> { store.put(id, " ") }
            assertEquals("Другая тестовая строка", store.get(id))
            store.remove(id); store.remove(id)
            assertNull(store.get(id))
            assertEquals(0L, Files.list(root).use { it.count() })
        } finally { root.toFile().deleteRecursively() }
    }
}
