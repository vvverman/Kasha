plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "ru.vrmn.kasha.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.vrmn.kasha"
        minSdk = 26
        targetSdk = 37
        versionCode = 120
        versionName = "1.2.0"
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }
}

dependencies {
    implementation(project(":kashaCore"))
    implementation(project(":aiCatalog"))
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
