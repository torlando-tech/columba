# Columba BLE Networking - Quick Reference

Fast reference for implementing and debugging BLE mesh networking on Android.

## 30-Second Overview

**What:** Native Kotlin BLE implementation for Reticulum mesh networking on Android

**Why:** Enables decentralized messaging over Bluetooth without infrastructure

**Where:** `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/`

**Key Components:**
- BleScanner - Adaptive discovery (2s → 30s intervals)
- BleGattClient - Connect to peers (central mode)
- BleGattServer - Accept connections (peripheral mode)
- BleFragmenter/Reassembler - MTU-aware packet handling
- BleOperationQueue - Serial GATT execution (critical!)
- BleConnectionManager - Orchestrates everything

---

## Quick Start

### 1. Test BLE on Your Phone

```bash
# Build & install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# In app:
# Settings → BLE Mesh Networking → Open BLE Test
# Grant permissions → Start Service → Start Scan
```

### 2. Scan for Devices

```kotlin
val scanner = BleScanner(context, bluetoothAdapter, scope)
scanner.onDeviceDiscovered = { device ->
    println("${device.name}: ${device.rssi} dBm")
}
scanner.startScanning()
```

### 3. Connect to Peer

```kotlin
val client = BleGattClient(context, bluetoothAdapter, operationQueue, scope)
client.onConnected = { address, mtu -> println("Connected! MTU: $mtu") }
client.connect("AA:BB:CC:DD:EE:FF")
```

### 4. Send Data

```kotlin
val connectionManager = BleConnectionManager(context, bluetoothManager, scope)
connectionManager.sendData("AA:BB:CC:DD:EE:FF", packet)
// Automatically fragments if > MTU
```

---

## Critical Rules (Don't Skip!)

### Rule #1: Always Use Operation Queue
```kotlin
// ❌ NEVER: Multiple operations without queue
gatt.read(char1)
gatt.write(char2)  // FAILS SILENTLY!

// ✅ ALWAYS: Queue operations
operationQueue.enqueue(ReadCharacteristic(gatt, char1))
operationQueue.enqueue(WriteCharacteristic(gatt, char2, data))
```

### Rule #2: Handle Status 133
```kotlin
if (status == 133) {
    gatt.close()
    delay(backoff)
    retry()
}
```

### Rule #3: Update Fragmenter MTU
```kotlin
override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
    fragmenter.updateMtu(mtu - 3)  // Don't forget!
}
```

### Rule #4: Check Permissions (Android 12+)
```kotlin
// Required: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE
BlePermissionManager.getRequiredPermissions()
```

### Rule #5: Use Foreground Service
```kotlin
// BLE in background requires foreground service
startForeground(NOTIFICATION_ID, notification)
```

---

## Component Quick Ref

| Component | Purpose | Key Method | Location |
|-----------|---------|------------|----------|
| **BleScanner** | Discovery | `startScanning()` | `ble/client/` |
| **BleGattClient** | Connect as central | `connect(address)` | `ble/client/` |
| **BleGattServer** | Accept as peripheral | `open()` | `ble/server/` |
| **BleAdvertiser** | Broadcast presence | `startAdvertising(name)` | `ble/server/` |
| **BleFragmenter** | Split packets | `fragmentPacket(data)` | `ble/fragmentation/` |
| **BleReassembler** | Join fragments | `receiveFragment(fragment)` | `ble/fragmentation/` |
| **BleOperationQueue** | Serial GATT ops | `enqueue(operation)` | `ble/util/` ⭐ |
| **BleConnectionManager** | Orchestrate | `start()` | `ble/service/` ⭐⭐ |
| **BleService** | Foreground service | `onBind()` | `app/service/` |

---

## Common Commands

### Debugging
```bash
# BLE logs
adb logcat -s BleService:D BleConnectionManager:D BleScanner:D

# Service status
adb shell dumpsys activity services | grep BleService

# Check Bluetooth
adb shell settings get global bluetooth_on

# Enable HCI snoop (packet capture)
adb shell setprop persist.bluetooth.btsnooplogmode full
```

### Testing
```bash
# Install debug build
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Check permissions
adb shell dumpsys package com.lxmf.messenger | grep permission

# Force stop
adb shell am force-stop com.lxmf.messenger
```

---

## Error Codes Quick Ref

| Code | Name | Meaning | Solution |
|------|------|---------|----------|
| **133** | GATT_ERROR | Connection/stack issue | Retry with backoff |
| **8** | CONN_TIMEOUT | Connection timeout | Check range, retry |
| **22** | CONN_TERMINATE_PEER** | Peer disconnected | Normal, auto-reconnect |
| **62** | GATT_NO_RESOURCES | Stack busy | Wait, retry |
| **1** | SCAN_FAILED_ALREADY_STARTED | Already scanning | Stop first |
| **2** | SCAN_FAILED_APP_REG | Too many apps | Close other BLE apps |

---

## Performance Targets

- **Discovery:** < 5s
- **Connection:** < 5s
- **MTU Negotiation:** < 2s
- **Packet Latency:** < 100ms
- **Throughput:** > 50 kbps
- **Battery (scan):** < 5%/hr
- **Battery (idle):** < 2%/hr

---

## File Locations

**Implementation:**
- `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/` - All BLE code
- `app/src/main/java/com/lxmf/messenger/service/BleService.kt` - Service
- `app/src/main/java/com/lxmf/messenger/ui/screens/BleTestScreen.kt` - UI

**Documentation:**
- `.claude/skills/columba-ble-networking/` - This skill
- `BLE_ARCHITECTURE.md` - Architecture doc
- `BLE_DEVELOPER_GUIDE.md` - Dev guide
- `BLE_TESTING_GUIDE.md` - Testing guide

**Configuration:**
- `app/src/main/AndroidManifest.xml` - Permissions, service registration
- `reticulum/build.gradle.kts` - Dependencies, AIDL config

---

## Next Steps

**New to BLE?** → Read `docs/BLE_ARCHITECTURE_OVERVIEW.md`

**Implementing BLE?** → Follow `phases/phase-1-discovery.md`

**Debugging Issues?** → Check `docs/TROUBLESHOOTING.md`

**Testing?** → Use `checklists/testing-scenarios-checklist.md`

**Need Code?** → Copy from `templates/`

---

## Support

**Issues:** Check `docs/TROUBLESHOOTING.md` first

**Questions:** Search SKILL.md for keywords

**Testing:** Use nRF Connect app for external validation

**Logs:** `adb logcat -s BleService:D BleConnectionManager:D`
