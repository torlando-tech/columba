# BLE Troubleshooting Guide

Comprehensive troubleshooting for common BLE issues in Columba.

## Quick Diagnosis

**Use this decision tree:**

```
Issue: BLE not working?
    ↓
Is Bluetooth enabled?
    No → Enable in Settings
    Yes ↓
Are permissions granted?
    No → Grant in app or Settings → Apps → Columba → Permissions
    Yes ↓
Is scan starting?
    No → Check logs for errors (see Debugging section)
    Yes ↓
Are devices discovered?
    No → Check if other device is advertising, check UUID match
    Yes ↓
Can connect to devices?
    No → See "Connection Failures" section
    Yes ↓
Is data being sent/received?
    No → See "Data Transfer Issues" section
    Yes ↓
Performance issues?
    → See "Performance Optimization" section
```

---

## Common Errors & Solutions

### Error: "Bluetooth permissions denied"

**Symptoms:**
- Permission dialog doesn't appear
- Operations fail with SecurityException
- Logs show "Permission denied"

**Solution 1: Request permissions**
```kotlin
val permissions = BlePermissionManager.getRequiredPermissions()
requestMultiplePermissions.launch(permissions)
```

**Solution 2: Manual grant**
```bash
# Via ADB
adb shell pm grant com.lxmf.messenger android.permission.BLUETOOTH_SCAN
adb shell pm grant com.lxmf.messenger android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.lxmf.messenger android.permission.BLUETOOTH_ADVERTISE
```

**Solution 3: Check Settings**
Settings → Apps → Columba → Permissions → Enable all Bluetooth permissions

### Error: "BLE Advertiser not available"

**Symptoms:**
- `bluetoothAdapter.bluetoothLeAdvertiser` is `null`
- Peripheral mode fails to start
- Logs show "Advertiser not available"

**Causes:**
- Device doesn't support peripheral mode (pre-Android 5.0)
- Hardware doesn't support multiple advertisements
- Bluetooth is disabled

**Solutions:**
```kotlin
// Check support
if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
    showError("This device doesn't support BLE peripheral mode")
    // Fallback: central mode only
    enableCentralModeOnly()
}

// Check Bluetooth enabled
if (!bluetoothAdapter.isEnabled) {
    requestEnableBluetooth()
}
```

### Error: Scan failed (error code X)

| Code | Error | Cause | Solution |
|------|-------|-------|----------|
| **1** | SCAN_FAILED_ALREADY_STARTED | Already scanning | Call `stopScan()` first |
| **2** | SCAN_FAILED_APPLICATION_REGISTRATION_FAILED | Too many scans | Wait 30s, reduce scan frequency |
| **3** | SCAN_FAILED_INTERNAL_ERROR | Bluetooth stack issue | Restart Bluetooth or reboot |
| **4** | SCAN_FAILED_FEATURE_UNSUPPORTED | BLE not supported | Device incompatible |
| **5** | SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES | Too many apps scanning | Close other BLE apps |

**Solution for Error 2 (Most Common):**
```kotlin
// Exceeded 5 scans per 30s limit
// Columba's adaptive scanning prevents this:

// ❌ WRONG - scans every 5s (6 per 30s)
while (true) {
    startScan()
    delay(5000)
}

// ✅ CORRECT - scan for 10s, pause 20s (1 per 30s)
while (true) {
    startScan()
    delay(10000)
    stopScan()
    delay(20000)
}
```

### Error: Status 133 (GATT_ERROR)

**Most common GATT error** (~30-50% of connection attempts)

**Causes:**
- Connection timeout
- Bluetooth stack busy
- Previous connection not cleaned up
- Device out of range
- Too many connections

**Solutions:**

**Solution 1: Retry with exponential backoff**
```kotlin
private var retryCount = 0

override fun onConnectionStateChange(gatt, status, newState) {
    when (status) {
        133 -> {
            gatt.close()  // MUST close
            if (retryCount < 3) {
                retryCount++
                val backoffMs = 1000L * (1L shl retryCount)  // 2s, 4s, 8s
                delay(backoffMs)
                connect(address)  // Retry
            } else {
                blacklistDevice(address, duration = 5.minutes)
            }
        }
    }
}
```

