# External Integrations

**Analysis Date:** 2026-01-23

## APIs & External Services

**Reticulum Mesh Network:**
- Service: Reticulum (RNS) - Decentralized wireless networking protocol
- What it's used for: Low-bandwidth mesh networking backbone for LXMF messaging, location sharing, voice calls
- SDK/Client: Fork at `github.com/torlando-tech/Reticulum@fix/socket-leak-1.1.3`
- Integration: Python library embedded via Chaquopy, initialized by `ReticulumService`

**LXMF Protocol:**
- Service: Long-Range eXtensible Message Format - High-level messaging protocol built on Reticulum
- What it's used for: Encrypted message delivery, announce/discovery, delivery receipts
- SDK/Client: Fork at `github.com/torlando-tech/LXMF@feature/receiving-interface-capture`
- Integration: Python library with external stamp generator for Android (bypasses multiprocessing), initialized in app startup

**BLE (Bluetooth Low Energy) Reticulum Interface:**
- Service: Reticulum BLE transports - Bluetooth mesh networking
- What it's used for: Direct BLE communication between Android devices, no WiFi required
- SDK/Client: Fork at `github.com/torlando-tech/ble-reticulum@main`
- Auth: Device pairing via Bluetooth link negotiation
- Integration: Python library + KotlinBLEBridge at `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/bridge/KotlinBLEBridge.kt`

**LXST (Voice Calls):**
- Service: Long-Range eXtensible Telephony - Voice communication over Reticulum
- What it's used for: Low-bandwidth audio calls with codec2 compression
- SDK/Client: Fork at `github.com/torlando-tech/LXST@chaquopy-compat`
- Integration: Python library with Chaquopy-compatible patches
- Auth: Uses Reticulum identity authentication

**Location Services:**
- Service: Google Play Services Location (FusedLocationProviderClient)
- What it's used for: GPS location acquisition for location sharing feature and map centering
- Client: `com.google.android.gms:play-services-location:21.2.0`
- Request type: LocationRequest with configured priority and interval
- Usage: `LocationSharingManager` (`app/src/main/java/com/lxmf/messenger/service/LocationSharingManager.kt`) and `TelemetryCollectorManager` manage requests

**Maps:**
- Service: MapLibre GL Android (OpenFreeMap tiles)
- What it's used for: Offline-capable map display with peer locations, offline map regions downloadable
- Client: `org.maplibre.gl:android-sdk:11.5.2`
- Usage: `MapScreen` at `app/src/main/java/com/lxmf/messenger/ui/screens/MapScreen.kt`
- HTTP Control: `MAP_SOURCE_HTTP_ENABLED` setting controls HTTP tile fetching (default: enabled)
- Tiles: OpenFreeMap provides tiles over HTTP/HTTPS

**QR Codes:**
- Service: ZXing (Zebra Crossing) - QR code generation and scanning
- What it's used for: Identity QR code generation for sharing, scanning shared identities
- Client: `com.google.zxing:core:3.5.3`
- Integration: CameraX pipeline for real-time scanning

## Data Storage

**Databases:**
- Provider: Room (SQLite on Android)
- Location: App-private database files (encrypted by Android)
- Client: `androidx.room:room-runtime:2.8.4`
- Compiler: KSP-based code generation

**Primary Database Entities:**
- `InterfaceDatabase` at `app/src/main/java/com/lxmf/messenger/data/database/InterfaceDatabase.kt` - Reticulum interface configs
- Core entities in `data/src/main/java/com/lxmf/messenger/data/db/entity/`:
  - `ContactEntity` - Peer contacts
  - `ConversationEntity` - Message threads
  - `MessageEntity` - Individual messages
  - `LocalIdentityEntity` - User identities
  - `PeerIdentityEntity` - Peer identities
  - `PeerIconEntity` - Peer avatar icons (dedicated table for reliable persistence)
  - `AnnounceEntity` - Network announcements
  - `ReceivedLocationEntity` - Shared peer locations
  - `OfflineMapRegionEntity` - Downloaded offline map regions
  - `RmspServerEntity` - RMSP relay server configurations
  - `CustomThemeEntity` - User theme customizations

**File Storage:**
- Approach: Local filesystem only
- Android internal storage (private app data)
- Offline map tiles cached in app-private directories
- Backup: DataStore for encrypted settings, Room handles identity/message backup via Android Backup Service

**Settings Storage:**
- Provider: DataStore (encrypted preferences)
- Location: App-private encrypted file
- Client: `androidx.datastore:datastore-preferences:1.1.1`
- Key Settings:
  - `MAP_SOURCE_HTTP_ENABLED` - Controls HTTP tile source (true by default)
  - `HTTP_ENABLED_FOR_DOWNLOAD` - Allows HTTP for offline map downloads
  - `LOCATION_SHARING_ENABLED` - User location sharing master toggle
  - `LOCATION_PRECISION_RADIUS` - Obfuscation radius in meters

**Caching:**
- Approach: In-memory (Kotlin Flow, coroutine state)
- Image caching: Coil with GIF animation support
- Map tiles: MapLibre handles caching

## Authentication & Identity

