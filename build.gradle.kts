import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
}

// Системные аудио-тесты CI выполняются на полностью загруженном iPhone.
// Без переменной среды обычные сборки и выбор симулятора остаются прежними.
val iosTestDevice = providers.environmentVariable("KASHA_IOS_TEST_DEVICE")
subprojects {
    tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        if (iosTestDevice.isPresent) {
            device.set(iosTestDevice)
            standalone.set(false)
        }
    }
}
