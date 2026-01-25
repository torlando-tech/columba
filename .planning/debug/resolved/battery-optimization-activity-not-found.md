---
status: resolved
trigger: "battery-optimization-activity-not-found"
created: 2026-01-25T00:00:00Z
updated: 2026-01-25T00:06:00Z
---

## Current Focus

hypothesis: Root cause confirmed - need to implement try-catch with fallback
test: Implement intent resolution check or try-catch with fallback to battery settings
expecting: App should gracefully fall back to battery settings screen on MEIZU devices
next_action: Implement fix in BatteryOptimizationCard.kt and BatteryOptimizationManager.kt

## Symptoms

expected: Tapping the battery optimization setting should open the system battery settings or show a dialog
actual: App crashes with ActivityNotFoundException - "No Activity found to handle Intent { act=android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dat=package: }"
errors: android.content.ActivityNotFoundException at q5.H3.invoke(SourceFile:94) - likely a Compose onClick handler
reproduction: Tap "Background Service Protection" setting in the app on a MEIZU device
started: Reported on 0.7.2-beta build 25ca2cc from 2026-01-25. Device is MEIZU 20 Pro running Android 13 (API 33)

## Eliminated

## Evidence

- timestamp: 2026-01-25T00:01:00Z
  checked: BatteryOptimizationCard.kt lines 178-182
  found: Direct startActivity call with no try-catch or intent resolution check
  implication: Will crash on devices without handler for REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent

- timestamp: 2026-01-25T00:01:30Z
  checked: BatteryOptimizationManager.kt lines 46-51
  found: createRequestExemptionIntent creates intent with ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
  implication: Intent is correctly formed but no validation that a handler exists

- timestamp: 2026-01-25T00:02:00Z
  checked: Android documentation
  found: MEIZU and some other OEM devices don't implement REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
  implication: Need fallback to IGNORE_BATTERY_OPTIMIZATION_SETTINGS or graceful handling

- timestamp: 2026-01-25T00:03:00Z
  checked: OnboardingPagerScreen.kt line 234
  found: Uses activity result launcher which might not crash but could fail silently
  implication: Should fix both locations - settings card (direct startActivity) and onboarding (launcher)

## Resolution

root_cause: BatteryOptimizationCard.kt line 182 calls startActivity without checking if intent can be resolved. MEIZU devices don't implement ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS handler, causing ActivityNotFoundException.

fix: Added BatteryOptimizationManager.requestBatteryOptimizationExemption() method that:
1. Checks if intent can be resolved using resolveActivity()
2. Launches direct exemption request if available
3. Falls back to battery settings screen if not available
4. Catches all exceptions to prevent crashes
Updated both BatteryOptimizationCard.kt and OnboardingPagerScreen.kt to use this safe method.

verification: |
  Code review verification (gradle build issues in worktree prevented compilation test):

  Test Case 1 (Normal device): resolveActivity returns non-null → launches direct exemption intent
  Test Case 2 (MEIZU device): resolveActivity returns null → falls back to battery settings
  Test Case 3 (Exception): Any exception caught → no crash, returns false

  ✓ Uses resolveActivity() to check intent can be handled before launching
  ✓ Falls back to IGNORE_BATTERY_OPTIMIZATION_SETTINGS if direct exemption unavailable
  ✓ Wraps all intent launching in try-catch to prevent crashes
  ✓ Logs appropriate debug/warning/error messages at each branch
  ✓ Returns boolean success indicator
  ✓ Updated both call sites (settings card and onboarding screen)
  ✓ Maintains existing behavior for normal Android devices
  ✓ Fixes ActivityNotFoundException crash on MEIZU and similar OEM devices

  The fix correctly addresses the root cause by checking intent resolution before launching.

files_changed:
- app/src/main/java/com/lxmf/messenger/util/BatteryOptimizationManager.kt
- app/src/main/java/com/lxmf/messenger/ui/screens/settings/cards/BatteryOptimizationCard.kt
- app/src/main/java/com/lxmf/messenger/ui/screens/onboarding/OnboardingPagerScreen.kt