**Auth Provider:**
- Approach: Custom Reticulum-based identity system
- Implementation: Each user creates or imports an LXMF identity (256-bit key material)
- Identity sharing: Deep link `lxma://` URIs for identity exchange
- No centralized auth - identities are self-sovereign and verified via Reticulum signature validation

**Credentials:**
- Storage: Encrypted in Room database (LocalIdentityEntity)
- Transfer: Encrypted identity files can be exported/imported via migration system
- Signature verification: LXMF protocol validates identity signatures on messages

## Monitoring & Observability

**Error Tracking:**
- Service: Sentry (GlitchTip compatible)
- Client: `io.sentry:sentry-android:7.3.0` (both app and reticulum modules)
- Configuration: Manual initialization in Application class (auto-init disabled in `AndroidManifest.xml`)
- DSN: Configured via Sentry initialization (not checked into repo)
- Usage: Crash reporting, exception tracking, KotlinBLEBridge metrics

**Logs:**
- Approach: Android Log (logcat) with contextual logging
- Files:
  - GW2 Debug Log: App logs to standard Android system
  - No persistent file logging in production (logs available via adb logcat)
- Log levels: Controlled by tag filters

**Analytics:**
- Approach: None - No telemetry collection (privacy-focused)
- Note: TODO comment in `KotlinBLEBridge.kt` indicates future metrics infrastructure possibility (Firebase/Grafana/custom)

## CI/CD & Deployment

**Hosting:**
- Platform: GitHub (source)
- Distribution: GitHub Releases (APK artifacts)
- Installation: Direct APK installation on Android devices

**CI Pipeline:**
- Service: GitHub Actions (`.github/workflows/`)
- Jobs defined in `ci.yml`:
  1. **validate-wrapper** - Gradle wrapper validation
  2. **lint** - ktlint + detekt + CPD quality checks (runs `./gradlew ktlintCheck detektCheck cpdCheck`)
  3. **threading-audit** - Custom dispatcher architecture audit (`audit-dispatchers.sh`)
  4. **build** - Compile release and debug APKs
  5. **coverage** - JaCoCo test coverage report
  6. Upload artifacts (lint reports, APKs)

**Workflows:**
- `ci.yml` - Main CI on push/PR to main or `v*.*.x` branches
- `build-prerelease-apk.yml` - Manual prerelease builds
- `release.yml` - Release workflow (triggered by tags)

**Signing:**
- Method: Environment variables (CI/CD only)
- Keystore: Base64-encoded in `KEYSTORE_FILE` secret
- Config: `app/build.gradle.kts` decodes and configures signing
- Release builds: Minified with ProGuard rules at `app/proguard-rules.pro`

## Environment Configuration

**Required env vars for builds:**
- `ANDROID_HOME` - SDK path (e.g., `$HOME/Android/Sdk`)
- `JAVA_HOME` - Java 17 JDK (e.g., `/usr/lib/jvm/java-17-openjdk`)
- `PYTHON_VERSION` - Python 3.11 (Chaquopy compatibility)

**Required env vars for release:**
- `KEYSTORE_FILE` - Base64-encoded .jks file
- `KEYSTORE_PASSWORD` - Keystore password
- `KEY_ALIAS` - Release signing key alias
- `KEY_PASSWORD` - Key password

**Gradle configuration:**
- `gradle.properties` sets heap size, parallel builds, worker limits

**Secrets location:**
- GitHub Actions Secrets (for CI/CD)
- Local development: `~/.gradle/gradle.properties` or environment exports
- Template: `.envrc.example` for direnv-based setup

## Webhooks & Callbacks

**Incoming Webhooks:**
- Approach: None - App is client-only, no webhook endpoints

**Outgoing Callbacks:**
- Reticulum: Callbacks for packet/announce/link events handled by `ServiceReticulumProtocol`
- LXMF: Delivery callbacks and message receive notifications
- Lifecycle: Android lifecycle callbacks integrated via coroutines and Flow

**USB Device Callbacks:**
- Handler: `MainActivity` receives `UsbManager.ACTION_USB_DEVICE_ATTACHED` and `ACTION_USB_DEVICE_DETACHED` intents
- Filter: `usb_device_filter` XML defines RNode and compatible serial device VIDs/PIDs
- Permission: Requested via `UsbManager.requestPermission()` at runtime

**BLE Callbacks:**
- Handler: `KotlinBLEBridge` manages BLE advertisement scanning and connection callbacks
- Metrics: Sentry integration for observability

## Network Configuration

**Network Permissions:**
- INTERNET - General network access
- ACCESS_NETWORK_STATE - Network status monitoring
- ACCESS_WIFI_STATE - WiFi status
- CHANGE_WIFI_STATE - WiFi enable/disable (if needed)
- CHANGE_WIFI_MULTICAST_STATE - Multicast for mesh discovery

**Foreground Services:**
- `ReticulumService` runs as foreground service with notification
- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MICROPHONE`

**Special Requirements:**
- Battery Optimization Exemption: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for background mesh networking
- Companion Device Manager: RNode device association (Android 12+)

---

*Integration audit: 2026-01-23*
