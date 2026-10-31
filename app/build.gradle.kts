plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.topoar"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.topoar"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }

    signingConfigs {
        // Один и тот же ключ для всех сборок — APK обновляется поверх, без удаления
        create("shared") {
            storeFile = rootProject.file("keystore/topoar-debug.jks")
            storePassword = "topoar123"
            keyAlias = "topoar"
            keyPassword = "topoar123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.ar:core:1.44.0")
}