**Solution 2: Try autoConnect mode**
```kotlin
// First attempt: autoConnect = false (faster but less reliable)
device.connectGatt(context, autoConnect = false, callback)

// If Status 133 persists: autoConnect = true (slower but more reliable)
device.connectGatt(context, autoConnect = true, callback)
```

**Solution 3: Clear Bluetooth cache (drastic)**
```bash
adb shell su -c "rm -rf /data/misc/bluedroid/*"
# Then reboot device
```

---

## Connection Failures

### Symptom: "Connecting..." forever

**Timeout:** Connection stuck, no callback

**Causes:**
- Device out of range
- Device not advertising
- Bluetooth interference
- Connection limit reached

**Solutions:**
```kotlin
// Implement connection timeout
val timeoutJob = scope.launch {
    delay(30000)  // 30s timeout
    if (gatt.connectionState != STATE_CONNECTED) {
        gatt.disconnect()
        gatt.close()
        onConnectionFailed("Timeout")
    }
}

// Cancel timeout on success
override fun onConnectionStateChange(...) {
    if (newState == STATE_CONNECTED) {
        timeoutJob.cancel()
    }
}
```

### Symptom: Connects then immediately disconnects

**Causes:**
- Peer doesn't have Reticulum service
- Service discovery fails
- Characteristic not found
- Permission issues on peer

**Solutions:**
```kotlin
override fun onServicesDiscovered(gatt, status) {
    val service = gatt.getService(RETICULUM_UUID)
    if (service == null) {
        log("❌ Reticulum service not found - disconnecting")
        gatt.disconnect()
        return
    }

    val rxChar = service.getCharacteristic(RX_UUID)
    val txChar = service.getCharacteristic(TX_UUID)
    if (rxChar == null || txChar == null) {
        log("❌ Required characteristics not found")
        gatt.disconnect()
        return
    }

    // Characteristics found - continue setup
}
```

---

## Data Transfer Issues

### Symptom: Writes fail silently

**Cause:** Not using operation queue (multiple operations in progress)

**Solution:**
```kotlin
// ❌ WRONG
gatt.writeCharacteristic(char, data1)
gatt.writeCharacteristic(char, data2)  // Fails silently!

// ✅ CORRECT
operationQueue.enqueue(WriteCharacteristic(gatt, char, data1))
operationQueue.enqueue(WriteCharacteristic(gatt, char, data2))  // Waits for data1
```

### Symptom: Notifications not received

**Cause:** CCCD (Client Characteristic Configuration Descriptor) not enabled

**Solution:**
```kotlin
// 1. Enable local notifications
gatt.setCharacteristicNotification(txChar, true)

// 2. Write CCCD descriptor
val cccd = txChar.getDescriptor(CCCD_UUID)
cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
operationQueue.enqueue(WriteDescriptor(gatt, cccd, cccd.value))

// 3. Wait for callback
override fun onDescriptorWrite(gatt, descriptor, status) {
    if (status == GATT_SUCCESS) {
        log("✅ Notifications enabled")
    }
}
```

### Symptom: Packets never complete (reassembly timeout)

**Cause:** Fragments lost or wrong sender ID

**Solutions:**

**Check fragment loss:**
```kotlin
val stats = reassembler.getStatistics()
log("Packets timed out: ${stats.packetsTimedOut}")  // High? Fragment loss!
log("Packets in progress: ${stats.packetsInProgress}")  // Growing? Memory leak!
```

**Check sender ID consistency:**
```kotlin
// Same peer must use same sender ID for all fragments
Log.d(TAG, "Fragment from: $senderId")

// Verify buffer state
val buffer = reassemblyBuffers[senderId]
Log.d(TAG, "Buffer: ${buffer?.fragments?.size}/${buffer?.totalFragments} fragments")
```

