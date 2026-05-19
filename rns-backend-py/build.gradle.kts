// :rns-backend-py — Chaquopy (upstream Python RNS/LXMF) RnsBackend implementation.
//
// Owns the `ChaquopyRnsBackend` root impl + six sub-interface impls
// (`PythonRnsCore`, `PythonRnsLxmf`, `PythonRnsTelephony`, `PythonRnsTelemetry`,
// `PythonRnsNomadnet`, `PythonRnsTransportAdmin`). Each holds the shared
// `PythonRnsRuntime` and calls upstream RNS/LXMF methods directly via
// `PyObject.callAttr(...)` — Python is the protocol stack, not a wrapper layer.
//
// SLIM-PYTHON DISCIPLINE (see CLAUDE.md in this module):
//   - The Python tree contains ONLY upstream RNS/LXMF wheels + the
//     architecturally-forced interface adapters (BLE/RNode/USB `RNS.Interface`
//     subclasses) + Chaquopy env stubs + the ~50-line `event_bridge.py`
//     callback receiver.
//   - There is NO `rns_*.py` facade and no `reticulum_wrapper.py`. App-logic
//     helpers live in Kotlin in `:rns-host` and are shared by both backends.
//     Re-adding a Python facade is a regression caught by the
//     `NoRnsFacadeInPythonBackend` Detekt rule.
//
// The `com.chaquo.python` plugin is applied HERE, at module level — never at
// `:app` level — so the kotlinBackend flavor's compile/runtime classpath is
// never polluted with the Chaquopy runtime or Python wheels. This module only
// lands on the classpath through `:rns-host`'s `pythonBackendImplementation`
// edge.
//
// Dependency rule: `implementation(:rns-api)` for the contract + `api(lxst.kt)`
// for the telephony value types only. No `:rns-host` dep (the host wires this
// in via its pythonBackend-flavor Hilt module).

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    kotlin("plugin.parcelize")
    kotlin("plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.chaquo.python")
}

android {
    namespace = "network.columba.app.rns.backend.py"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Mirror :rns-host / :rns-backend-kt: armeabi-v7a + arm64-v8a + x86_64.
            // Chaquopy ships its CPython runtime + native wheels (cryptography)
            // for exactly these ABIs.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        // BuildConfig surfaces the pinned upstream wheel versions to Kotlin so
        // PythonCapabilities can report them via RnsBackend.capabilities without
        // a runtime PyObject round-trip. Keep in sync with PINNED_VERSIONS.md.
        // Display strings for the About card — kept short (the pinning SHAs live
        // in PINNED_VERSIONS.md, not the user-facing version line). RNS and LXMF
        // ARE torlando-tech forks of markqvist's upstream; ble-reticulum is
        // torlando-tech's own project, so it carries no "fork" label.
        buildConfigField("String", "PY_RNS_VERSION", "\"1.1.9 (torlando-tech fork)\"")
        buildConfigField("String", "PY_LXMF_VERSION", "\"0.9.2 (torlando-tech fork)\"")
        buildConfigField("String", "PY_BLE_RETICULUM_VERSION", "\"0.2.2\"")
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

// Chaquopy: upstream Python RNS/LXMF as the protocol stack for the python flavor.
//
// Wheel pins live in PINNED_VERSIONS.md (and are mirrored in the buildConfig
// fields above). Pin to commit SHA, not branch tip, wherever the upstream ref
// can be resolved — matches release/v0.10.x's reproducibility discipline.
chaquopy {
    defaultConfig {
        // Target CPython bundled into the APK. Build host needs a matching
        // python3.11 on PATH for pip resolution; CI provisions it.
        version = "3.11"

        pip {
            // Upstream RNS — torlando-tech fork pinned to commit SHA. This commit
            // already carries the four fork patches incl. the context-manager
            // ratchet/log file-handle fixes, so the v0.10.x `patches/RNS/` tree
            // is intentionally NOT restored (its runtime patch-deployer lived in
            // the deleted reticulum_wrapper.py — see PINNED_VERSIONS.md).
            install("git+https://github.com/torlando-tech/Reticulum@99c42fce06bc8afe8cfd0107acd990d8de428013")

            // Upstream LXMF — torlando-tech fork (external stamp generator +
            // receiving-interface capture). Pinned to the commit SHA at the
            // tip of feature/receiving-interface-capture for reproducibility.
            install("git+https://github.com/torlando-tech/LXMF@158b771c4c65ff43fd25764b00f5555ea543ab2e")

            // ble-reticulum — RNS.Interface subclass for the Android BLE bridge.
            // Pinned to the commit SHA at the tip of main for reproducibility.
            install("git+https://github.com/torlando-tech/ble-reticulum.git@07d941304c9a1dc3a8e58087b3b974ff3d229e56")

            install("cryptography>=42.0.0")

            // msgpack — Sideband-compatible telemetry + LXST signalling wire format.
            install("u-msgpack-python")
        }

        // .pyc precompilation requires an exact-minor buildPython on every build
        // host. Ship source form instead — keeps contributor environments simple
        // and is required anyway for RNS.Interface discovery via pkgutil.
        pyc {
            src = false
        }

        // Keep .py sources extractable at runtime: upstream RNS interface
        // discovery (Transport.find_interfaces) and pkgutil.get_data() both read
        // the bundled BLE adapter sources off disk.
        extractPackages("ble_reticulum", "ble_modules")
    }
}

dependencies {
    // Backend-seam contract (value types, capabilities, sub-interfaces).
    implementation(project(":rns-api"))

    // Hilt
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    // Coroutines — every PyObject call is wrapped in withContext(Dispatchers.IO)
    // to keep the GIL off the caller's thread.
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // LXST-kt — telephony value types only (CallState / CallCoordinator). The
    // python flavor runs voice on LXST-kt exactly like the kotlin flavor; the
    // CallCoordinator instance is constructed in :rns-host and passed in
    // (this module is NOT on the NoCallCoordinatorGetInstanceOutsideHost
    // allowlist — it must never call getInstance() itself).
    api(libs.lxst.kt)

    // Serialization — flat-dict JSON marshalling for event-bridge payloads.
    implementation(libs.serialization.json)

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
