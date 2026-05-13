// :rns-api — pure-JVM contract for the Reticulum Network Stack backend seam.
//
// Defines RnsBackend + sub-interfaces, value types, BackendCapabilities, and the
// typed error envelope. Implemented by :rns-backend-kt and :rns-backend-py.
// Consumed by the UI process (via :rns-ipc's AIDL adapter) and by the host
// service (via :rns-ipc's stub adapter).
//
// No Android dependencies — JVM-only so contract tests run without Robolectric.
// AIDL definitions for the IPC layer live in :rns-ipc, not here.

plugins {
    id("java-library")
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Flow / StateFlow / suspend types appear in the seam contract.
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
}