**Reduce timeout if needed:**
```kotlin
// Default: 30s
val reassembler = BleReassembler(timeoutMs = 30000)

// For debugging: shorter timeout reveals issues faster
val reassembler = BleReassembler(timeoutMs = 10000)  // 10s
```

---

## Performance Issues

### Symptom: High battery drain

**Causes:**
- Continuous scanning (not using adaptive intervals)
- Too many connections
- High scan mode (LOW_LATENCY)

**Solutions:**

**Use adaptive scanning:**
```kotlin
// Columba already implements this in BleScanner
// - Active: 5s intervals, BALANCED/LOW_LATENCY mode
// - Idle: 30s intervals, LOW_POWER mode

// Verify scan mode
Log.d(TAG, "Scan mode: ${determineScanMode()}")  // Should be LOW_POWER when idle
```

**Limit connections:**
```kotlin
// Max 7 connections (configurable)
if (connections.size >= MAX_CONNECTIONS) {
    // Don't connect to more devices
}
```

**Stop scanning when not needed:**
```kotlin
// Stop scanning after discovering enough devices
if (discoveredDevices.size >= 5) {
    scanner.stopScanning()
}
```

### Symptom: Slow packet transmission

**Causes:**
- Low MTU (many fragments)
- Operation queue backed up
- Poor signal (retransmissions)

**Solutions:**

**Check MTU:**
```kotlin
val mtu = connections[address]?.centralMtu ?: 23
log("Current MTU: $mtu")
if (mtu < 185) {
    log("⚠️ MTU is low, transmission will be slow")
    log("  Did MTU negotiation fail?")
}
```

**Check operation queue:**
```kotlin
val pendingOps = operationQueue.getPendingOperationCount()
log("Pending operations: $pendingOps")
if (pendingOps > 10) {
    log("⚠️ Operation queue backed up")
    log("  Too many writes queued? Throttle sends!")
}
```

**Check signal strength:**
```kotlin
val rssi = device.rssi
log("RSSI: $rssi dBm")
if (rssi < -80) {
    log("⚠️ Weak signal, move closer")
}
```

---

## Debugging Tools & Commands

### Log Tags

**Columba BLE log tags:**
```bash
BleService              # Service lifecycle, AIDL calls
BleConnectionManager    # Connection orchestration, state changes
BleScanner              # Discovery, scan intervals
BleGattClient           # Central mode operations
BleGattServer           # Peripheral mode operations
BleAdvertiser           # Advertising lifecycle
BleOperationQueue       # GATT operation execution
BleFragmenter           # Packet fragmentation
BleReassembler          # Fragment reassembly
```

**View all BLE logs:**
```bash
adb logcat -s BleService:D BleConnectionManager:D BleScanner:D BleGattClient:D BleGattServer:D
```

**View errors only:**
```bash
adb logcat BleService:E BleConnectionManager:E BleScanner:E BleGattClient:E *:S
```

### ADB Commands

**Check Bluetooth state:**
```bash
adb shell settings get global bluetooth_on
# Output: 1 (enabled) or 0 (disabled)
```

**Enable/Disable Bluetooth:**
```bash
adb shell svc bluetooth enable
adb shell svc bluetooth disable
```

**Check service status:**
```bash
adb shell dumpsys activity services | grep -A 20 BleService
```

**Check permissions:**
```bash
adb shell dumpsys package com.lxmf.messenger | grep -A 5 "permission"
```

**Grant permissions manually:**
```bash
adb shell pm grant com.lxmf.messenger android.permission.BLUETOOTH_SCAN
adb shell pm grant com.lxmf.messenger android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.lxmf.messenger android.permission.BLUETOOTH_ADVERTISE
```

**Enable HCI snoop logging (packet capture):**
```bash
# Enable
adb shell setprop persist.bluetooth.btsnooplogmode full
adb shell svc bluetooth disable && adb shell svc bluetooth enable

# Logs saved to: /data/misc/bluetooth/logs/btsnoop_hci.log

# Pull logs
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log

# Analyze with Wireshark
wireshark btsnoop_hci.log
```

