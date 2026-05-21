// :rns-api — Backend-seam contract for the Reticulum Network Stack.
//
// Defines RnsBackend + sub-interfaces, value types, BackendCapabilities, the
// typed RnsError envelope, AND the AIDL definitions that mirror the contract
// across the UI ↔ :reticulum process boundary. Implemented by
// :rns-backend-kt and :rns-backend-py; consumed by the UI process (via
// :rns-ipc's adapter) and the host service (via :rns-ipc's stub).
//
// AIDL definitions live HERE (not in :rns-ipc) so the contract has a single
// source of truth: the Kotlin interfaces and the AIDL surface they marshal
// to live next to each other and version together.
//
// This is a com.android.library — required to host the AIDL build feature
// and the @Parcelize annotation. Tests use Robolectric for Parcel round-trip
// coverage; pure-Kotlin tests (capability shape, error envelope) work too.

plugins {
    id("com.android.library")
    kotlin("plugin.parcelize")
}

android {
    namespace = "network.columba.app.rns.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Keep desugaring in sync with downstream modules so any future
        // java.time usage compiles + runs on minSdk 24.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        aidl = true
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

    // Flow / StateFlow / suspend types appear in the seam contract.
    implementation(libs.coroutines.core)

    // org.json.JSONObject is used by InterfaceConfigExt.toJsonString() — the
    // serialization concern lives with the type rather than with the storage
    // layer (:data) so consumers don't need a separate dep just to round-trip
    // an interface config.
    //
    // compileOnly, NOT implementation: org.json ships in the Android framework
    // (bootclasspath), so it's present at runtime for free. Bundling the
    // reference `org.json:json` artifact into the APK creates a DUPLICATE
    // org.json on the classpath whose method signatures differ from the
    // framework's. R8 (optimize/obfuscate) then binds call sites against the
    // bundled copy, but the framework copy wins at runtime — producing
    // NoSuchMethodError (e.g. JSONObject.optInt) and breaking Chaquopy's
    // build.json walk (isinstance mismatch), which crashed the minified
    // pythonBackend release. Keep it off the runtime classpath.
    compileOnly("org.json:json:20240303")

    // msgpack-core powers `util.AppDataParser` — the announce app_data
    // parser is consumed by every backend (kotlin native + python flavor)
    // and lives here so display-name / stamp-cost / propagation-meta
    // parsing has one source of truth across both implementations.
    implementation(libs.msgpack)

    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    // org.json is compileOnly above (Android provides it at runtime), so the
    // real impl must be on the JVM unit-test classpath — otherwise toJsonString()
    // hits the android.jar "Stub!" org.json. Matches the other modules' test deps.
    testImplementation("org.json:json:20240303")
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
}
