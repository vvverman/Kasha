package brain.desktop

import java.nio.file.Path
import java.util.Locale

enum class DesktopOs { MACOS, WINDOWS, LINUX, OTHER }

object DesktopPlatform {
    val os: DesktopOs by lazy { detectOs(System.getProperty("os.name")) }

    internal fun detectOs(osName: String): DesktopOs {
        val name = osName.lowercase(Locale.ROOT)
        return when {
            name.contains("mac") || name.contains("darwin") -> DesktopOs.MACOS
            name.contains("win") -> DesktopOs.WINDOWS
            name.contains("linux") -> DesktopOs.LINUX
            else -> DesktopOs.OTHER
        }
    }

    fun dataRoot(appName: String): Path = dataRoot(
        appName = appName,
        os = os,
        environment = System.getenv(),
        home = Path.of(System.getProperty("user.home")),
    )

    internal fun dataRoot(appName: String, os: DesktopOs, environment: Map<String, String>, home: Path): Path {
        environment["KASHA_HOME"]?.takeIf(String::isNotBlank)?.let { return Path.of(it) }
        return when (os) {
            DesktopOs.MACOS -> home.resolve("Library").resolve("Application Support").resolve(appName)
            DesktopOs.WINDOWS -> {
                val appData = environment["APPDATA"]?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: home.resolve("AppData").resolve("Roaming")
                appData.resolve(appName)
            }
            DesktopOs.LINUX -> {
                val xdg = environment["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
                    ?: home.resolve(".local").resolve("share")
                xdg.resolve(appName.lowercase(Locale.ROOT))
            }
            DesktopOs.OTHER -> home.resolve(".$appName")
        }
    }

    fun localDataRoot(appName: String): Path = localDataRoot(
        appName = appName,
        os = os,
        environment = System.getenv(),
        home = Path.of(System.getProperty("user.home")),
    )

    internal fun localDataRoot(appName: String, os: DesktopOs, environment: Map<String, String>, home: Path): Path = when (os) {
        DesktopOs.WINDOWS -> {
            val local = environment["LOCALAPPDATA"]?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: home.resolve("AppData").resolve("Local")
            local.resolve(appName)
        }
        else -> dataRoot(appName, os, environment - "KASHA_HOME", home)
    }

    fun executableCandidates(baseName: String): List<String> = executableCandidates(baseName, os)

    internal fun executableCandidates(baseName: String, os: DesktopOs): List<String> = when (os) {
        DesktopOs.WINDOWS -> listOf("$baseName.exe", baseName)
        else -> listOf(baseName)
    }
}
