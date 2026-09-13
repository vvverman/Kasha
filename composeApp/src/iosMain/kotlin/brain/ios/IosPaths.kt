@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import platform.Foundation.*

/** Только файловая часть iOS shell. Никакой бизнес-логики здесь нет. */
internal object IosPaths {
    private val files = NSFileManager.defaultManager

    val root: String by lazy {
        val base = (NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String) ?: NSTemporaryDirectory()
        directory((base as NSString).stringByAppendingPathComponent("Kasha"))
    }

    val audio: String by lazy { directory(child(root, "audio")) }
    val pending: String by lazy { directory(child(root, "pending")) }
    val stateFile: String get() = child(root, "state.json")
    val preferencesFile: String get() = child(root, "preferences.json")

    fun child(parent: String, name: String): String =
        (parent as NSString).stringByAppendingPathComponent(name)

    fun directory(path: String): String {
        if (!files.fileExistsAtPath(path)) {
            check(files.createDirectoryAtPath(
                path = path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )) { "Не удалось создать локальный каталог Kasha" }
        }
        return path
    }

    fun exists(path: String): Boolean = files.fileExistsAtPath(path)

    fun read(path: String): String? = if (!exists(path)) null else
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.toString()

    fun write(path: String, text: String) {
        val ok = (text as NSString).writeToFile(
            path = path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        check(ok) { "Не удалось сохранить локальные данные Kasha" }
    }

    fun move(from: String, to: String) {
        if (exists(to)) remove(to)
        check(files.moveItemAtPath(from, toPath = to, error = null)) {
            "Не удалось завершить локальный аудиофайл Kasha"
        }
    }

    fun remove(path: String) {
        if (exists(path)) files.removeItemAtPath(path, error = null)
    }

    fun list(path: String): List<String> =
        (files.contentsOfDirectoryAtPath(path, error = null) ?: emptyList<Any>())
            .mapNotNull { it as? String }
}
