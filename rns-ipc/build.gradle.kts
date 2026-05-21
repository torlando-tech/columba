// :rns-ipc — AIDL adapter between the UI process and the :reticulum process.
//
// Contains the UI-side `RnsBackendClient` (wraps the AIDL proxy in suspend +
// Flow Kotlin shapes) and the host-side `RnsBackendServer` (translates AIDL
// calls into the underlying RnsBackend implementation and pumps its Flows
// back across the binder via observer callbacks). Plus `HandleRegistry` which
// turns long-lived host-side `Link` objects into opaque IDs the UI can pass
// back across the seam.
//
// Depends on :rns-api via api() so consumers transitively get the contract
// types and AIDL-generated stubs without re-declaring the dependency.

plugins {
    id("com.android.library")
    kotlin("plugin.parcelize")
}

android {
    namespace = "network.columba.app.rns.ipc"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Keep desugaring in sync with the rest of the rns-* modules.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Java 8+ core library desugaring runtime (java.time backport for API < 26).
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Backend-seam contract. api() so RnsBackend / sub-interfaces / value types
    // and the AIDL-generated Stub/Proxy classes are visible to :app and :rns-host
    // without an explicit :rns-api dependency in either.
    api(project(":rns-api"))

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
}
