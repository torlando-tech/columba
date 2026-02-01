---
status: resolved
trigger: "Tapping 'Enter Bluetooth Pairing Mode' on USB tab crashes with 'need android.permission.BLUETOOTH_CONNECT' instead of prompting for permission"
created: 2026-01-24T12:00:00Z
updated: 2026-01-24T12:20:00Z
---

## Current Focus

hypothesis: CONFIRMED AND FIXED
test: Implemented permission check in UI before calling BT pairing methods
expecting: App now prompts for permission instead of crashing
next_action: Archive session

## Symptoms

expected: When tapping "Enter Bluetooth Pairing Mode", the app should check if Bluetooth permission is granted. If not, prompt the user to grant the permission before attempting to use Bluetooth features.
actual: Shows error "need android.permission.BLUETOOTH_CONNECT" - crashes or shows error without giving user chance to grant permission
errors: need android.permission.BLUETOOTH_CONNECT
reproduction: 1. Deny or revoke Bluetooth permission, 2. Go to USB tab in Select RNode Device screen, 3. Tap "Enter Bluetooth Pairing Mode" button
started: Current behavior in fix/usb-assisted-ble-pairing branch

## Eliminated

## Evidence

- timestamp: 2026-01-24T12:05:00Z
  checked: DeviceDiscoveryStep.kt line 1089 - "Enter Bluetooth Pairing Mode" button onClick
  found: Calls viewModel.enterUsbBluetoothPairingMode() directly without any permission check in UI
  implication: UI layer does not check permissions before calling ViewModel method

- timestamp: 2026-01-24T12:06:00Z
  checked: RNodeWizardViewModel.kt line 1814 - enterUsbBluetoothPairingMode()
  found: Function calls startClassicBluetoothDiscoveryForPairing() and startBleScanForPairingEarly() without any permission check
  implication: ViewModel layer also does not check permissions

- timestamp: 2026-01-24T12:07:00Z
  checked: RNodeWizardViewModel.kt lines 2327 and 2445
  found: Both startClassicBluetoothDiscoveryForPairing() and startBleScanForPairingEarly() use @SuppressLint("MissingPermission") and call bluetoothAdapter?.startDiscovery() and scanner.startScan() which require BLUETOOTH_SCAN permission on Android 12+
  implication: These are the actual calls that fail without permission

- timestamp: 2026-01-24T12:08:00Z
  checked: DeviceDiscoveryStep.kt lines 103-126 - Bluetooth tab permission handling
  found: Bluetooth tab uses LaunchedEffect to check BlePermissionManager.hasAllPermissions() and launches permissionLauncher if missing
  implication: Pattern exists in codebase - need to apply similar pattern for USB tab's BT pairing button

## Resolution

root_cause: enterUsbBluetoothPairingMode() and startUsbAssistedPairing() both use Bluetooth operations (discovery/scanning) without checking for BLUETOOTH_SCAN/BLUETOOTH_CONNECT permissions first. Both UI buttons call these methods directly without permission checks.

fix: Added permission checking to DeviceDiscoveryStep.kt:
1. Created PendingBluetoothAction enum to track what action needs permissions
2. Added pendingBluetoothAction state to track pending action after permission grant
3. Modified permissionLauncher callback to execute the pending action (SCAN_DEVICES, ENTER_USB_PAIRING_MODE, or START_USB_ASSISTED_PAIRING)
4. Added requiredBluetoothPermissions computed once and reused
5. Added requestBluetoothPermissionsIfNeeded() helper function
6. Modified BluetoothDeviceDiscovery to accept onStartUsbAssistedPairing callback
7. Modified UsbDeviceDiscovery to accept onEnterBluetoothPairingMode callback
8. Both buttons now check permissions before calling ViewModel methods

verification:
- Build successful (no compile errors)
- All 20 DeviceDiscoveryStepTest unit tests pass
- App installed on device without crashes
- Code review confirms permission check now occurs before BT operations

files_changed:
- app/src/main/java/com/lxmf/messenger/ui/screens/rnode/DeviceDiscoveryStep.kt
