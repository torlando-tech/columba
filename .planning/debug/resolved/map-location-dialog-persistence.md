---
status: resolved
trigger: "map-location-dialog-persistence - PR 356 was supposed to fix issue 342 but the bug persists in v0.7.3-beta"
created: 2026-01-28T00:00:00Z
updated: 2026-01-28T00:30:00Z
---

## Current Focus

hypothesis: RACE CONDITION - MainActivity.onCreate calls resetLocationPermissionSheetDismissal() in lifecycleScope.launch (async), but MapViewModel might be created and start collecting from hasDismissedLocationPermissionSheetFlow BEFORE the reset completes. This means ViewModel could read the old "true" value from a previous session on first composition.
test: Verify the timing of the reset vs ViewModel creation and flow collection
expecting: Confirm that the reset happens asynchronously and the ViewModel can read stale data before it completes
next_action: Fix by either (1) making reset synchronous, (2) eagerly awaiting it before continuing, or (3) using a different persistence mechanism

## Symptoms

expected: The location permission dialog should only show once per app session. After dismissing it, navigating away and back to the map page should NOT re-show the dialog.
actual: The dialog appears every time the user navigates back to the map page after dismissing it.
errors: None observed - this is a behavioral issue, not a crash.
reproduction: 1) Go to map page (without location permission granted), 2) Dismiss the location permission dialog, 3) Navigate to another tab, 4) Navigate back to map page - dialog appears again.
started: User is unsure if PR 356 changed anything. Testing v0.7.3-beta.

## Eliminated

## Evidence

- timestamp: 2026-01-28T00:05:00Z
  checked: PR 356 commit (84ccfbce) and both UI components
  found: PR 356 fixed the permission CARD (EmptyMapStateCard - a card overlay) using SavedStateHandle, NOT the permission SHEET (LocationPermissionBottomSheet - a modal bottom sheet dialog). The SHEET has "Location Permission" title with "Not Now" and "Enable Location" buttons. The CARD has "Location permission required" text with a close X button.
  implication: Issue 342 mentioned in PR 356 was about the CARD, but the current bug report is about the SHEET - these are two completely different UI elements with different persistence mechanisms

- timestamp: 2026-01-28T00:06:00Z
  checked: MapScreen.kt lines 175-178 and 922-933
  found: showPermissionSheet = !state.hasLocationPermission && !state.hasUserDismissedPermissionSheet. The sheet is controlled by hasUserDismissedPermissionSheet from ViewModel state
  implication: The sheet visibility depends on hasUserDismissedPermissionSheet being persisted

- timestamp: 2026-01-28T00:07:00Z
  checked: MapViewModel.kt lines 171-174 and 368-372
  found: hasUserDismissedPermissionSheet comes from settingsRepository.hasDismissedLocationPermissionSheetFlow (DataStore), and dismissLocationPermissionSheet() calls settingsRepository.markLocationPermissionSheetDismissed()
  implication: The dismissal is persisted to DataStore (permanent storage, survives app restart AND tab switches)

- timestamp: 2026-01-28T00:08:00Z
  checked: SettingsRepository.kt
  found: markLocationPermissionSheetDismissed() writes to DataStore with value true. There's also a resetLocationPermissionSheetDismissal() that sets it back to false
  implication: The reset function exists but needs to be called at the right time

- timestamp: 2026-01-28T00:09:00Z
  checked: MainActivity.kt lines 213-224
  found: resetLocationPermissionSheetDismissal() is called in lifecycleScope.launch{} inside onCreate() ONLY when savedInstanceState == null (fresh app launch)
  implication: The reset is ASYNCHRONOUS (launched in a coroutine), not blocking onCreate

- timestamp: 2026-01-28T00:10:00Z
  checked: MainActivity.kt lines 812-827 (bottom navigation setup)
  found: Navigation uses saveState=true and restoreState=true, so ViewModels survive tab switches
  implication: ViewModel is not recreated on tab switches, it's restored from saved state

- timestamp: 2026-01-28T00:11:00Z
  checked: Timing analysis
  found: On fresh app launch: (1) MainActivity.onCreate starts, (2) lifecycleScope.launch { reset } fires ASYNC, (3) Compose UI renders, (4) NavHost creates MapScreen, (5) MapViewModel init starts collecting from DataStore flow. The reset and the collection are RACING.
  implication: If ViewModel starts collecting before the async reset completes, it will read the old "true" value from previous session, causing sheet to not show when it should

## Resolution

root_cause: MapViewModel initializes with hasUserDismissedPermissionSheet = false (default value from MapState data class) and then collects from DataStore to update it. When the user navigates away from the Map tab, the ViewModel is destroyed (even though Navigation saves/restores the BackStackEntry, Hilt recreates the ViewModel). When the user navigates back, a NEW ViewModel is created with the default false value again. The init block starts collecting from DataStore, but there's a timing gap: the initial Compose render happens BEFORE the DataStore flow emits, causing showPermissionSheet = true and the sheet to appear. This is exactly the same bug that PR 356 fixed for isPermissionCardDismissed using SavedStateHandle, but it wasn't fixed for hasUserDismissedPermissionSheet.

fix: Applied the same pattern as PR 356, with proper handling of DataStore/SavedStateHandle interaction:
1. Added KEY_PERMISSION_SHEET_DISMISSED constant for SavedStateHandle key
2. Initialize MapState with hasUserDismissedPermissionSheet from SavedStateHandle (defaults to false if not present)
3. Update dismissLocationPermissionSheet() to:
   - Save to SavedStateHandle synchronously (immediate persistence across tab switches)
   - Update _state synchronously (immediate UI response)
   - Save to DataStore asynchronously (for app-level reset coordination)
4. Modified DataStore flow collector to use .drop(1) to skip initial emission and only react to actual changes (like MainActivity reset on fresh launch)
5. Added import for kotlinx.coroutines.flow.drop
6. Updated test to reflect new behavior: SavedStateHandle is source of truth, DataStore only used for reset coordination
7. Added comprehensive tests for SavedStateHandle persistence across ViewModel recreation

This ensures dismissal survives tab switches (SavedStateHandle) but resets on app relaunch (DataStore reset in MainActivity). The drop(1) prevents DataStore from overwriting SavedStateHandle on ViewModel recreation.

verification: All MapViewModel tests pass, including new tests for permission sheet SavedStateHandle persistence
files_changed:
  - app/src/main/java/com/lxmf/messenger/viewmodel/MapViewModel.kt
  - app/src/test/java/com/lxmf/messenger/viewmodel/MapViewModelTest.kt
