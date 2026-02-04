plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
    kotlin("plugin.parcelize")
    id("com.google.dagger.hilt.android")
    id("com.chaquo.python")
}

android {
    namespace = "tech.torlando.columba.reticulum"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Python 3.11 supports 64-bit ABIs
            // TODO: x86_64 disabled until pycodec2 wheel resolution issue is fixed
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

dependencies {
    // Hilt
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // USB Serial for Android (mik3y) - handles FTDI, CP210x, PL2303, CH340, CDC-ACM protocols
    // Use api() so KotlinUSBBridge types are accessible to app module
    api("com.github.mik3y:usb-serial-for-android:3.7.0")

    // MessagePack
    implementation(libs.msgpack)

    // Opus codec - for voice/audio encoding in Kotlin LXST
    implementation("cn.entertech.android:wuqi-opus:1.0.3")

    // Codec2 codec - for ultra-low-bitrate voice encoding (700-3200 bps)
    implementation(project(":external:codec2_talkie:libcodec2-android"))

    // Crash Reporting - Sentry (for KotlinBLEBridge metrics)
    implementation("io.sentry:sentry-android:8.29.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation("org.json:json:20240303")
}

ksp {
    arg("correctErrorTypes", "true")
}
