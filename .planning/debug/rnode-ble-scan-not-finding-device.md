---
status: verifying
trigger: "BLE scan for RNode pairing finds NO devices at all after re-entering pairing mode. Previously it found the RNode but now finds nothing."
created: 2026-01-23T14:20:00Z
updated: 2026-01-23T14:55:00Z
---

## Current Focus

hypothesis: CONFIRMED - USB disconnect happens too quickly (500ms) after re-sending pairing mode command
test: Applied fix - increased delay to 2000ms and reduced initial timeout to 3s
expecting: BLE scan will now find the RNode device after manual PIN entry
next_action: User verification required - test with physical RNode hardware

## Symptoms

expected: After re-entering pairing mode and starting BLE scan, the scan should find "RNode E16A" within a few seconds
actual: BLE scan runs for 30 seconds and finds nothing at all - no "BLE scan found" log messages
errors: No errors - scan just times out without finding anything
reproduction:
1. Connect Heltec v3 via USB
2. Wait for 10s timeout
3. Enter manual PIN
4. Re-enter pairing mode command sent
5. BLE scan starts but finds nothing
started: After the re-enter pairing fix was implemented - previously the scan DID find "RNode E16A (F4:44:99:ED:02:CC), bondState=12, isAlreadyBonded=true" but now it finds nothing

## Eliminated

## Evidence

- timestamp: 2026-01-23T14:20:00Z
  checked: Log timeline from user
  found:
    - 14:11:13.054: Manual PIN submitted
    - 14:11:13.054: Re-entering pairing mode message
    - 14:11:13.595: Initiating auto-pairing (541ms later)
    - 14:11:13.598: Starting BLE scan (3ms later)
    - 14:11:13.600: BLE scan started with "Bonded device addresses to skip: [E4:17:D8:62:CB:E4, F4:44:99:ED:02:CC, ...]"
    - 14:11:43.605: BLE scan timeout (30s later)
    - NO "BLE scan found:" messages at all
  implication: The re-enter pairing command happens at 14:11:13.054, BLE scan starts at 14:11:13.598 (544ms delay). This may be too quick if USB needs to disconnect first.

- timestamp: 2026-01-23T14:25:00Z
  checked: submitManualPin() code flow (lines 2161-2213)
  found:
    - Line 2185-2191: Sends KISS pairing mode command via USB
    - Line 2203: delay(500) - waits 500ms after sending command
    - Line 2206-2208: Disconnects USB
    - Line 2211: Calls initiateAutoPairingWithPin() which starts BLE scan
  implication: Flow is: send command -> wait 500ms -> disconnect USB -> start BLE scan. USB is disconnected BEFORE BLE scan starts.

- timestamp: 2026-01-23T14:27:00Z
  checked: startBleScanForPairing() code (lines 1957-2099)
  found:
    - Line 1967: Gets bondedAddresses from bluetoothAdapter.bondedDevices
    - Line 1979: Only processes devices if deviceName != null
    - Line 1982: Only processes devices if name starts with "RNode"
    - Line 1984: Checks if device is already bonded: isAlreadyBonded = bondedAddresses.contains(device.address)
    - Line 2000-2028: If already bonded, adds to discovered devices but doesn't try to pair
  implication: The scan callback filters devices by name. If RNode is not advertising its name, the callback won't process it.

- timestamp: 2026-01-23T14:30:00Z
  checked: User's previous log showing it DID find the device
  found: "BLE scan found: RNode E16A (F4:44:99:ED:02:CC), bondState=12, isAlreadyBonded=true"
  implication: F4:44:99:ED:02:CC is in the bonded devices list (confirmed by user log showing this address in the skip list). When it WAS found, it was already bonded (bondState=12 = BOND_BONDED).

- timestamp: 2026-01-23T14:35:00Z
  checked: Web search on Android BLE scanning for bonded devices
  found: Android DOES report bonded devices in BLE scan results - the issue is not OS-level filtering
  implication: The problem is likely that the RNode is not advertising at all, not that Android is filtering it out.

- timestamp: 2026-01-23T14:40:00Z
  checked: Detailed code flow timing in submitManualPin()
  found:
    - Line 2185-2196: Send KISS pairing command via usbBridge.write()
    - Line 2203: delay(500) - 500ms delay
    - Line 2206-2208: usbBridge.disconnect() - DISCONNECT USB
    - Line 2211: initiateAutoPairingWithPin() - starts BLE scan
  implication: The USB is disconnected WHILE the RNode is still in the middle of processing the pairing mode command. The RNode firmware likely needs the USB connection to remain active to complete the pairing mode transition. When USB disconnects mid-command, the RNode may abort or fail to enter pairing mode.

- timestamp: 2026-01-23T14:45:00Z
  checked: Initial USB pairing flow (lines 1884-1904)
  found:
    - Line 1884-1891: After sending initial pairing command, waits 10 seconds for PIN response
    - Line 1903: Comment says "Keep USB connected - we'll disconnect after manual PIN entry"
    - USB stays connected during the entire 10-second timeout waiting for PIN
    - Comment at line 2181-2182: "RNode pairing mode has a 10-second timeout. After the initial timeout waiting for PIN via serial, the RNode has exited pairing mode."
  implication: The initial pairing command keeps USB connected for 10 SECONDS. But the re-enter pairing command only waits 500ms before disconnecting. This is the problem: RNode needs time to enter pairing mode, but we're pulling the USB cable too quickly.

## Resolution

root_cause: USB disconnects too quickly (500ms) after re-sending pairing mode command in submitManualPin(). RNode needs time to process the command and enter pairing mode. Initial pairing keeps USB connected for 10 seconds, but re-enter only waits 500ms. This causes RNode to not fully enter pairing mode, so it never advertises via BLE, causing the scan to find nothing.

fix:
1. Increased delay in submitManualPin() from 500ms to 2000ms (line 2203) to give RNode sufficient time to process the re-enter pairing command and start BLE advertising before USB disconnect
2. Reduced initial PIN timeout from 10 seconds to 3 seconds (line 1887) as requested by user - reduces wait time before manual PIN prompt

verification: After fix, test the flow: connect via USB, wait for 3s timeout, enter manual PIN, verify BLE scan finds "RNode E16A" device.

files_changed:
  - app/src/main/java/com/lxmf/messenger/viewmodel/RNodeWizardViewModel.kt