### System Dumps

**Full Bluetooth dump:**
```bash
adb shell dumpsys bluetooth_manager > bluetooth_dump.txt
```

**Battery stats (check BLE impact):**
```bash
adb shell dumpsys batterystats > battery_dump.txt
# Search for: "Bluetooth"
```

---

## Testing with nRF Connect

**nRF Connect** (Nordic Semiconductor) is the professional BLE testing tool.

### Test as GATT Server (Peripheral Mode)

**Setup:**
1. Start Columba BleService
2. Tap "Advertise"
3. Open nRF Connect on another phone
4. Tap "SCAN"

**Verify:**
- ✅ Device appears: "Reticulum-XXX"
- ✅ UUID shown: `00000001-5824-4f48-9e1a-3b3e8f0c1234`
- ✅ RSSI updates

**Connect:**
1. Tap "CONNECT" in nRF Connect
2. Should see:
   - Service: `00000001-5824...`
   - RX Char: `00000002-...` (WRITE property)
   - TX Char: `00000003-...` (NOTIFY property)

**Test Write (Send data to Columba):**
1. Tap RX characteristic
2. Select "WRITE"
3. Enter hex data: `48656C6C6F` (Hello)
4. Tap "SEND"
5. Check Columba logs: Should see "Received 5 bytes from XX:XX:XX:XX:XX:XX"

**Test Notify (Receive data from Columba):**
1. Tap TX characteristic
2. Enable notifications (tap icon)
3. Trigger send from Columba
4. Should see notification in nRF Connect

### Test as GATT Client (Central Mode)

**Setup:**
1. Open nRF Connect on Phone 1
2. Tap "ADVERTISER" tab
3. Tap "+" → "Add advertising packet"
4. Add service UUID: `00000001-5824-4f48-9e1a-3b3e8f0c1234`
5. Tap "START"

**Verify:**
1. Open Columba on Phone 2
2. Tap "Start Scan"
3. Phone 1 should appear in discovered devices
4. RSSI should update

**Note:** nRF Connect advertising doesn't create a full GATT server, so connection may fail. For full testing, use two Columba devices.

---

## Status Codes Reference

### GATT Status Codes

| Code | Name | Meaning | Action |
|------|------|---------|--------|
| **0** | GATT_SUCCESS | Operation succeeded | Continue |
| **8** | GATT_CONN_TIMEOUT | Connection timeout | Retry |
| **13** | GATT_INVALID_HANDLE | Handle invalid | Rediscover services |
| **15** | GATT_INSUFFICIENT_AUTHENTICATION | Auth required | Pair device |
| **22** | GATT_CONN_TERMINATE_PEER_USER | Peer disconnected | Normal, reconnect if needed |
| **62** | GATT_NO_RESOURCES | Stack resources exhausted | Wait, retry |
| **133** | GATT_ERROR | Generic error (undocumented) | Close, retry with backoff |
| **257** | GATT_CONN_TERMINATE_LOCAL_HOST | We disconnected | Normal |

### BluetoothProfile State Codes

| Code | Name | Meaning |
|------|------|---------|
| **0** | STATE_DISCONNECTED | Not connected |
| **1** | STATE_CONNECTING | Connection in progress |
| **2** | STATE_CONNECTED | Connected |
| **3** | STATE_DISCONNECTING | Disconnection in progress |

---

## Log Analysis

### Normal Connection Flow

**Expected logs:**
```
BleScanner: Started scan (interval: 5000ms, mode: BALANCED, minRssi: -85)
BleScanner: Discovered new device: AA:BB:CC:DD:EE:FF (Reticulum-Node) RSSI: -45 dBm
BleConnectionManager: Auto-connecting to AA:BB:CC:DD:EE:FF
BleGattClient: Connecting to AA:BB:CC:DD:EE:FF...
BleGattClient: Connected to AA:BB:CC:DD:EE:FF, discovering services...
BleGattClient: Services discovered on AA:BB:CC:DD:EE:FF, requesting MTU...
BleGattClient: MTU changed for AA:BB:CC:DD:EE:FF: 182 bytes (requested: 517)
BleGattClient: Notifications enabled for AA:BB:CC:DD:EE:FF
BleConnectionManager: Central connection established: AA:BB:CC:DD:EE:FF (MTU: 182)
```

