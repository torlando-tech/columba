# App Store Readiness Audit — F-Droid & Google Play

Audit date: 2026-07-13. Catalog of everything in the codebase (plus release
pipeline and store-console work it implies) that could block acceptance into
F-Droid or Google Play, with file references and suggested fixes.

**Top-level summary:**

| # | Item | Store | Severity |
|---|------|-------|----------|
| 1 | Chaquopy Python backend (prebuilt CPython + pip during build) | F-Droid | Blocker — ship `kotlinBackend` flavor instead |
| 2 | `play-services-location` GMS dependency in ALL variants | F-Droid | Blocker |
| 3 | JitPack dependencies (reticulum-kt, LXMF-kt, LXST-kt, usb-serial-for-android) | F-Droid | Blocker |
| 4 | Release pipeline builds APKs only — Play requires AAB | Play | Blocker |
| 5 | `READ_MEDIA_IMAGES` broad photo access (attachment grid) | Play | Blocker (Photo & Video Permissions policy) |
| 6 | Self-update checker (`UpdateChecker.kt`) | Play (& F-Droid) | Blocker — must be gated off per store |
| 7 | `ACCESS_BACKGROUND_LOCATION` | Play | Blocker without declaration + prominent-disclosure UX |
| 8 | 16 KB page-size support for native libs | Play | Blocker — must verify (required since Nov 2025 for targetSdk 35+) |
| 9 | No privacy policy | Play | Blocker (console requirement) |
| 10 | targetSdk 35 → 36 | Play | Deadline ~Aug 31, 2026 (weeks away) |

---

## 1. Google Play — hard blockers

### 1.1 Release pipeline produces APKs, not an App Bundle

New apps on Play must be uploaded as `.aab` (App Bundle) and enrolled in Play
App Signing — APK uploads are not accepted.

- `.github/workflows/release.yml` runs `assembleRelease` and publishes per-ABI
  APK splits only.
- `app/build.gradle.kts:305-312` — the `splits { abi { ... } }` block and the
  per-ABI versionCode offsets (`app/build.gradle.kts:387-402`) are
  APK-distribution machinery; AAB handles per-ABI delivery itself.

**Change:** add a `bundleRelease` path (keep APK splits for GitHub/F-Droid).
Decide the Play App Signing strategy — Play holds the app signing key, your
current keystore becomes the upload key. Note this means the Play-installed
APK will have a different signature than GitHub-release APKs, so users cannot
cross-update between the two sources (relevant to the APK-sharing feature,
§2.4).

### 1.2 16 KB page-size support (required since Nov 1, 2025 for targetSdk 35+)

`targetSdk = 35` (`app/build.gradle.kts:115`) means every new upload must
support 16 KB memory pages. The app bundles several native-lib stacks:

- Chaquopy CPython runtime + native wheels (`cryptography`) — `pythonBackend`
  flavor, Chaquopy 17.0.0 (`build.gradle.kts:13`).
- MapLibre native (`org.maplibre.gl:android-sdk:12.3.1`, `app/build.gradle.kts:539`).
- LXST-kt "Kotlin/C++" codecs (`libs.lxst.kt`).
- Sentry NDK (sentry flavor, `io.sentry:sentry-android:8.31.0`).

AGP 9.1.0 handles APK/AAB alignment, but each bundled `.so` must itself be
built 16 KB-compatible (NDK r27+ or explicit
`-Wl,-z,max-page-size=16384`).

**Change/verify:** run `zipalign -c -P 16 -v 4 <universal.apk>` and check each
`.so` with `llvm-readelf -l` (LOAD segment align ≥ 0x4000) for all four
stacks. LXST-kt/reticulum-kt are your own libraries — fix their NDK builds if
needed. If Chaquopy 17's CPython is not 16 KB-clean, the `pythonBackend`
flavor cannot ship on Play until Chaquopy fixes it (the `kotlinBackend`
flavor would still be shippable).

### 1.3 `READ_MEDIA_IMAGES` — Photos & Videos permission policy

`app/src/main/AndroidManifest.xml:70-72` declares `READ_MEDIA_IMAGES` +
legacy `READ_EXTERNAL_STORAGE`, used by the attachment-panel photo grid
(`app/src/main/java/network/columba/app/util/MediaPermissionManager.kt`).

Google's Photo & Video Permissions policy (enforced since 2025) only permits
broad photo-library access for apps whose *core purpose* is photo/gallery
management. Messaging apps attaching images are Google's canonical example of
what must use the **Android Photo Picker** instead. Expect a rejection or a
denied declaration form as-is.

