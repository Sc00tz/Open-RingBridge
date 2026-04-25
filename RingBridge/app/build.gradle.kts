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
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
}
