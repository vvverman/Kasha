import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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
            }
        }
    }
}
