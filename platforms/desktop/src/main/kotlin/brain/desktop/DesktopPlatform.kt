package brain.desktop

import java.nio.file.Path
import java.util.Locale

enum class DesktopOs { MACOS, WINDOWS, LINUX, OTHER }

object DesktopPlatform {
    val os: DesktopOs by lazy {
        val name = System.getProperty("os.name").lowercase(Locale.ROOT)
        when {
            name.contains("mac") -> DesktopOs.MACOS
            name.contains("win") -> DesktopOs.WINDOWS
            name.contains("linux") -> DesktopOs.LINUX
            else -> DesktopOs.OTHER
        }
    }

    fun dataRoot(appName: String): Path {
        System.getenv("KASHA_HOME")?.takeIf(String::isNotBlank)?.let { return Path.of(it) }
        val home = Path.of(System.getProperty("user.home"))
        return when (os) {
            DesktopOs.MACOS -> home.resolve("Library").resolve("Application Support").resolve(appName)
            DesktopOs.WINDOWS -> {
                val appData = System.getenv("APPDATA")?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: home.resolve("AppData").resolve("Roaming")
                appData.resolve(appName)
            }
            DesktopOs.LINUX -> {
                val xdg = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: home.resolve(".local").resolve("share")
                xdg.resolve(appName.lowercase(Locale.ROOT))
            }
            DesktopOs.OTHER -> home.resolve(".$appName")
        }
    }

    fun executableCandidates(baseName: String): List<String> = when (os) {
        DesktopOs.WINDOWS -> listOf("$baseName.exe", baseName)
        else -> listOf(baseName)
    }
}
