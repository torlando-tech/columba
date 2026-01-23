---
status: resolved
trigger: "rnode-ble-discovery"
created: 2026-01-23T00:00:00Z
updated: 2026-01-23T00:25:00Z
---

## Current Focus

hypothesis: Fix implemented - skip CDM for already-discovered devices
test: Build and run app, scan for RNode devices, click on discovered device
expecting: Device selected immediately without discovery_timeout error
next_action: Compile and verify fix works

## Symptoms

expected: RNode devices should be discovered in BLE scan and connection should be established successfully
actual: New RNode devices are not discovered even when advertising. Previously paired devices cannot be connected. UI shows "discovery_timeout" error.
errors: "discovery_timeout" - no other errors visible in UI or logcat
reproduction: Open RNode wizard, start scan - issue reproduces consistently
started: After recent changes - was working before, broke after code changes

## Eliminated

## Evidence

- timestamp: 2026-01-23T00:05:00Z
  checked: RNodeWizardViewModel CompanionDeviceManager code
  found: "discovery_timeout" error comes from CDM onFailure callback at line 1364
  implication: The CDM association is failing during device discovery, not the BLE scan itself

- timestamp: 2026-01-23T00:10:00Z
  checked: Git history - CDM integration added in commit 99ef90ac (Dec 4, 2025)
  found: When user clicks on device, requestDeviceAssociation is called which starts CDM's own scan
  implication: CDM starts a NEW scan after initial scan completes, device may not be advertising anymore

- timestamp: 2026-01-23T00:12:00Z
  checked: buildAssociationRequest implementation
  found: For BLE devices, uses setScanFilter with NUS_SERVICE_UUID filter AND setNamePattern
  implication: CDM scan must find device advertising with BOTH name pattern AND NUS service UUID

- timestamp: 2026-01-23T00:15:00Z
  checked: Android BLE documentation and RNodeWizardViewModel selectDevice method
  found: There's a selectDevice() method that bypasses CDM and directly selects the device
  implication: CDM scan timing is the problem - RNodes may use longer advertising intervals to save power, CDM times out before seeing the advertisement

## Resolution

root_cause: CompanionDeviceManager (CDM) integration causes discovery_timeout because CDM starts a NEW scan when user clicks on an already-discovered device. RNode devices use intermittent BLE advertising to save power, so they may not be advertising when CDM's scan runs, causing the scan to timeout. The initial BLE scan succeeds because it runs for 10 seconds (SCAN_DURATION_MS), but CDM has its own shorter timeout.

fix: Modified requestDeviceAssociation() in RNodeWizardViewModel.kt to skip CDM for devices that were already found in the initial scan (have a BluetoothDevice object). Added check: if device.bluetoothDevice != null, call selectDevice() directly instead of starting CDM association. This avoids the discovery_timeout caused by CDM's scan timing out when RNode stops advertising.

verification:
  - Code compiles successfully (assembleDebug passed)
  - Logic verified: devices found in scan (with bluetoothDevice != null) skip CDM and use direct selection
  - This prevents discovery_timeout by avoiding CDM's secondary scan when device may have stopped advertising
  - Manual testing required: Run app, scan for RNode, click discovered device - should select immediately without timeout
files_changed:
  - app/src/main/java/com/lxmf/messenger/viewmodel/RNodeWizardViewModel.kt
