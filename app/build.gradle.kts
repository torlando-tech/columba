import java.util.Base64

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.chaquo.python")
}

// Parse version from git tag (e.g., v1.2.3 -> versionName "1.2.3", versionCode calculated)
fun getVersionFromTag(): Pair<Int, String> {
    return try {
        val tagName =
            providers.exec {
                commandLine("git", "describe", "--tags", "--exact-match")
            }.standardOutput.asText.get().trim()

        // Parse version from tag (e.g., v1.2.3 or 1.2.3)
        val versionString = tagName.removePrefix("v")
        val parts = versionString.split(".")

        // Calculate versionCode from version numbers (e.g., 1.2.3 -> 10203)
        val versionCode =
            when {
                parts.size >= 3 -> {
                    val major = parts[0].toIntOrNull() ?: 0
                    val minor = parts[1].toIntOrNull() ?: 0
                    val patch = parts[2].split("-")[0].toIntOrNull() ?: 0
                    major * 10000 + minor * 100 + patch
                }
                else -> 1
            }

        Pair(versionCode, versionString)
    } catch (e: Exception) {
        // Not on a tag or git command failed, use defaults
        println("Warning: Could not parse version from git tag, using defaults")
        Pair(1, "1.0.0-dev")
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Chaquopy supports these architectures
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
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

    kotlinOptions {
        jvmTarget = "17"
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

chaquopy {
    defaultConfig {
        version = "3.11"

        pip {
            // Install from local wheels directory
            options("--find-links", "../python/wheels")

            // Install ble-reticulum from local wheel (no external dependencies)
            install("ble-reticulum")

            // Install requirements from requirements.txt
            install("-r", "../python/requirements.txt")
        }

        // Include Python source files (needed for pkgutil.get_data() to deploy BLE interface)
        pyc {
            src = true
        }

        // Extract package files so .py sources are accessible at runtime
        extractPackages("ble_reticulum", "ble_modules")
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
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Crash Reporting - GlitchTip (Sentry-compatible)
    // Phase 4 Task 4.2: Production Observability
    implementation("io.sentry:sentry-android:7.3.0")

    // QR Code & Camera
    implementation(libs.zxing.core)
    implementation(libs.cameraX.core)
    implementation(libs.cameraX.camera2)
    implementation(libs.cameraX.lifecycle)
    implementation(libs.cameraX.view)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.paging.testing)
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
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

// Task to build ble-reticulum wheel from submodule
tasks.register<Exec>("buildBleReticulumWheel") {
    description = "Builds ble-reticulum wheel from submodule"
    group = "build"

    val submoduleDir = file("${project.rootDir}/external/ble-reticulum")
    val wheelsDir = file("${project.rootDir}/python/wheels")
    val distDir = file("$submoduleDir/dist")
    val wheelFile = file("$distDir/ble_reticulum-0.1.0-py3-none-any.whl")

    // Inputs: submodule source files
    inputs.dir("$submoduleDir/src")
    inputs.file("$submoduleDir/pyproject.toml")

    // Outputs: wheel file
    outputs.file(wheelFile)
    outputs.dir(wheelsDir)

    doFirst {
        // Create wheels directory if it doesn't exist
        wheelsDir.mkdirs()

        // Clean old wheels
        distDir.deleteRecursively()
    }

    // Build the wheel
    workingDir = submoduleDir
    commandLine("python3", "-m", "build", "--wheel", "--outdir", "dist/")

    doLast {
        // Copy wheel to python/wheels directory
        copy {
            from(distDir)
            into(wheelsDir)
            include("*.whl")
        }
        println("✓ Built ble-reticulum wheel and copied to python/wheels/")
    }
}

// Run wheel build before Chaquopy's tasks that need the wheel
tasks.named("preBuild") {
    dependsOn("buildBleReticulumWheel")
}

// Ensure wheel is built before Python sources are merged
tasks.configureEach {
    if (name.contains("PythonSources") || name.contains("PythonRequirements")) {
        dependsOn("buildBleReticulumWheel")
    }
}

// Task to print version info for CI/CD
tasks.register("printVersion") {
    doLast {
        println("versionName: ${android.defaultConfig.versionName}")
        println("versionCode: ${android.defaultConfig.versionCode}")
    }
}