**Time:** ~3-6 seconds total

### Failed Connection (Status 133)

**Expected logs:**
```
BleGattClient: Connecting to AA:BB:CC:DD:EE:FF...
BleGattClient: GATT error 133 for AA:BB:CC:DD:EE:FF
BleGattClient: Retrying connection to AA:BB:CC:DD:EE:FF (attempt 1/3) in 30000ms
[Wait 30s]
BleGattClient: Connecting to AA:BB:CC:DD:EE:FF...
BleGattClient: Connected to AA:BB:CC:DD:EE:FF, discovering services...
[Success on retry]
```

**Time:** ~30-35 seconds (including retry)

### Data Transfer Logs

**Sending:**
```
BleConnectionManager: Sending 500 bytes to AA:BB:CC:DD:EE:FF in 3 fragments (MTU: 182)
BleFragmenter: Fragmenting 500 bytes into 3 fragments (MTU: 185)
BleGattClient: Sent 185 bytes to AA:BB:CC:DD:EE:FF
BleGattClient: Write successful to AA:BB:CC:DD:EE:FF
BleGattClient: Sent 185 bytes to AA:BB:CC:DD:EE:FF
BleGattClient: Write successful to AA:BB:CC:DD:EE:FF
BleGattClient: Sent 135 bytes to AA:BB:CC:DD:EE:FF
BleGattClient: Write successful to AA:BB:CC:DD:EE:FF
```

**Receiving:**
```
BleGattClient: Received 185 bytes from AA:BB:CC:DD:EE:FF
BleReassembler: Received fragment seq=0/3 from AA:BB:CC:DD:EE:FF (180 bytes)
BleGattClient: Received 185 bytes from AA:BB:CC:DD:EE:FF
BleReassembler: Received fragment seq=1/3 from AA:BB:CC:DD:EE:FF (180 bytes)
BleGattClient: Received 135 bytes from AA:BB:CC:DD:EE:FF
BleReassembler: Received fragment seq=2/3 from AA:BB:CC:DD:EE:FF (130 bytes)
BleReassembler: Reassembled packet from AA:BB:CC:DD:EE:FF: 3 fragments, 490 bytes
BleConnectionManager: Received complete packet from AA:BB:CC:DD:EE:FF: 490 bytes
```

---

## Performance Analysis

### Check Statistics

```kotlin
val stats = connectionManager.getStatistics()
println("""
    Central connections: ${stats.totalCentralConnections}
    Peripheral connections: ${stats.totalPeripheralConnections}
    Packets sent: ${stats.packetsSent}
    Packets received: ${stats.packetsReceived}
    Bytes sent: ${stats.bytesSent}
    Bytes received: ${stats.bytesReceived}
    Fragments sent: ${stats.fragmentsSent}
    Fragments received: ${stats.fragmentsReceived}
""")

// Calculate efficiency
val avgFragmentsPerPacket = stats.fragmentsSent.toDouble() / stats.packetsSent
println("Avg fragments/packet: $avgFragmentsPerPacket")  // Should be < 3 if MTU good
```

### Battery Impact Analysis

```bash
# Check battery usage
adb shell dumpsys batterystats | grep -A 20 "com.lxmf.messenger"

# Reset battery stats (requires root)
adb shell dumpsys batterystats --reset

# Run for 1 hour, then check again
```

**Expected:**
- Active scanning: 3-5% per hour
- Idle scanning: 1-2% per hour
- Connected (idle): < 1% per hour

**If higher:** Check scan intervals, reduce connection count

---

## Edge Cases

### Multiple Connections from Same Peer

**Scenario:** Peer connects to us twice (e.g., app restart)

