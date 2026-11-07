import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ подписи для Google Play лежит ВНЕ репозитория: keystore.properties + upload-key.jks
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ru.screentranslator"
    compileSdk = 36

    defaultConfig {
        // ID приложения в Google Play — после первой публикации менять нельзя
        applicationId = "io.github.alpharusts.screentranslator"
        minSdk = 26          // Android 8.0+
        targetSdk = 36       // требование Google Play с 31.08.2026
        versionCode = 1
        versionName = "1.0"
        // Только процессоры телефонов (без x86 для эмуляторов) — APK меньше
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Распознавание текста на устройстве (китайский + латиница). Бесплатно, модель встроена в APK.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // Перевод на устройстве. Бесплатно, без ключей и подписок, работает офлайн.
    implementation("com.google.mlkit:translate:17.0.3")
}
