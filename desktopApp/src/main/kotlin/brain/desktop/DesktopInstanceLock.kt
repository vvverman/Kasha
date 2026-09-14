package brain.desktop

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

internal class DesktopInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { lock.release() }
        channel.close()
    }

    companion object {
        fun acquire(root: Path): DesktopInstanceLock {
            Files.createDirectories(root)
            val channel = FileChannel.open(
                root.resolve(".desktop.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            return try {
                val lock = channel.tryLock() ?: error("Kasha уже запущен")
                DesktopInstanceLock(channel, lock)
            } catch (e: Exception) {
                channel.close()
                throw e
            }
        }
    }
}
