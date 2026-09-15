import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "brain.studio.resources"
    generateResClass = always
}

val generatedKashaIconsDirectory = layout.buildDirectory.dir("generated/kashaIcons/commonMain")
val generateKashaIcons by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Generate Compose runtime geometry from the canonical Kasha Icons registry"
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/generate-kasha-icons.py",
        generatedKashaIconsDirectory.get().file("brain/studio/GeneratedKashaIcons.kt").asFile.absolutePath)
    inputs.file(rootProject.file("docs/design/icons/registry.json"))
    inputs.file(rootProject.file("scripts/generate-kasha-icons.py"))
    outputs.dir(generatedKashaIconsDirectory)
}

kotlin {
    jvm()
    jvmToolchain(21)

    android {
        namespace = "ru.vrmn.kasha.ui"
        compileSdk = 37
        minSdk = 26
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KashaShared"
            isStatic = true
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { commonWebpackConfig { outputFileName = "composeApp.js" } }
        binaries.executable()
    }

    sourceSets {
        getByName("commonMain").kotlin.apply {
            srcDir(generateKashaIcons)
            // Не компилировать оставшийся игнорируемый результат старой локальной сборки.
            exclude("brain/studio/ui/GeneratedKashaIcons.kt")
        }
        commonMain.dependencies {
            implementation(project(":kashaCore"))
            implementation(project(":aiCatalog"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        iosMain.dependencies {
            implementation("com.russhwolf:multiplatform-settings:1.3.0")
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation("io.ktor:ktor-client-darwin:3.5.2")
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.js)
        }
    }
}

// Все компиляции общего UI, включая Android, ожидают генерацию в build/generated.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateKashaIcons)
}
