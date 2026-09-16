import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}
kotlin { jvmToolchain(21) }
val demoBuild = providers.gradleProperty("demoBuild").map { it.toBoolean() }.getOrElse(false)
dependencies {
    implementation(project(":kashaCore")); implementation(project(":aiCatalog")); implementation(project(":composeApp")); implementation(project(":runtime"))
    implementation(compose.desktop.currentOs); implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation(libs.kotlinx.serialization.json); testImplementation(kotlin("test-junit"))
}
compose.desktop {
    application {
        mainClass = "brain.desktop.MainKt"
        jvmArgs += listOf("-Xmx768m", "-Dfile.encoding=UTF-8", "-Dapple.awt.application.name=Kasha")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = if(demoBuild) "Kasha Test" else "Kasha"
            packageVersion = "1.2.0"
            vendor = "Vyacheslav Verman"
            description = if(demoBuild) "Тест интерфейса, ИИ имитируется" else "Локальные голосовые заметки"
            includeAllModules = true
            appResourcesRootDir.set(layout.projectDirectory.dir(if(demoBuild) "bundle-test" else "bundle"))
            macOS {
                bundleID = if(demoBuild) "ru.vrmn.kasha.test" else "ru.vrmn.kasha"
                dockName = if(demoBuild) "Kasha Test" else "Kasha"
                minimumSystemVersion = "13.3"
                appCategory = "public.app-category.productivity"
                iconFile.set(layout.projectDirectory.file("packaging/Kasha.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key><string>Kasha записывает ваш голос локально. Аудио, транскрипции, заметки и задачи не отправляются в облако без вашего явного выбора.</string>
                        <key>NSHighResolutionCapable</key><true/>
                        <key>CFBundleDevelopmentRegion</key><string>en</string>
                        <key>CFBundleLocalizations</key><array><string>ru</string><string>en</string><string>es</string><string>fr</string><string>de</string><string>uk</string><string>be</string><string>kk</string></array>
                    """.trimIndent()
                }
            }
            windows {
                menuGroup = "Kasha"
                dirChooser = true
                perUserInstall = true
            }
            linux {
                menuGroup = "Utility"
                appCategory = "Utility"
                shortcut = true
            }
        }
    }
}

// These commands are used by DesktopReminder and LinuxSecretServiceStore.
// Keep distro package names in packaging, not in Core or shared UI.
tasks.withType<AbstractJPackageTask>().configureEach {
    when (targetFormat) {
        TargetFormat.Deb -> freeArgs.addAll("--linux-package-deps", "libnotify-bin,libsecret-tools")
        TargetFormat.Rpm -> freeArgs.addAll("--linux-package-deps", "libnotify,libsecret")
        else -> Unit
    }
}

// Gradle/JPackage копируют ресурсы без исходного POSIX executable bit.
// Исправляем сам app image до упаковки, а не только тестовую копию.
if (!demoBuild && System.getProperty("os.name").lowercase().contains("linux")) {
    tasks.named("createDistributable") {
        doLast {
            val bin = layout.buildDirectory.dir("compose/binaries/main/app/Kasha/lib/app/resources/bin").get().asFile
            for (name in listOf("whisper-cli", "llama-completion", "ffmpeg")) {
                val executable = bin.resolve(name)
                check(executable.isFile && executable.setExecutable(true, false) && executable.canExecute()) {
                    "Не удалось установить право запуска упакованного движка: $name"
                }
            }
        }
    }
}
