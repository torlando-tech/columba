---
status: resolved
trigger: "BadTokenException crash when showing dialog after activity destroyed"
created: 2026-01-25T14:00:00Z
updated: 2026-01-25T14:40:00Z
---

## Current Focus

hypothesis: ModalBottomSheet in MainActivity (BlePermissionBottomSheet) is being shown from LaunchedEffect after activity starts destroying, causing BadTokenException because window token is invalid
test: Verify LaunchedEffect at line 663-668 in MainActivity can trigger sheet display during activity destruction
expecting: Will confirm the LaunchedEffect doesn't check activity lifecycle before setting showPermissionBottomSheet = true
next_action: Implement fix to check LocalLifecycleOwner.current.lifecycle.currentState before showing popups/sheets

## Symptoms

expected: App should work without crashing when showing dialogs
actual: BadTokenException crash - "Unable to add window -- token ... is not valid; is your activity running?"
errors: android.view.WindowManager$BadTokenException at Dialog.show() called from Choreographer.doFrame via obfuscated code (e2.a.invoke, P0.G.a, X0.w.c, P0.y.e, P0.y.d, P0.z0.invoke, E1.i0.doFrame)
reproduction: Crash occurs during dialog display triggered from frame callback - exact user action unclear. Stack trace shows dialog being shown after activity window token invalidated. User was on settings screen.
started: Reported on 0.7.2-beta (build 25ca2cc, 2026-01-24), crash time 2026-01-25 12:44:47

## Reproduction Steps (to verify fix)

The crash is a race condition that's difficult to reproduce reliably, but here are the likely scenarios:

### Scenario 1: BLE Permission Bottom Sheet (MainActivity)
1. Complete onboarding with a Bluetooth-requiring interface enabled (RNode or AndroidBLE)
2. Immediately press home or back while the app is determining if it needs to show the BLE permission sheet
3. Without the fix, if the LaunchedEffect completes while activity is stopping, crash occurs

### Scenario 2: Crash Report Dialog (SettingsScreen)
1. Force a crash in the app (e.g., trigger an unhandled exception)
2. Relaunch the app and immediately navigate to Settings
3. Quickly press back/home while the crash report manager is checking for pending reports
4. Without the fix, if showCrashDialog is set while activity is stopping, crash occurs

### Scenario 3: Location Permission Sheet (SettingsScreen)
1. Navigate to Settings > Location Sharing
2. Try to enable telemetry collector without location permission
3. Quickly press back/home while permission sheet is about to show
4. Without the fix, crash occurs

### How to stress test
- Use adb shell commands to rapidly start/stop the activity
- Use developer options "Don't keep activities" to force activity destruction
- Use "Background process limit = No background processes" to stress test lifecycle

## Eliminated

## Evidence

- timestamp: 2026-01-25T14:00:00Z
  checked: ProGuard/R8 mapping files
  found: No mapping files in repository - obfuscated names cannot be directly de-obfuscated
  implication: Must search for Dialog usage patterns and frame callback patterns to identify culprit

- timestamp: 2026-01-25T14:10:00Z
  checked: Dialog and Popup usage in codebase
  found: Multiple DropdownMenu (popup-based) and ModalBottomSheet components used across screens including SettingsScreen, ContactsScreen, MessagingScreen, etc. All use Compose components.
  implication: Compose DropdownMenu and ModalBottomSheet use Popup windows internally which require valid window tokens

- timestamp: 2026-01-25T14:12:00Z
  checked: Compose BOM version
  found: Using composeBom "2025.12.01", material3 from BOM
  implication: Recent Compose version - need to check if there are known issues with Popup/ModalBottomSheet and lifecycle

- timestamp: 2026-01-25T14:20:00Z
  checked: LaunchedEffect in MainActivity lines 663-668
  found: LaunchedEffect triggers showPermissionBottomSheet = true based on state changes (onboardingState, hasEnabledBluetoothInterface). Does NOT check if activity is finishing/destroyed before showing the sheet.
  implication: If user navigates away or activity is destroyed while LaunchedEffect is checking conditions, it may try to show ModalBottomSheet on an invalid window token

- timestamp: 2026-01-25T14:22:00Z
  checked: Web search for Compose ModalBottomSheet BadTokenException
  found: Known issue - ModalBottomSheet uses popup window which requires valid window token. Fix is to check isFinishing() or lifecycle state before showing
  implication: Need to add lifecycle check before setting showPermissionBottomSheet = true

## Resolution

root_cause: ModalBottomSheet and AlertDialog components in Compose use popup windows which require a valid window token from the activity. When activity is being destroyed (user navigates away, presses back/home, or activity is stopped), but LaunchedEffect has already set a "show dialog" flag to true, Compose attempts to create the popup window during the next recomposition frame (Choreographer.doFrame), resulting in BadTokenException because the window token is no longer valid.

Specific culprits:
1. MainActivity.kt line 666-671: LaunchedEffect triggers showPermissionBottomSheet = true without lifecycle check
2. SettingsScreen.kt line 123: LaunchedEffect triggers showCrashDialog = true without lifecycle check
3. Both ModalBottomSheet and AlertDialog rendering sites didn't check lifecycle state before rendering

fix: Added lifecycle state checks in two places for each affected component:
1. In LaunchedEffect before setting show flags (defensive first line)
2. In conditional rendering (defensive last line)

Used LocalLifecycleOwner.current.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) to verify activity is still active before showing any popup-based UI components.

verification: Build compiles successfully. Manual testing needed to verify fix works in production scenario.

files_changed:
- /home/tyler/repos/public/columba-347/app/src/main/java/com/lxmf/messenger/MainActivity.kt
- /home/tyler/repos/public/columba-347/app/src/main/java/com/lxmf/messenger/ui/screens/SettingsScreen.kt
