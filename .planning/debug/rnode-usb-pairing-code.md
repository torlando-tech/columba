---
status: diagnosed
trigger: "Investigate why BLE scan stopped finding RNode when it worked earlier today"
created: 2026-01-23T14:30:00Z
updated: 2026-01-23T14:52:00Z
---

## Current Focus

hypothesis: RNode advertises differently when already bonded vs in pairing mode. Successful 13:33:25 scan found bondState=12 (bonded), current failed scans are looking for unbonded RNode in pairing mode
test: Check if RNode advertises when already bonded vs when in pairing mode
expecting: Evidence that RNode in pairing mode either doesn't advertise via BLE or advertises differently
next_action: Research RNode firmware BLE advertising behavior during pairing

## Symptoms

expected: BLE scan should find RNode E16A like it did earlier at 13:33:25
actual: BLE scan runs for 30 seconds but finds nothing - no "BLE scan found" log
errors: No errors - just timeout
reproduction: Connect Heltec v3 via USB, wait for timeout, enter PIN manually, scan times out
started: Used to work in original PR (Classic BT), worked at 13:33:25 today, broken now

## Eliminated

## Evidence

- timestamp: 2026-01-23T14:35:00Z
  checked: Original working commit e79f62a2
  found: Used startClassicBluetoothDiscovery(pin), NOT BLE scan
  implication: Original implementation used Classic BT discovery, not BLE scanning

- timestamp: 2026-01-23T14:36:00Z
  checked: Current timing flow in submitManualPin()
  found: User enters PIN at T+10s (after timeout), then BLE scan starts
  implication: By the time scan starts, RNode may have exited pairing mode

- timestamp: 2026-01-23T14:37:00Z
  checked: RNode firmware pairing timeout
  found: RNode exits pairing mode after ~10 seconds (firmware timeout)
  implication: Manual PIN entry (3s timeout + user delay) means scan starts AFTER RNode exits pairing mode

- timestamp: 2026-01-23T14:38:00Z
  checked: Successful scan at 13:33:25
  found: Found "RNode E16A (F4:44:99:ED:02:CC), bondState=12, isAlreadyBonded=true"
  implication: That scan found an ALREADY BONDED device (bondState=12), not a device in pairing mode

- timestamp: 2026-01-23T14:40:00Z
  checked: Previous debug session .planning/debug/rnode-usb-pairing-manual-pin.md
  found: SAME ISSUE diagnosed previously. Root cause: RNode pairing mode timeout = 10s. Fix was to re-send CMD_BT_CTRL before BLE scan to re-enter pairing mode.
  implication: Previous session identified the fix but it may not have been applied

- timestamp: 2026-01-23T14:42:00Z
  checked: Current submitManualPin() implementation (lines 2161-2191)
  found: Code comment says "Do NOT re-send pairing mode command - that would generate a NEW PIN!" and directly disconnects USB then starts BLE scan
  implication: THE FIX WAS NEVER APPLIED! Previous debug session identified fix but code still has original broken behavior

- timestamp: 2026-01-23T14:43:00Z
  checked: RNode firmware behavior when re-entering pairing mode
  found: Previous debug session stated fix is to re-send CMD_BT_CTRL. Question: Does this generate NEW PIN or keep same PIN?
  implication: Code comment assumes re-send = new PIN. Need to verify if this is true or if RNode keeps same PIN.

- timestamp: 2026-01-23T14:45:00Z
  checked: Original working commit e79f62a2 used Classic BT discovery
  found: Original used startClassicBluetoothDiscovery, NOT BLE scan. Classic BT discovery is different from BLE scanning.
  implication: RNode might advertise via Classic Bluetooth during pairing, not via BLE. BLE scan might be wrong approach.

- timestamp: 2026-01-23T14:46:00Z
  checked: Current BLE scan at line 1972-1974 - no UUID filter
  found: Code comment: "Don't filter by service UUID - RNode in pairing mode may not advertise NUS"
  implication: Code author knew RNode in pairing mode advertises differently! This supports hypothesis that pairing mode uses Classic BT.

- timestamp: 2026-01-23T14:47:00Z
  checked: Success at 13:33:25 found bondState=12 (already bonded)
  found: That RNode was NOT in pairing mode - it was already bonded and running normally
  implication: We've never successfully found an RNode IN PAIRING MODE via BLE scan. Only found already-bonded RNodes.

- timestamp: 2026-01-23T14:50:00Z
  checked: USB-assisted pairing flow (line 2220+)
  found: Uses Classic Bluetooth discovery via bluetoothAdapter.startDiscovery() at line 2549
  implication: There are TWO pairing flows: (1) USB-assisted uses Classic BT, (2) Auto-pairing with PIN uses BLE scan

- timestamp: 2026-01-23T14:51:00Z
  checked: Original working commit e79f62a2
  found: Used Classic BT discovery, worked reliably
  implication: ROOT CAUSE CONFIRMED - RNode in pairing mode advertises via CLASSIC BLUETOOTH, not BLE

## Resolution

root_cause: RNode in Bluetooth pairing mode advertises via Classic Bluetooth, NOT via BLE. When submitManualPin() calls initiateAutoPairingWithPin(), it uses startBleScanForPairing() which performs a BLE scan. BLE scan never finds the RNode because it's advertising via Classic BT, not BLE. The original working implementation (commit e79f62a2) used Classic Bluetooth discovery (bluetoothAdapter.startDiscovery()), which is why it worked. The code was later changed to use BLE scanning, breaking the manual PIN flow.

Evidence:
1. Original working commit used startClassicBluetoothDiscovery()
2. Current USB-assisted pairing STILL uses Classic BT discovery and works
3. Code comment at line 1970: "Don't filter by service UUID - RNode in pairing mode may not advertise NUS" - acknowledges pairing mode advertising is different
4. Successful scan at 13:33:25 found bondState=12 (already bonded, normal operation), not in pairing mode
5. No "BLE scan found" logs when RNode is in pairing mode = BLE scan doesn't see it

fix: Change initiateAutoPairingWithPin() to use Classic Bluetooth discovery instead of BLE scan. This matches the original working implementation and the current USB-assisted pairing flow.

verification:
files_changed:
  - app/src/main/java/com/lxmf/messenger/viewmodel/RNodeWizardViewModel.kt
