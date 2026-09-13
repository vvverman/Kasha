plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "ru.vrmn.kasha.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.vrmn.kasha"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.1.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