**Handling:**
```kotlin
override fun onCentralConnected(address: String) {
    val existing = connections[address]
    if (existing?.hasPeripheralConnection == true) {
        log("⚠️ Duplicate peripheral connection from $address (keeping latest)")
        // Android will handle cleanup of old connection
    }

    // Store new connection
    connections[address] = ConnectionInfo(address, hasPeripheralConnection = true)
}
```

### Rapid Connect/Disconnect Cycles

**Scenario:** Peer rapidly connects/disconnects (poor signal)

**Handling:**
```kotlin
// Count disconnects in time window
private val disconnectTimes = mutableMapOf<String, MutableList<Long>>()

fun recordDisconnect(address: String) {
    val times = disconnectTimes.getOrPut(address) { mutableListOf() }
    times.add(System.currentTimeMillis())

    // Remove old entries (> 60s ago)
    times.removeIf { it < System.currentTimeMillis() - 60000 }

    // Too many disconnects?
    if (times.size > 5) {
        log("⚠️ Unstable connection to $address (${times.size} disconnects in 60s)")
        blacklistDevice(address, duration = 5.minutes)
    }
}
```

### Device Moves Out of Range During Transfer

**Scenario:** Packet partially sent, then disconnection

**Handling:**
```kotlin
override fun onConnectionStateChange(gatt, status, newState) {
    if (newState == STATE_DISCONNECTED) {
        // Clean up pending operations
        operationQueue.clearPendingOperations()

        // Clear reassembly buffer
        reassembler.clearSender(address)

        log("Connection lost mid-transfer, buffers cleared")
    }
}
```

---

## When to Restart Bluetooth

**Symptoms indicating Bluetooth stack issues:**
- Status 133 on all connections (not just one device)
- Operations fail with "Bluetooth is off" but settings show enabled
- Scanning produces no results
- Random disconnections on all devices

**Solution:**
```kotlin
fun restartBluetooth() {
    val bluetoothAdapter = bluetoothManager.adapter

    // Disable
    bluetoothAdapter.disable()

    // Wait for state change
    delay(1000)

    // Enable
    bluetoothAdapter.enable()

    // Wait for state change
    delay(2000)

    // Resume operations
    connectionManager.start()
}
```

**Or manually:**
Settings → Bluetooth → Toggle off/on

---

## Support Resources

**Android Official:**
- [BLE Guide](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)
- [BLE Permissions](https://developer.android.com/guide/topics/connectivity/bluetooth/permissions)

**Nordic (Best Practices):**
- [Android BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library)
- [BLE Common Problems](https://devzone.nordicsemi.com/guides/short-range-guides/b/bluetooth-low-energy/posts/ble-characteristics-a-beginners-tutorial)

**Reticulum:**
- [ble-reticulum](https://github.com/markqvist/ble-reticulum) - Python reference implementation

**Testing:**
- [nRF Connect](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp) - Professional BLE tool
- [LightBlue Explorer](https://play.google.com/store/apps/details?id=com.punchthrough.lightblueexplorer) - Alternative

---

## Getting Additional Help

**Before asking for help, gather:**
1. **Device info**: Model, Android version, Bluetooth version
2. **Logs**: `adb logcat` output showing the issue
3. **Steps to reproduce**: Exact sequence that causes problem
4. **Statistics**: `connectionManager.getStatistics()`
5. **State**: `connectionManager.getConnectionState()`

**Where to look:**
1. This troubleshooting guide (you are here!)
2. `ANDROID_BLE_RULES.md` - Check if following rules
3. `BLE_ARCHITECTURE_OVERVIEW.md` - Understand expected behavior
4. Columba GitHub issues
5. Nordic DevZone forums (general Android BLE)

---

## See Also

- `ANDROID_BLE_RULES.md` - Rules to avoid issues
- `BLE_ARCHITECTURE_OVERVIEW.md` - Expected architecture
- `FRAGMENTATION_PROTOCOL.md` - Fragmentation details
- `../checklists/testing-scenarios-checklist.md` - Test scenarios
