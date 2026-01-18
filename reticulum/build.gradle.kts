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

    // MessagePack
    implementation(libs.msgpack)

    // Crash Reporting - Sentry (for KotlinBLEBridge metrics)
    implementation("io.sentry:sentry-android:7.3.0")

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
