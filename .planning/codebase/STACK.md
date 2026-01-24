# Technology Stack

**Analysis Date:** 2026-01-23

## Languages

**Primary:**
- Kotlin 2.2.21 - Main application language (Android app, data layer, reticulum module)
- Java 17 - Compilation target and compatibility
- Python 3.11 - Reticulum networking, LXST voice calls, codec2 audio processing

**Secondary:**
- XML - Android manifest, layout resources, configuration files
- YAML - Gradle configuration, Detekt rules, GitHub Actions workflows

## Runtime

**Environment:**
- Android Runtime (targets API 35, minimum API 24)
- Chaquopy 16.0.0 - Python interpreter embedded in Android app

**Package Manager:**
- Gradle 8.7.3 (wrapper at `./gradlew`)
- Lockfile: `gradle/wrapper/gradle-wrapper.properties`
- pip (for Python dependencies installed via Chaquopy)
- NPM/npm - Not used

## Frameworks

**Core Android:**
- Android SDK 35 (compileSdk)
- AndroidX 1.0+
- Jetpack Compose 1.7.5 - UI framework (338+ Composable functions)

**Architecture & Dependency Injection:**
- Hilt 2.57.2 - Dependency injection framework
- KSP (Kotlin Symbol Processing) 2.2.20-2.0.4 - Code generation for Hilt

**State Management & Data:**
- Room 2.8.4 - SQLite database ORM
- DataStore 1.1.1 - Preferences and settings (encrypted)
- Paging 3.3.6 - List pagination
- Coroutines 1.10.2 - Async/concurrent operations
- Lifecycle 2.8.7 - Lifecycle-aware components

**Navigation:**
- Jetpack Navigation 2.8.4 - Compose navigation

**Testing:**
- JUnit 4.13.2 - Unit test framework
- JUnit Jupiter 5.11.4 - Parameterized test support
- Robolectric 4.16 - Android unit test runtime
- Mockk 1.14.7 - Kotlin mocking library
- Turbine 1.2.1 - Flow testing utilities
- Espresso 3.6.1 - Instrumented UI testing
- JaCoCo 0.8.14 - Code coverage

**Build & Quality:**
- ktlint 1.0.1 - Kotlin code linting
- Detekt 1.23.8 - Static analysis (custom Columba rules in `detekt-rules/`)
- CPD 7.7.0 - Copy-paste detection
- detekt-rules module - Custom Kotlin analysis rules

## Key Dependencies

**Critical:**
- Chaquopy 16.0.0 - Bridges Kotlin and Python for Reticulum mesh networking
- Android Python Wheels - Pre-built arm64 Python packages (pycodec2 4.1.1)
- usb-serial-for-android 3.7.0 - Serial USB support (FTDI, CP210x, PL2303, CH340, CDC-ACM protocols)
- sentry-android 7.3.0 - Crash reporting and observability (GlitchTip compatible)

**Messaging & Networking:**
- Reticulum (RNS 1.1.3) - Fork at `torlando-tech/Reticulum@rebase-1.1.3` with patches for shared instance RPC error handling
- LXMF - Fork at `torlando-tech/LXMF@feature/receiving-interface-capture` with external stamp generator and receiving interface capture
- LXST - Fork at `torlando-tech/LXST@chaquopy-compat` with Chaquopy compatibility patches
- ble-reticulum - Git fork at `torlando-tech/ble-reticulum@main`
- u-msgpack-python - MessagePack serialization for Sideband-compatible telemetry
- MessagePack 0.9.10 (JVM) and msgpack-core 0.9.8 (for LXMF stamp generation)

**Hardware & Sensors:**
- CameraX 1.5.2 (core, camera2, lifecycle, view) - Camera API abstraction
- Google Play Services Location 21.2.0 - FusedLocationProviderClient for location sharing and map features
- MapLibre 11.5.2 - Offline-capable maps
- ZXing 3.5.3 - QR code encoding/decoding
- Lucide Icons 1.1.0 - Icon library

**Media & UI:**
- Coil 2.6.0 - Image loading with GIF animation support
- Material Icons Extended - Additional Material Design icons
- Compose Material3 - Material Design 3 components

**Serialization:**
- kotlinx-serialization-json 1.9.0 - Kotlin JSON serialization (migration export/import)
- org.json 20231013/20240303 - Real JSON for unit tests

**Python Dependencies (installed via Chaquopy):**
- numpy - Audio processing dependency for LXST
- cryptography >=42.0.0 - Encryption for Reticulum
- pycodec2 4.1.1 - Pre-built wheel for audio codec (pure Python ctypes wrapper)
- audioop - Built-in on Python 3.11 (codec audio processing)

## Configuration

**Environment:**
- `.envrc.example` - Template for direnv-based configuration
- Environment variables required for CI/CD:
  - `ANDROID_HOME` - Android SDK location
  - `JAVA_HOME` - Java 17 JDK path
  - `KEYSTORE_FILE` - Base64-encoded keystore for release signing
  - `KEYSTORE_PASSWORD` - Keystore password
  - `KEY_ALIAS` - Release key alias
  - `KEY_PASSWORD` - Key password

**Build:**
- `build.gradle.kts` - Root project configuration with JaCoCo, ktlint, detekt, CPD
- `app/build.gradle.kts` - App module with Compose, Chaquopy, Hilt, testing config
- `data/build.gradle.kts` - Data layer with Room, Hilt, coroutines
- `reticulum/build.gradle.kts` - Reticulum integration with Python, USB serial, BLE
- `gradle/libs.versions.toml` - Centralized dependency versions
- `gradle.properties` - JVM args, parallel builds, Kotlin code style
- `detekt-config.yml` - Static analysis rules (`detekt-baseline.xml` captures pre-existing issues)

**Kotlin Compiler:**
- Target: JVM 17
- Kotlin code style: official
- Compose compiler extension: 1.7.5

**Android Configuration:**
- Namespace: `com.lxmf.messenger`
- Application ID: `com.lxmf.messenger`
- Supported ABIs: arm64-v8a (64-bit only, x86_64 disabled due to pycodec2 wheel resolution)
- Features: Compose enabled, AIDL enabled, BuildConfig enabled
- Resources: R class namespacing enabled, AndroidX enabled

## Platform Requirements

**Development:**
- Android SDK 35 or later
- Java 17 JDK (OpenJDK or compatible)
- Python 3.8-3.11 (build-time, for Chaquopy compatibility detection)
- Gradle 8.7.3
- Git 2.x+ (for version detection via tags)

**Production (Runtime):**
- Android 6.0+ (API 24 minimum)
- Android 15 (API 35 target)
- ARM64 CPU (v8a)
- 100+ MB storage for app and offline maps
- Network: WiFi or mobile data for Reticulum mesh networking

---

*Stack analysis: 2026-01-23*
