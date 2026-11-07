plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.screentranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.screentranslator"
        minSdk = 26          // Android 8.0+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Только процессоры телефонов (без x86 для эмуляторов) — APK меньше
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Распознавание текста на устройстве (китайский + латиница). Бесплатно, модель встроена в APK.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // Перевод на устройстве. Бесплатно, без ключей и подписок, работает офлайн.
    implementation("com.google.mlkit:translate:17.0.3")
}
