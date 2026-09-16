@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import platform.Foundation.*
import kotlin.test.*

/** Настоящие операции Foundation в временном каталоге симулятора, не имитация storage. */
class IosPathsTest {
    private fun withDirectory(block: (String) -> Unit) {
        val path = NSTemporaryDirectory().trimEnd('/') + "/kasha-files-test-" + NSUUID().UUIDString
        IosPaths.directory(path)
        try { block(path) }
        finally { NSFileManager.defaultManager.removeItemAtPath(path, error = null) }
    }

    @Test fun applicationRootCanBeResolvedWithoutStringCast() {
        val path = IosPaths.root
        assertTrue(path.endsWith("/Kasha"))
        assertTrue(IosPaths.exists(path))
    }

    @Test fun unicodePathsAndTextRoundTripThroughFoundation() = withDirectory { root ->
        val path = IosPaths.child(root, "Заметка 1.json")
        val text = "Первая строка\nҚазақша · Українська · Беларуская · Grüße · Español · Français\n12345"
        assertEquals(root + "/Заметка 1.json", path)
        IosPaths.write(path, text)
        assertEquals(text, IosPaths.read(path))
    }

    @Test fun atomicReplacementKeepsTheOtherFile() = withDirectory { root ->
        val path = IosPaths.child(root, "state.json")
        val other = IosPaths.child(root, "other.json")
        IosPaths.write(path, "До изменения")
        IosPaths.write(other, "Не менять")
        IosPaths.write(path, "После изменения")
        assertEquals("После изменения", IosPaths.read(path))
        assertEquals("Не менять", IosPaths.read(other))
    }

    @Test fun emptyTextAndMissingFileAreDifferent() = withDirectory { root ->
        val path = IosPaths.child(root, "empty.json")
        assertNull(IosPaths.read(path))
        IosPaths.write(path, "")
        assertTrue(IosPaths.exists(path))
        assertEquals("", IosPaths.read(path))
    }

    @Test fun writeFailureIsNotReportedAsSuccess() = withDirectory { root ->
        val path = IosPaths.child(IosPaths.child(root, "missing-directory"), "state.json")
        assertFailsWith<IllegalStateException> { IosPaths.write(path, "Не сохранено") }
        assertFalse(IosPaths.exists(path))
    }

    @Test fun moveListAndRemovalOperateOnTheExpectedFile() = withDirectory { root ->
        val from = IosPaths.child(root, "source.txt")
        val to = IosPaths.child(root, "accepted.txt")
        IosPaths.write(from, "Сохранённый источник")
        IosPaths.move(from, to)
        assertFalse(IosPaths.exists(from))
        assertEquals("Сохранённый источник", IosPaths.read(to))
        assertEquals(listOf("accepted.txt"), IosPaths.list(root))
        IosPaths.remove(to)
        assertFalse(IosPaths.exists(to))
    }

    @Test fun moveNeverClobbersAnExistingDestination() = withDirectory { root ->
        val from = IosPaths.child(root, "source.txt")
        val to = IosPaths.child(root, "existing.txt")
        IosPaths.write(from, "Новая копия")
        IosPaths.write(to, "Существующая копия")
        assertFailsWith<IllegalStateException> { IosPaths.move(from, to) }
        assertEquals("Новая копия", IosPaths.read(from))
        assertEquals("Существующая копия", IosPaths.read(to))
    }

}
