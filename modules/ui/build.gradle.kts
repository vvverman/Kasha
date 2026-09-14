import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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

val generateKashaIcons by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Generate Compose runtime geometry from the canonical Kasha Icons registry"
    workingDir(rootProject.projectDir)
    val python = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
    commandLine(python, "scripts/generate-kasha-icons.py")
    inputs.file(rootProject.file("docs/design/icons/registry.json"))
    inputs.file(rootProject.file("scripts/generate-kasha-icons.py"))
    outputs.file(project.file("src/commonMain/kotlin/brain/studio/ui/GeneratedKashaIcons.kt"))
}

kotlin {
    jvm()
    jvmToolchain(21)

    android {
        namespace = "ru.vrmn.kasha.ui"
        compileSdk = 37
        minSdk = 26
    }

    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
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
    }
}

tasks.configureEach {
    if (name.startsWith("compileKotlin") || name.contains("KotlinMetadata")) {
        dependsOn(generateKashaIcons)
    }
}