**Change:** migrate the attachment grid to
`ActivityResultContracts.PickVisualMedia` (no permission needed, works to API
24 via the backported picker) and drop both permissions — or, if the in-app
grid must stay, add `READ_MEDIA_VISUAL_USER_SELECTED` partial-access UX and
file the console declaration form (high rejection risk for a messenger).

### 1.4 Self-update checker

`app/src/main/java/network/columba/app/service/UpdateChecker.kt` polls the
GitHub Releases API and surfaces "update available" linking to the GitHub
release page (Settings/About UI). On Play this violates the Device & Network
Abuse policy (apps must update only via Play; steering users to sideloaded
APKs is treated as circumvention). F-Droid likewise rejects self-updaters /
update nags pointing outside F-Droid.

**Change:** gate the entire update-check feature (checker, settings toggle,
About-card UI) behind a distribution flavor/BuildConfig flag so Play and
F-Droid builds ship without it (see §5).

### 1.5 `ACCESS_BACKGROUND_LOCATION`

Declared at `app/src/main/AndroidManifest.xml:53`, requested from
`SettingsScreen.kt:693` for background telemetry/location sharing
(`TelemetryLocationTracker`, `LocationSharingManager`).

Play requires: (a) the location-permissions declaration form + review video,
(b) an in-app **prominent disclosure** dialog shown *before* the runtime
prompt ("Columba collects location data in the background to share your
position with contacts you choose, even when the app is closed…"), and (c)
the feature to be demonstrably core. Apps get rejected for the missing
disclosure dialog far more often than for the feature itself.

**Change/verify:** confirm `LocationPermissionManager` /
`SettingsScreen` show a compliant prominent-disclosure dialog before
launching the permission request; add one if not. Alternatively ship the Play
build without background location (foreground-only telemetry) to skip the
review entirely.

### 1.6 Privacy policy

No privacy policy exists in the repo or docs. Play requires a privacy-policy
URL for every app, and the sensitive permissions here (location, camera,
microphone, Bluetooth) make it non-negotiable. F-Droid users will expect one
too.

**Change:** publish a privacy policy (covering: opt-in Sentry crash reports,
peer-to-peer location/telemetry sharing, no central servers, OpenFreeMap tile
fetches) and link it from the app (Settings/About) and the Play listing.

### 1.7 Target API level deadline

`targetSdk = 35` is compliant today, but Play's annual requirement will move
to API 36 around **Aug 31, 2026** for new apps and updates. Since store
submission is happening now, plan the 36 bump immediately to avoid submitting
weeks before being forced to re-verify everything (FGS behavior, permissions)
under Android 16 semantics.

---

## 2. Google Play — review-risk items (not hard blockers, will draw scrutiny)

### 2.1 `SYSTEM_ALERT_WINDOW` (`AndroidManifest.xml:86`)

Used to show the incoming-call screen when the app is backgrounded. This
permission is heavily scrutinized. Since `USE_FULL_SCREEN_INTENT` is already
declared and `IncomingCallActivity` supports `showOnLockScreen`/
`turnScreenOn`, a full-screen-intent notification should cover the use case
on modern Android. **Recommend:** drop `SYSTEM_ALERT_WINDOW` if the
full-screen-intent path works ≥ API 29; otherwise be prepared to justify it.

### 2.2 `USE_FULL_SCREEN_INTENT` (`AndroidManifest.xml:82`)

Apps targeting 34+ must declare the FSI use case in Play Console. Calling
apps are the sanctioned category — Columba qualifies (LXST voice calls), just
remember the console declaration.

### 2.3 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`AndroidManifest.xml:22`, `util/BatteryOptimizationManager.kt`)

Play's "acceptable use" policy permits this only when core functionality
breaks otherwise. An always-on mesh node is a plausible justification, but
this is a documented rejection trigger. Ensure the exemption prompt is
user-initiated (settings toggle, not forced on first run) and document the
justification for the review team.

### 2.4 APK-sharing feature (`service/ApkSharingServer.kt`, `viewmodel/ApkSharingViewModel.kt`)

Serves the installed APK over local WiFi/hotspot for offline distribution.
Precedent exists (Briar ships this on Play), and it distributes the app to
*other* devices rather than self-updating, so it's defensible — but note that
after Play App Signing, the Play-signed APK you'd share differs from
GitHub-release signatures (signature-mismatch updates for recipients who
later switch source). Consider gating it per distribution channel or keep and
document.

### 2.5 Foreground-service declarations

- `rns-host/src/main/AndroidManifest.xml:19` uses
  `connectedDevice|microphone`. Both types require use-case declarations in
  Play Console (video may be requested). Microphone-type FGS started from
  background is restricted on 14+ — verify the call-answer path starts it
  from a user interaction (notification action / full-screen intent).
- `app/src/main/AndroidManifest.xml:63` declares
  `FOREGROUND_SERVICE_DATA_SYNC` but **no service uses the `dataSync` type**
  (grep confirms). Remove the unused permission — declaring it triggers the
  dataSync declaration flow in the console for nothing, and dataSync is the
  type Google is actively deprecating.

### 2.6 Sentry crash reporting (sentry flavor)

Opt-in consent gating is already implemented
(`ColumbaApplication.kt:131-136`, `CrashReporter.kt`, defaults to off) —
that's compliant. Remaining work is the **Data Safety form**: declare crash
data (device info, stack traces) as optional/user-consented, plus location
data shared with other users (telemetry), and messages (E2E-encrypted,
not collected). Alternatively ship the `noSentry` flavor to Play and skip the
crash-data declaration entirely.

### 2.7 Console-side items (no code change)

- Data Safety form (see 2.6), IARC content rating questionnaire.
- US export-compliance question: app uses non-standard cryptography
  (Reticulum) — answer accordingly.
- Account deletion policy: N/A (no server accounts) — state that in the form.

---

## 3. F-Droid — hard blockers

F-Droid's inclusion policy: everything must build from source on their
servers; no prebuilt binaries, no proprietary dependencies, no tracking by
default.

### 3.1 Chaquopy `pythonBackend` flavor cannot be included

`rns-backend-py/build.gradle.kts:95-136`:

- Chaquopy bundles a **prebuilt CPython runtime** and prebuilt native wheels
  (`cryptography`) from Chaquopy's Maven artifacts — prebuilt blobs F-Droid
  won't accept (no Chaquopy app has made it into the main repo).
- `pip { install("git+https://...") }` fetches arbitrary sources during the
  build; `install("cryptography>=42.0.0")` is an **unpinned version range**
  (also a reproducibility bug for your own releases — pin it regardless).

**Change:** submit the **`kotlinBackend` flavor** to F-Droid. It already
exists precisely for this (`app/build.gradle.kts:159-173`). The Python flavor
stays GitHub/Play-only.

### 3.2 Google Play Services location — in every variant

`app/build.gradle.kts:542` puts
`com.google.android.gms:play-services-location:21.2.0` on the classpath of
**all** flavors, including `kotlinBackend`+`noSentry`. The F-Droid scanner
hard-fails on any `com.google.android.gms` artifact.

`util/LocationCompat.kt` already implements the full `LocationManager`
fallback used when GMS is absent at runtime — so the functionality survives
removal.

**Change:** move the GMS dependency behind a distribution flavor (e.g.
`playImplementation(...)`) with a source-set seam so the F-Droid/foss variant
compiles only the `LocationCompat` path, with no GMS classes referenced.
Files touching FusedLocation: `LocationCompat.kt`, `MapScreen.kt`,
`OfflineMapDownloadScreen.kt`, `DiscoveredInterfacesScreen.kt`,
`TelemetryCollectorManager.kt`, `TelemetryLocationTracker.kt`,
`LocationSharingManager.kt`.

### 3.3 JitPack dependencies

`settings.gradle.kts:22` adds `https://jitpack.io` for:

- `com.github.torlando-tech.reticulum-kt:*` (`gradle/libs.versions.toml:31-33`)
- `com.github.torlando-tech:LXMF-kt`, `com.github.torlando-tech:LXST-kt`
- `com.github.mik3y:usb-serial-for-android:3.7.0` (`rns-host/build.gradle.kts:135`)

F-Droid's scanner rejects JitPack artifacts (binaries built outside their
infrastructure). Options, best-first:

