import java.util.Base64

// Helper function to find a compatible Python version for Chaquopy build
fun findCompatiblePython(): String {
    // Try compatible Python versions in order of preference
    val compatibleVersions = listOf("python3.11", "python3.10", "python3.9", "python3.8")

    for (pyCmd in compatibleVersions) {
        try {
            val result = Runtime.getRuntime().exec(arrayOf("which", pyCmd))
            result.waitFor()
            if (result.exitValue() == 0) {
                val path = result.inputStream.bufferedReader().readText().trim()
                if (path.isNotEmpty()) {
                    println("Using $pyCmd for Chaquopy build: $path")
                    return pyCmd
                }
            }
        } catch (e: Exception) {
            // Continue to next version
        }
    }

    // Fall back to python3 (may fail if incompatible version)
    println("Warning: No compatible Python 3.8-3.11 found, using default python3")
    return "python3"
}

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.chaquo.python")
    id("io.sentry.android.gradle")
}

// Parse version from git tag (e.g., v1.2.3 -> versionName "1.2.3", versionCode calculated)
// New scheme: major * 10M + minor * 100K + patch * 1K (+ commits for dev builds)
fun getVersionFromTag(): Pair<Int, String> {
    return try {
        // Try exact tag match first (release build)
        val tagName =
            providers.exec {
                commandLine("git", "describe", "--tags", "--exact-match")
            }.standardOutput.asText.get().trim()

        val versionString = tagName.removePrefix("v")
        val parts = versionString.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.split("-")?.get(0)?.toIntOrNull() ?: 0

        // New scheme: major * 10M + minor * 100K + patch * 1K
        val versionCode = major * 10_000_000 + minor * 100_000 + patch * 1_000
        Pair(versionCode, versionString)
    } catch (e: Exception) {
        // Not on exact tag - get nearest tag + commit count
        try {
            val describe =
                providers.exec {
                    commandLine("git", "describe", "--tags", "--long")
                }.standardOutput.asText.get().trim()

            // Format: v0.6.4-beta-5-g1234abc or v0.6.4-5-g1234abc
            val parts = describe.removePrefix("v").split("-")
            val versionPart = parts[0] // "0.6.4"

            // Find commit count (second-to-last element before git hash)
            val commitCount = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 0

            val versionParts = versionPart.split(".")
            val major = versionParts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = versionParts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = versionParts.getOrNull(2)?.toIntOrNull() ?: 0

            val versionCode = major * 10_000_000 + minor * 100_000 + patch * 1_000 + commitCount
            val versionName = "$major.$minor.$patch.${commitCount.toString().padStart(4, '0')}-dev"

            println("Dev build: $versionName (versionCode=$versionCode)")
            Pair(versionCode, versionName)
        } catch (e2: Exception) {
            // No tags at all - fallback
            println("Warning: No git tags found, using fallback version")
            Pair(1_000_000, "0.0.0-dev")
        }
    }
}

