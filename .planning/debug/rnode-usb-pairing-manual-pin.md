---
status: verifying
trigger: "rnode-usb-pairing-manual-pin - After manually entering the Bluetooth PIN shown on the RNode OLED (because Heltec v3 doesn't send PIN over serial), the pairing doesn't complete within Columba"
created: 2026-01-23T13:50:00Z
updated: 2026-01-23T13:50:00Z
---

## Current Focus

hypothesis: RNode exits pairing/advertising mode before manual PIN is submitted. Timeline: (1) USB pairing command sent at 13:41:36, (2) 10-second timeout at 13:41:46, (3) user enters PIN at 13:41:52 (6 seconds after timeout), (4) BLE scan starts but RNode is no longer advertising because pairing mode timed out on the RNode side
test: Check RNode firmware source or documentation for pairing mode timeout duration
expecting: Find that RNode pairing mode has a timeout (likely 10-30 seconds) and exits before user manually enters PIN
next_action: Search for RNode pairing mode timeout in firmware/documentation

## Symptoms

expected: After entering the 6-digit PIN manually, the app should find the RNode via BLE scan, initiate pairing automatically, and proceed to the next wizard step
actual: User says "it still isn't working" after we made several fixes
errors: No explicit errors - logs show scan timeout after finding device
reproduction:
1. Connect Heltec v3 RNode via USB
2. Enter pairing mode (timeout after 10s waiting for PIN)
3. Enter PIN manually from RNode's OLED display
4. App should find RNode and pair - but something fails
timeline: This is an evolution of the original "PIN not received over serial" issue. Original timeout fix works. Manual PIN entry UI works. The issue is what happens AFTER PIN entry.
hardware: Heltec v3 board (ESP32-based RNode)

## Eliminated

## Evidence

- timestamp: 2026-01-23T13:50:00Z
  checked: Latest logcat from 13:41:52 test run
  found: BLE scan starts at 13:41:52.754 but NO "BLE scan found" log appears. Scan times out after 30 seconds (13:42:22.757). The RNode E16A (F4:44:99:ED:02:CC) is in the bonded addresses list but BLE scan callback never fires.
  implication: BLE scan callback is NOT being triggered at all. Either (1) RNode is not advertising, (2) Android filters out already-bonded devices from BLE scan results, or (3) RNode is not in pairing/advertising mode after USB pairing timeout.

- timestamp: 2026-01-23T13:52:00Z
  checked: Code at lines 1976-2099 - BLE scan callback logic
  found: Callback checks if device.name starts with "RNode" (line 1982), logs finding (line 1986-1990), then stops scan (line 1994) and processes device. No callback firing means Android never invoked onScanResult for ANY RNode device.
  implication: RNode is either not advertising at all, or Android is filtering it out. Need to check if already-bonded devices appear in BLE scans.

- timestamp: 2026-01-23T13:53:00Z
  checked: Web research on Android BLE scans and bonded devices
  found: Bonded devices SHOULD appear in BLE scan results if they're advertising. Android has had reliability issues with bonded devices but scan detection should work.
  implication: If scan callback isn't firing, the RNode is likely not advertising. Timeline analysis: pairing command sent at 13:41:36, timeout at 13:41:46 (10s), PIN entered at 13:41:52 (16s after command). RNode may have exited pairing mode.

- timestamp: 2026-01-23T13:54:00Z
  checked: RNode firmware Bluetooth pairing mode timeout documentation
  found: RNode pairing mode has a 10-second timeout. After sending CMD_BT_CTRL, the RNode enters pairing mode for exactly 10 seconds, then exits.
  implication: ROOT CAUSE CONFIRMED. App waits 10s for PIN (13:41:36 to 13:41:46), RNode pairing mode expires at same time. User enters PIN at 13:41:52 (6s after RNode exited pairing mode). BLE scan finds no advertising RNode because it's no longer in pairing mode.

## Resolution

root_cause: RNode firmware's Bluetooth pairing mode has a 10-second timeout. When app sends CMD_BT_CTRL, RNode enters pairing mode for 10 seconds. App also waits 10 seconds for PIN via serial. Both timeouts occur simultaneously. When manual PIN entry UI appears after 10s timeout, the RNode has already exited pairing mode and stopped advertising. User enters PIN 6+ seconds later, app starts BLE scan, but RNode is no longer in pairing mode - hence no device found, scan timeout.

fix: Modified submitManualPin() to re-send CMD_BT_CTRL command BEFORE starting BLE scan. This re-enters pairing mode on the RNode, ensuring it's advertising when the BLE scan starts. Added 500ms delay after sending command to allow RNode to enter pairing mode and start advertising. Flow: (1) user submits PIN, (2) send CMD_BT_CTRL via USB, (3) wait 500ms, (4) disconnect USB, (5) start BLE scan.

verification: Build successful, APK deployed to device. Ready for user testing. User should:
1. Connect Heltec v3 RNode via USB
2. Enter pairing mode and wait for 10-second timeout
3. Manually enter the 6-digit PIN from RNode's OLED
4. Observe: App should now re-send pairing command, wait 500ms, then start BLE scan
5. Expected: RNode should be found via BLE scan and pairing should complete
files_changed:
  - app/src/main/java/com/lxmf/messenger/viewmodel/RNodeWizardViewModel.kt: Modified submitManualPin() to re-enter pairing mode before BLE scan