1. **srclibs / git submodules**: have the F-Droid build compile
   reticulum-kt, LXMF-kt, LXST-kt, and usb-serial-for-android from source
   (they're all your repos except mik3y's, which is FOSS). The
   `LOCAL_RETICULUM_KT`-style `includeBuild` seams in `settings.gradle.kts`
   already prove this works — an F-Droid build recipe can set those env vars.
2. Publish your -kt libraries to Maven Central (helps Play reproducibility
   too, but F-Droid may still ask for source builds).

### 3.4 Sentry, update checker, GMS = build-variant policy for F-Droid

The F-Droid build must be: `noSentry` (exists ✓) + `kotlinBackend` (exists ✓)
+ no GMS (§3.2, missing) + no update checker (§1.4, missing). Today only two
of the four gates exist.

---

## 4. F-Droid — setup & packaging work

### 4.1 Application ID decision

`kotlinBackend` applies `applicationIdSuffix = ".kt"` →
`network.columba.app.kt` (`app/build.gradle.kts:166`). An F-Droid submission
of the kotlin flavor would therefore ship a *different app id* than the
`network.columba.app` your GitHub/Obtainium users have. App ids are permanent
per store listing. Decide now:

- Option A: once the Kotlin backend is no longer "EXPERIMENTAL", make it the
  base `network.columba.app` and give the Python flavor the suffix.