fun getGitCommitHash(): String {
    return try {
        providers.exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
        }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

val (versionCodeValue, versionNameValue) = getVersionFromTag()

android {
    namespace = "com.lxmf.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lxmf.messenger"
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionNameValue

        buildConfigField("String", "GIT_COMMIT_HASH", "\"${getGitCommitHash()}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Python 3.11 supports 64-bit ABIs
            // TODO: x86_64 disabled until pycodec2 wheel resolution issue is fixed
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "telemetry"

    productFlavors {
        create("sentry") {
            dimension = "telemetry"
            // SENTRY_DSN from environment - Sentry enabled in release builds
            buildConfigField("String", "SENTRY_DSN", "\"${System.getenv("SENTRY_DSN") ?: ""}\"")
        }
        create("noSentry") {
            dimension = "telemetry"
            // Empty DSN - Sentry fully disabled (no init, no network calls)
            buildConfigField("String", "SENTRY_DSN", "\"\"")
            // Suffix to distinguish APK in app drawer
            applicationIdSuffix = ".nosentry"
        }
    }

    lint {
        // Workaround for lint crash with Kotlin 2.x
        // https://issuetracker.google.com/issues/344341744
        disable += "NullSafeMutableLiveData"
    }

    // Track whether release signing is configured
    val releaseSigningConfigured =
        run {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")

            keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null
        }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                // Keystore configuration from environment variables (set in CI/CD)
                val keystoreFile = System.getenv("KEYSTORE_FILE")!!
                val keystorePassword = System.getenv("KEYSTORE_PASSWORD")!!
                val keyAlias = System.getenv("KEY_ALIAS")!!
                val keyPassword = System.getenv("KEY_PASSWORD")!!

                try {
                    // Decode base64 keystore file and save temporarily
                    val keystoreDir = file("${layout.buildDirectory.get().asFile}/keystore")
                    keystoreDir.mkdirs()
                    val decodedKeystore = file("$keystoreDir/release.keystore")

                    // Remove whitespace and newlines from base64 string before decoding
                    val cleanedKeystoreFile = keystoreFile.replace("\\s".toRegex(), "")
                    decodedKeystore.writeBytes(Base64.getDecoder().decode(cleanedKeystoreFile))

                    storeFile = decodedKeystore
                    storePassword = keystorePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword

                    println("✓ Release signing configured from environment variables")
                } catch (e: IllegalArgumentException) {
                    throw GradleException(
                        "Failed to decode KEYSTORE_FILE: ${e.message}\n" +
                            "Please regenerate the KEYSTORE_FILE secret with valid base64 encoding.\n" +
                            "To encode: base64 -w 0 your-keystore.jks",
                    )
                }
            }
        } else {
            println("⚠ Release signing not configured (missing environment variables)")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("Boolean", "USE_RUST", "false")
        }
        debug {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("Boolean", "USE_RUST", "false")
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
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
        compose = true
        buildConfig = true
        aidl = true // Enable AIDL support for IPC
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "2048m"
                // Enable JaCoCo coverage for Robolectric tests
                it.extensions.configure<JacocoTaskExtension> {
                    isIncludeNoLocationClasses = true
                    // Required for Java 9+ compatibility
                    excludes = listOf("jdk.internal.*")
                }
                // Show test progress in CI logs
                it.testLogging {
                    events("passed", "skipped", "failed")
                    showStandardStreams = false
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    sourceSets {
        getByName("androidTest") {
            manifest.srcFile("src/androidTest/AndroidManifest.xml")
        }
    }
}

// Sentry Gradle Plugin configuration
sentry {
    // Disable auto-upload of ProGuard mappings (not using ProGuard obfuscation)
    autoUploadProguardMapping.set(false)

    // Enable source context for readable stack traces
    includeSourceContext.set(true)

    // Logcat integration - captures android.util.Log calls as breadcrumbs
    tracingInstrumentation {
        enabled.set(true)

        logcat {
            enabled.set(true)
            minLevel.set(io.sentry.android.gradle.instrumentation.logcat.LogcatLevel.WARNING)
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"

        pip {
            // Install pre-built pycodec2 wheel for arm64
            // Uses pure Python ctypes wrapper (not Cython) to avoid Android linker namespace
            // symbol resolution issues with Python C API symbols like PyExc_RuntimeError
            // audioop is built-in on Python 3.11, no external wheel needed
            install("https://github.com/torlando-tech/android-python-wheels/releases/download/v1.1.0/pycodec2-4.1.1-cp311-cp311-android_21_arm64_v8a.whl")

            // Install ble-reticulum from GitHub
            install("git+https://github.com/torlando-tech/ble-reticulum.git@main")

            // Install requirements from requirements.txt (includes LXST which has pycodec2 removed)
            install("-r", "../python/requirements.txt")
        }

        // Include Python source files (needed for pkgutil.get_data() to deploy BLE interface)
        pyc {
            // Temporarily disable for local builds with Python 3.14+
            // Re-enable when Python 3.11 is available or Chaquopy supports 3.14
            src = false // was: true
        }

        // Extract package files so .py sources are accessible at runtime
        // pycodec2 needs to be extracted so libcodec2.so can be loaded at runtime
        extractPackages("ble_reticulum", "ble_modules", "pycodec2")
    }

    sourceSets {
        getByName("main") {
            srcDir("../python")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":reticulum"))

    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.compose.activity)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.composables:icons-lucide-android:1.1.0")
    debugImplementation(libs.compose.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation(libs.navigation)

    // Paging
    implementation(libs.paging.compose)

    // Hilt
    implementation(libs.hilt)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // DataStore
    implementation(libs.datastore)

    // Serialization (for migration export/import)
    implementation(libs.serialization.json)

    // Room
    implementation(libs.room)
    ksp(libs.room.compiler)

    // Crash Reporting - GlitchTip (Sentry-compatible)
    // Phase 4 Task 4.2: Production Observability
    implementation("io.sentry:sentry-android:8.29.0")

    // QR Code & Camera
    implementation(libs.zxing.core)
    implementation(libs.cameraX.core)
    implementation(libs.cameraX.camera2)
    implementation(libs.cameraX.lifecycle)
    implementation(libs.cameraX.view)

    // Coil - Image loading with GIF animation support
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    // MessagePack - for LXMF stamp generation
    implementation("org.msgpack:msgpack-core:0.9.8")

    // MapLibre - for offline-capable maps
    implementation("org.maplibre.gl:android-sdk:11.5.2")

    // Google Play Services Location - for FusedLocationProviderClient
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.test)
    testImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.paging.testing)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.json:json:20231013") // Real JSON implementation for unit tests
    androidTestImplementation(libs.junit.android)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.test.services)
    androidTestUtil(libs.test.orchestrator)
}

ksp {
    arg("correctErrorTypes", "true")
}

// Task to print version info for CI/CD
tasks.register("printVersion") {
    doLast {
        println("versionName: ${android.defaultConfig.versionName}")
        println("versionCode: ${android.defaultConfig.versionCode}")
    }
}
