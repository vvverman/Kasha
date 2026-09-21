plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Секреты подписи задаются только окружением сборки, не сохраняются в репозитории.
val releaseSigningNames = listOf("KASHA_ANDROID_KEYSTORE", "KASHA_ANDROID_STORE_PASSWORD", "KASHA_ANDROID_KEY_ALIAS", "KASHA_ANDROID_KEY_PASSWORD")
val releaseSigningValues = releaseSigningNames.associateWith { providers.environmentVariable(it).orNull }
val releaseSigningEnabled = releaseSigningValues.values.all { !it.isNullOrBlank() }
require(releaseSigningValues.values.all { it.isNullOrBlank() } || releaseSigningEnabled) {
    "Укажите все четыре KASHA_ANDROID_* параметра подписи либо ни одного"
}

android {
    namespace = "ru.vrmn.kasha.android"
    compileSdk = 37
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "ru.vrmn.kasha"
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 2
        versionName = "1.2.0"
        // Одна STL для обоих JNI-движков; Gradle упаковывает её вместе с APK/AAB.
        externalNativeBuild {
            cmake { arguments.add("-DANDROID_STL=c++_shared") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("production") {
                storeFile = file(releaseSigningValues.getValue("KASHA_ANDROID_KEYSTORE")!!)
                storePassword = releaseSigningValues.getValue("KASHA_ANDROID_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("KASHA_ANDROID_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("KASHA_ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigningEnabled) signingConfig = signingConfigs.getByName("production")
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":kashaCore"))
    implementation(project(":composeApp"))
    implementation(project(":aiCatalog"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
