plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "tech.torlando.lxst"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Opus codec - libopus built from source (caller-allocated buffers, no 1024-sample limit)
    implementation(project(":external:codec2_talkie:libopus-android"))

    // Codec2 codec - for ultra-low-bitrate voice encoding (700-3200 bps)
    implementation(project(":external:codec2_talkie:libcodec2-android"))

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation("org.json:json:20240303")

    // Instrumented testing (androidTest)
    androidTestImplementation(libs.junit.android)
    androidTestImplementation(libs.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
