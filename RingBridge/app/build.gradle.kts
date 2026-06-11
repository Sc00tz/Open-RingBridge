import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.ringbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ringbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        // versionName carries a build timestamp so the installed build is identifiable
        // at a glance in Android's App Info screen (e.g. "1.0 (2026-06-11 07:45)").
        // Stamped at configuration time from the local clock on each build.
        val buildStamp = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
        versionName = "1.0 ($buildStamp)"
    }

    buildTypes {
        release {
            // Minification is off for now. The keep rules in proguard-rules.pro are
            // already wired in so flipping this to true is safe when desired.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle (LifecycleService for RingService)
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room (SQLite)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // OkHttp (server REST API)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ZXing — QR code scanner for device setup
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // MPAndroidChart — sensor history graphs
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Nordic BLE Library — handles GATT complexity (INDICATE/NOTIFY, descriptor writes, queuing)
    implementation("no.nordicsemi.android:ble-ktx:2.7.5")

    // ── Unit tests (JVM — no emulator needed) ─────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
