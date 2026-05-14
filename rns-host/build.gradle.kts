// :rns-host — hosts the :reticulum process and the backend-agnostic runtime.
//
// Owns the `ReticulumService` foreground service, peripheral managers
// (BLE / USB / RNode / flasher / lock / network-change / notifications),
// and LXST voice runtime construction (Telephone / PacketRouter /
// AudioDevice / CallCoordinator).
//
// Per-flavor source sets (`src/kotlinBackend/` and `src/pythonBackend/`)
// supply the variant-specific Hilt module wiring `RnsBackend` to
// either `:rns-backend-kt` (native) or `:rns-backend-py` (Chaquopy).
//
// The UI process never depends on `:rns-host` on its compile classpath;
// only the `:reticulum`-process service it hosts and the manifest entry
// it merges into `:app`.

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    kotlin("plugin.parcelize")
    kotlin("plugin.serialization")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "network.columba.app.rns.host"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Mirror :reticulum: armeabi-v7a + arm64-v8a + x86_64 (LXST native ships these).
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    // Mirror :app's `rnsImpl` dimension so Gradle can match a `:rns-host` variant
    // for each `:app` consumer variant. Per-flavor source sets under
    // `src/{kotlinBackend,pythonBackend}/kotlin/` carry the backend-specific
    // Hilt module wiring `RnsBackend` to the chosen implementation.
    flavorDimensions += "rnsImpl"
    productFlavors {
        create("kotlinBackend") {
            dimension = "rnsImpl"
            isDefault = true
        }
        create("pythonBackend") {
            dimension = "rnsImpl"
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
    // AIDL adapters (transitively brings in :rns-api contract types).
    api(project(":rns-ipc"))

    // Native Kotlin RnsBackend impl — only on the kotlinBackend flavor's compile
    // classpath. `HostBackendModule.kt` in src/kotlinBackend/ uses Hilt @Provides
    // to surface NativeRnsBackend into the :reticulum-process graph and to feed
    // the RNodeHostBridge adapter that wraps KotlinUSBBridge / BluetoothLeConnection.
    "kotlinBackendImplementation"(project(":rns-backend-kt"))

    // Chaquopy (upstream Python RNS/LXMF) RnsBackend impl — only on the
    // pythonBackend flavor's compile classpath. `HostBackendModule.kt` in
    // src/pythonBackend/ Hilt-provides ChaquopyRnsBackend and wires the LXST
    // NetworkTransport adapter through PythonRnsRuntime. The kotlinBackend
    // flavor never sees the Chaquopy runtime — that's the whole point of
    // applying the chaquopy plugin at :rns-backend-py module level.
    "pythonBackendImplementation"(project(":rns-backend-py"))

    // Hilt
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // LXST voice runtime (Telephone, PacketRouter, AudioDevice, CallCoordinator).
    api(libs.lxst.kt)

    // Reticulum-kt + LXMF-kt — peripherals (RNode, BLE) reach into RNS internals
    // and `NativeRnsBackend` (A.8) will sit alongside.
    api(libs.rns.core)
    api(libs.rns.interfaces)
    api(libs.rns.android)
    api(libs.lxmf.kt)

    // USB serial — KotlinUSBBridge wraps mik3y's lib for RNode-over-USB.
    api("com.github.mik3y:usb-serial-for-android:3.7.0")

    // MessagePack — RNode KISS frames + LXMF field encoding.
    implementation(libs.msgpack)

    // JSON serialization — firmware manifest parsing in the flasher subsystem.
    implementation(libs.serialization.json)

    // Room runtime — service-side access to Reticulum stores.
    implementation(libs.room)

    // Room DAOs + entities — ServicePersistenceManager writes announces / messages
    // directly from the :reticulum process.
    implementation(project(":data"))

    // Crash reporting — KotlinBLEBridge metrics already wire through Sentry.
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

    androidTestImplementation(libs.junit.android)
    androidTestImplementation(libs.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
}

ksp {
    arg("correctErrorTypes", "true")
}