- Option B: accept `network.columba.app.kt` on F-Droid forever.

The same question applies to Play (you can only list one id per listing; the
python flavor holds the base id today).

### 4.2 Fastlane metadata

No `fastlane/metadata/android/` tree exists. F-Droid reads app name,
summary, description, changelogs, screenshots, and icon from it (Play can
consume the same structure via fastlane supply). **Change:** add
`fastlane/metadata/android/en-US/{title.txt,short_description.txt,full_description.txt,changelogs/,images/}`.

### 4.3 Reproducible builds — mostly in good shape

Already done (keep it): `SOURCE_DATE_EPOCH` support + commit-timestamp
`BUILD_TIMESTAMP` (`app/build.gradle.kts:88-104`), reproducible archive tasks
(`build.gradle.kts:24-29`), tag-derived versionCode/versionName
(deterministic when built from a release tag).

Remaining for F-Droid "reproducible" status (lets F-Droid publish with your
signature so users can cross-update with GitHub releases):

- Pin `cryptography` exactly (python flavor; moot for the kotlin flavor).
- Verify a clean-room rebuild of a tagged release is byte-identical apart
  from the signature; then add `AllowedAPKSigningKeys` to the fdroiddata
  metadata.

### 4.4 Anti-features to expect (informational, not blockers)

- Default map tiles from `tiles.openfreemap.org`
  (`map/MapTileSourceManager.kt:83-84`) — FOSS service, no key; at most a
  note, likely no anti-feature.
- If any variant with Sentry were submitted it would be tagged `Tracking` —
  avoided by the `noSentry` gate.

---

## 5. Recommended mechanism: a `distribution` flavor dimension

Most fixes above are "gate X per store". One clean seam:

```kotlin
flavorDimensions += "distribution"
productFlavors {
    create("github") { dimension = "distribution" }            // status quo: updater, GMS, APK sharing
    create("play")   { dimension = "distribution" }            // no updater; AAB; GMS ok
    create("foss")   { dimension = "distribution" }            // no updater, no GMS; F-Droid builds this
}
```

with `BuildConfig.UPDATE_CHECK_AVAILABLE`, `playImplementation(...GMS...)`,
and per-flavor source sets for the FusedLocation vs LocationCompat seam.
(Three dimensions × existing two is 12 variants — consider collapsing
`telemetry` into `distribution` since the mapping is fixed: foss→noSentry,
play→choice, github→both.)

## 6. Suggested execution order

1. Remove unused `FOREGROUND_SERVICE_DATA_SYNC` permission (one line).
2. Photo-picker migration, drop `READ_MEDIA_IMAGES` (Play blocker, helps F-Droid optics).
3. `distribution` flavor dimension: gate UpdateChecker + GMS location.
4. AAB build + Play App Signing decision; targetSdk 36 bump.
5. 16 KB verification pass across all native libs (fix -kt libs if needed).
6. Background-location prominent disclosure (or drop background location on Play).
7. Privacy policy authored + linked in-app.
8. Fastlane metadata tree; app-id decision for the kotlin flavor.
9. Source-build story for JitPack deps; pin `cryptography`; fdroiddata
   metadata + submission (kotlinBackend/foss/noSentry variant).
10. Play Console forms: Data Safety, location declaration, FSI declaration,
    FGS declarations, content rating, export compliance.

## 7. License review (no blockers found)

| Component | License | Notes |
|-----------|---------|-------|
| Columba | MPL-2.0 (`LICENSE.md`) | F-Droid & Play compatible |
| MapLibre Android | BSD-2 | Maven Central |
| Coil, ZXing, msgpack, AndroidX, Hilt | Apache-2.0 | — |
| icons-lucide-android | ISC | Maven Central |
| Sentry SDK | MIT | stripped in noSentry |
| usb-serial-for-android | MIT (v3.x; was LGPL pre-3.0 — verify) | JitPack source (§3.3) |
| Reticulum / LXMF forks, reticulum-kt / LXMF-kt / LXST-kt | MIT (verify each fork carries upstream license) | — |
| Chaquopy runtime | MIT (runtime), but ships prebuilt CPython (PSF) | F-Droid: blocked by prebuilt policy regardless of license |
