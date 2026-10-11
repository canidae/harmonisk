import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    kotlin("plugin.serialization")
}

val releaseDate = LocalDate.now(ZoneOffset.UTC)
val versionNameValue = releaseDate.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
val versionBuild = (findProperty("versionBuild") as String?)?.toIntOrNull() ?: 1
val versionCodeValue = releaseDate.format(DateTimeFormatter.ofPattern("yyMMdd")).toInt() * 100 + versionBuild
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "net.exent.harmonisk"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "net.exent.harmonisk"
        minSdk = 29
        targetSdk = 37
        versionCode = versionCodeValue
        versionName = versionNameValue

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            merges += "**/*.tflite"
            merges += "**/*.sf2"
        }
    }
    aaptOptions {
        noCompress += listOf("tflite", "sf2")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Core
    implementation("androidx.core:core-ktx:1.19.0")

    // TensorFlow Lite — CREPE pitch detection model
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // Celebration — Konfetti particle physics
    implementation("nl.dionsegijn:konfetti-compose:2.0.5")

    // Sheet music rendering
    implementation("net.alphatab:alphaTab:1.6.1")
    // Force 16 KB aligned alphaSkia, otherwise libalphaskiajni.so has p_align 4096
    implementation("net.alphatab:alphaSkia-android:3.5.147")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.11")
}




