// :rns-backend-kt — native Kotlin RnsBackend implementation.
//
// Owns the `NativeRnsBackend` root impl + six sub-interface impls
// (`NativeRnsCore`, `NativeRnsLxmf`, `NativeRnsTelephony`, `NativeRnsTelemetry`,
// `NativeRnsNomadnet`, `NativeRnsTransportAdmin`) over reticulum-kt + lxmf-kt.
// LXST `NetworkTransport` binding lives here; full LXST runtime hosting
// (Telephone / PacketRouter / AudioDevice / CallCoordinator) lives in
// `:rns-host` and is bridged in via `LxstCallBridge` so the dep edge stays
// `:rns-host → :rns-backend-kt` (never the reverse).
//
// Dependency rule: `implementation(:rns-api)` only. No `:rns-host` dep.

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    kotlin("plugin.parcelize")
    kotlin("plugin.serialization")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "network.columba.app.rns.backend.kt"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Mirror :rns-host: armeabi-v7a + arm64-v8a + x86_64 (LXST native ships these).
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        // Expose the pinned library versions to Kotlin source so NativeCapabilities
        // can report them via the RnsBackend.capabilities StateFlow without hardcoding.
        // Sourced from libs.versions.toml — same fields :reticulum exposes today.
        buildConfigField("String", "RNS_KT_VERSION", "\"${libs.versions.reticulumKt.get().removePrefix("v")}\"")
        buildConfigField("String", "LXMF_KT_VERSION", "\"${libs.versions.lxmfKt.get().removePrefix("v")}\"")
        buildConfigField("String", "LXST_KT_VERSION", "\"${libs.versions.lxstKt.get().removePrefix("v")}\"")
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Backend-seam contract (value types, capabilities, interfaces). Implementation
    // scope only — consumers reach value types through `:rns-api` directly via
    // `:reticulum`'s `api()` edge today, and through the `:rns-ipc` adapter
    // surface after A.10.
    implementation(project(":rns-api"))

    // Hilt
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Native Reticulum/LXMF/LXST Kotlin stack — the actual protocol impl.
    api(libs.rns.core)
    api(libs.rns.interfaces)
    api(libs.rns.android)
    api(libs.lxmf.kt)
    api(libs.lxst.kt)

    // MessagePack — LXMF field encoding + RNode KISS frames.
    implementation(libs.msgpack)

    // Serialization (for LXMF field map JSON encoding).
    implementation(libs.serialization.json)

    // Room runtime (native process access to Reticulum stores).
    implementation(libs.room)

    // Crash Reporting - Sentry (for instrumented metrics).
    implementation("io.sentry:sentry-android:8.31.0")

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
