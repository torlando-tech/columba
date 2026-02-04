# Android BLE Rules & Requirements

Critical rules, permissions, API levels, and limitations for Android BLE development.

## Permission Requirements by Android Version

### Android 12+ (API 31+) - Modern Permissions

**Required Permissions:**
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
```

**Runtime Request:**
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )
    requestPermissions(permissions, REQUEST_CODE)
}
```

**Key Notes:**
- `neverForLocation` flag: Allows scanning without location permission
- User sees "Nearby devices" prompt (not "Location")
- Must request at runtime (dangerous permissions)

### Android 6-11 (API 23-30) - Legacy Permissions

**Required Permissions:**
```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

**Runtime Request:**
```kotlin
if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
    val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    requestPermissions(permissions, REQUEST_CODE)
}
```

**Key Notes:**
- Location permission required for BLE scanning (Android requirement)
- User sees "Location" prompt (confusing but necessary)
- BLUETOOTH/BLUETOOTH_ADMIN are normal permissions (auto-granted)

### Android 5 and Below (API < 23)

**No runtime permissions needed** - All permissions auto-granted at install

---

## Foreground Service Requirements

### Android 14+ (API 34+)

**Cannot start foreground service from background:**

```kotlin
// ❌ WRONG - throws ForegroundServiceStartNotAllowedException
class SomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // This is "background"
        context.startForegroundService(Intent(context, BleService::class.java))  // CRASH!
    }
}

// ✅ CORRECT - start when app is in foreground
class MainActivity : Activity() {
    override fun onResume() {
        // This is "foreground"
        startForegroundService(Intent(this, BleService::class.java))  // OK!
    }
}
```

**Foreground Service Type:**
```xml
<service
    android:name=".service.BleService"
    android:foregroundServiceType="connectedDevice" />
```

**Justification:** BLE mesh connections are "connected devices"

### Android 9-13 (API 28-33)

**Foreground service permissions:**
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

**Start foreground immediately:**
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, createNotification())
    // Must call within 5 seconds of service start
    return START_STICKY
}
```

---

## BLE Hardware Limitations

### MTU Limits

**Spec:**
- Minimum: 23 bytes (BLE 4.0 spec)
- Maximum: 517 bytes (Android limit)
- Default: 23 bytes (until negotiated)

**Reality:**
- Most devices: 185-244 bytes after negotiation
- High-end devices: 517 bytes
- Cheap devices: 23 bytes (negotiation fails)

**Check negotiated MTU:**
```kotlin
override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
    if (status == GATT_SUCCESS) {
        log("Negotiated MTU: $mtu")
        // Usable MTU = mtu - 3 (ATT header)
    } else {
        log("MTU negotiation failed, using default (23)")
    }
}
```

### Connection Limits

**Android Limit:** ~8 simultaneous BLE connections **across all apps**

**Columba Limit:** 7 connections (leave 1 for system/other apps)

**Enforcement:**
```kotlin
if (connections.size >= MAX_CONNECTIONS) {
    // Drop lowest-priority connection OR reject new connection
    val lowestPriority = connections.values.minByOrNull { it.priorityScore }
    lowestPriority?.let { disconnect(it.address) }
}
```

**Per-Connection Limits:**
- Each connection can have 1 ongoing GATT operation
- Must use operation queue for serial execution

### Peripheral Mode Support

**Requirements:**
- Android 5.0+ (API 21+)
- Hardware support for multiple advertisements

**Check at runtime:**
```kotlin
val bluetoothAdapter = bluetoothManager.adapter

if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
    // Device doesn't support peripheral mode
    // Fallback: central mode only
    log("Peripheral mode not supported on this device")
}
```

**Common devices without support:**
- Some low-end Android devices
- Android emulators (usually)
- Devices with older Bluetooth chips

---

## Scanning Restrictions

### Android 8+ (API 26+) Background Scan Restrictions

**Rule:** Background scans MUST use filters, or they're blocked.

```kotlin
// ❌ WRONG - unfiltered scan blocked when screen off
val settings = ScanSettings.Builder().build()
scanner.startScan(null, settings, callback)  // Blocked in background!

// ✅ CORRECT - filtered scan works in background
val filter = ScanFilter.Builder()
    .setServiceUuid(ParcelUuid(RETICULUM_UUID))
    .build()
scanner.startScan(listOf(filter), settings, callback)  // Works!
```

**With Foreground Service:** Scans work even when screen off

### Scan Frequency Limits

**Android Limit:** 5 scans per 30 seconds per app

**If exceeded:**
- `onScanFailed(SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)`
- Scan requests ignored
- Must wait for cooldown period

**Solution:** Use adaptive intervals
```kotlin
// Columba's smart polling:
// - 5s intervals when discovering (safe: 6 scans per 30s ❌)
// - Actually: scan for 10s, pause 5s (2 scans per 30s ✅)
// - 30s intervals when idle (1 scan per 30s ✅)
```

---

## GATT Operation Rules

### Rule #1: Operations Must Be Serial

**Problem:** Android BLE stack doesn't queue operations

```kotlin
// ❌ WRONG - operations fail silently
gatt.readCharacteristic(char1)        // Starts
gatt.writeCharacteristic(char2, data)  // FAILS (operation already in progress)
gatt.readCharacteristic(char3)        // FAILS
```

**Solution:** Use operation queue
```kotlin
// ✅ CORRECT - operations execute serially
operationQueue.enqueue(ReadCharacteristic(gatt, char1))
operationQueue.enqueue(WriteCharacteristic(gatt, char2, data))  // Waits for char1
operationQueue.enqueue(ReadCharacteristic(gatt, char3))         // Waits for char2
```

### Rule #2: Service Discovery on Main Thread

**Problem:** `discoverServices()` can deadlock on background threads (some Android versions)

```kotlin
// ❌ RISKY - may deadlock
scope.launch(Dispatchers.IO) {
    gatt.discoverServices()  // Deadlock on some devices!
}

// ✅ CORRECT - post to main thread
Handler(Looper.getMainLooper()).post {
    gatt.discoverServices()
}
```

### Rule #3: Never Block Binder Thread

**GATT callbacks run on binder thread:**

```kotlin
// ❌ WRONG - blocks binder thread
override fun onCharacteristicChanged(gatt, char, value: ByteArray) {
    processData(value)  // Long operation = ANR!
}

// ✅ CORRECT - dispatch immediately
override fun onCharacteristicChanged(gatt, char, value: ByteArray) {
    scope.launch {  // Off binder thread in <1ms
        processData(value)
    }
}
```

### Rule #4: Close GATT on Disconnect

**Problem:** GATT connections leak if not closed

```kotlin
// ❌ WRONG - memory leak
override fun onConnectionStateChange(gatt, status, newState) {
    if (newState == STATE_DISCONNECTED) {
        gatt.disconnect()  // Not enough!
    }
}

// ✅ CORRECT - close and remove reference
override fun onConnectionStateChange(gatt, status, newState) {
    if (newState == STATE_DISCONNECTED) {
        gatt.close()  // Releases resources
        connections.remove(gatt.device.address)
    }
}
```

---

## API Level Compatibility

### Android 7.0 (API 24) - Columba Minimum

**Available:**
- ✅ BLE scanning with filters
- ✅ GATT client operations
- ✅ GATT server (peripheral mode)
- ✅ MTU negotiation
- ✅ Connection parameters

**Limitations:**
- ❌ No runtime Bluetooth permission (added in API 31)
- ❌ Must use legacy location permission

### Android 5.0 (API 21) - Peripheral Mode Minimum

**First version with:**
- GATT server API
- Multiple advertisement support
- Peripheral mode capabilities

**Before API 21:**
- No peripheral mode available
- Central mode only

### Android 12 (API 31) - New Permission Model

**Major change:** New Bluetooth permissions replace location

**Migration:**
```kotlin
// Old (API < 31)
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

// New (API >= 31)
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

---

## Battery Optimization

### Doze Mode (Android 6+)

**Problem:** Doze mode suspends network, BLE, and background services

**Solution:** Foreground service exempts from Doze

```kotlin
class BleService : Service() {
    override fun onStartCommand(...): Int {
        startForeground(NOTIFICATION_ID, notification)  // Exempt from Doze
        return START_STICKY
    }
}
```

### App Standby (Android 6+)

**Standby Buckets:**
- Active: No restrictions
- Working set: Mild restrictions
- Frequent: Moderate restrictions
- Rare: Heavy restrictions

**BLE Impact:**
- Active/Working: Normal operation
- Frequent: Scan delays possible
- Rare: Significant delays

**Solution:** Foreground service keeps app in Active bucket

### Battery Saver Mode

**User-enabled power saving:**
- Reduces Bluetooth radio power
- May reduce scan frequency
- Connections more fragile

**No code solution** - just be aware of reduced performance

---

## Bluetooth State Management

### Check Bluetooth is Enabled

```kotlin
val bluetoothAdapter = bluetoothManager.adapter

if (!bluetoothAdapter.isEnabled) {
    // Bluetooth is off
    // Option 1: Request user enable it
    val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    startActivityForResult(enableIntent, REQUEST_ENABLE_BT)

    // Option 2: Show error
    showError("Please enable Bluetooth")
}
```

### Listen for Bluetooth State Changes

```kotlin
class BluetoothStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_ON -> {
                // Bluetooth enabled - resume operations
            }
            BluetoothAdapter.STATE_OFF -> {
                // Bluetooth disabled - stop operations
            }
        }
    }
}

// Register in manifest or code
<receiver android:name=".BluetoothStateReceiver">
    <intent-filter>
        <action android:name="android.bluetooth.adapter.action.STATE_CHANGED" />
    </intent-filter>
</receiver>
```

---

## Manufacturer-Specific Quirks

### Samsung Devices

**Quirk:** More aggressive connection timeouts
**Solution:** Reduce connection timeout to 20s (vs 30s default)

**Quirk:** Sometimes requires `autoConnect = true` for stability
**Solution:** Try both `autoConnect` modes if Status 133 persists

### Pixel Devices

**Generally good BLE stack**, follows Android guidelines closely

### OnePlus/Xiaomi

**Quirk:** Aggressive battery optimization kills background BLE
**Solution:**
- Request "Don't optimize" in battery settings
- Foreground service (required anyway)

### Emulators

**Quirk:** Most emulators don't support BLE
**Solution:** Test on real devices only

---

## Thread Safety

### GATT Thread Rules

**Main Thread Required For:**
- `connectGatt()`
- `disconnect()`
- `discoverServices()` (critical!)
- `requestMtu()`
- `setCharacteristicNotification()`

**Any Thread OK For:**
- Callback processing (dispatch immediately)
- Data processing
- State updates

### Columba Threading Pattern

```kotlin
// Service scope (orchestration)
private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

// GATT operations (must be Main)
withContext(Dispatchers.Main) {
    gatt.discoverServices()
}

// Data processing (IO)
withContext(Dispatchers.IO) {
    reassembler.receiveFragment(fragment, sender)
}

// Callbacks (dispatch immediately)
override fun onCharacteristicChanged(...) {
    scope.launch {  // Dispatches based on scope dispatcher
        handleData(value)
    }
}
```

---

## Resource Management

### Connection Lifecycle

```kotlin
// Connect
val gatt = device.connectGatt(context, autoConnect, callback)

// Always close on disconnect
override fun onConnectionStateChange(gatt, status, newState) {
    if (newState == STATE_DISCONNECTED) {
        gatt.close()  // ⚠️ Critical - releases resources
        connections.remove(address)
    }
}
```

**Memory Leak Prevention:**
```kotlin
// Before destroying service/activity
suspend fun shutdown() {
    // Disconnect all
    connections.keys.forEach { disconnect(it) }

    // Clear all references
    connections.clear()
    fragmenters.clear()
    reassemblers.values.forEach { it.shutdown() }
    reassemblers.clear()

    // Cancel scope
    scope.cancel()
}
```

### Operation Queue Cleanup

```kotlin
// Clear pending operations
operationQueue.clearPendingOperations()

// Shutdown queue
operationQueue.shutdown()
```

---

## Performance Characteristics

### Expected Latencies

| Operation | Typical Latency | Notes |
|-----------|-----------------|-------|
| **Start scan** | 0-100ms | Immediate |
| **Device discovery** | 1-5s | Depends on advertising interval |
| **Connect** | 2-5s | Can be slower on first connect |
| **Service discovery** | 500ms-2s | Device dependent |
| **MTU negotiation** | 100-500ms | Optional |
| **Enable notifications** | 100-300ms | Descriptor write |
| **Write characteristic** | 10-50ms | Single write |
| **Notification delivery** | 10-50ms | From peer |

### Throughput

**Factors:**
- MTU size (larger = faster)
- Connection interval (Android negotiates)
- Radio quality
- Distance

**Typical:**
- MTU 23: ~10 kbps
- MTU 185: ~50 kbps
- MTU 517: ~100 kbps

**Maximum theoretical:** ~1 Mbps (BLE 4.0 spec), but Android achieves ~100-200 kbps in practice

---

## Known Android BLE Bugs

### Bug #1: Status 133 (GATT_ERROR)

**Frequency:** Very common (30-50% of first connection attempts)

**Cause:** Undocumented, but usually:
- Connection timeout
- Bluetooth stack busy
- Previous connection not fully cleaned up

**Solution:**
```kotlin
if (status == 133) {
    gatt.close()
    delay(1000 * retryAttempts)  // 1s, 2s, 3s...
    reconnect()
}
```

### Bug #2: Service Discovery Deadlock

**Affected:** Some Samsung devices, Android 7-8

**Cause:** `discoverServices()` from background thread

**Solution:** Always post to main thread (see Rule #2)

### Bug #3: Scan Results Delayed

**Affected:** All devices in Doze mode

**Cause:** Android batches scan results to save battery

**Solution:**
- Use foreground service
- Set `setReportDelay(0)` in ScanSettings

### Bug #4: Advertisement Limit

**Limit:** Typically 4 concurrent advertisements per device

**Error:** `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS`

**Solution:**
- Only advertise Reticulum service (don't start multiple)
- Close other BLE apps

---

## Security Considerations

### Pairing & Bonding

**Reticulum BLE:** Typically uses "Just Works" pairing
- No PIN or user interaction
- Unauthenticated encryption (BLE Security Mode 1 Level 2)
- Acceptable because Reticulum has its own cryptography

**Auto-Accept Pairing (if needed):**
```kotlin
// Listen for pairing requests
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothDevice.ACTION_PAIRING_REQUEST) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            device?.setPairingConfirmation(true)  // Auto-accept
        }
    }
}
```

### Privacy

**Android 8+ (API 26):** MAC address randomization for privacy

**Impact:** Device address may change periodically
- Affects device tracking
- Affects blacklist management

**Solution:** Use Reticulum identity for tracking (not MAC address)

---

## Compliance & App Store

### Google Play Requirements

**Bluetooth Permissions:**
- Must declare `<uses-feature android:name="android.hardware.bluetooth_le" />`
- Must show permission rationale before requesting
- Must handle permission denials gracefully

**Foreground Service:**
- Must show persistent notification
- Must explain why service is needed
- Notification cannot be dismissed by user

### Privacy Policy

**Required disclosures:**
- "App uses Bluetooth to discover and connect to nearby devices"
- "Bluetooth is used for mesh networking, not location tracking"
- "No location data is collected or transmitted"

---

## Best Practices Summary

✅ **DO:**
- Use BleOperationQueue for all GATT operations
- Request max MTU (517) immediately after connection
- Handle Status 133 with exponential backoff
- Post `discoverServices()` to main thread
- Dispatch callbacks to coroutines immediately
- Use foreground service for background operation
- Close GATT connections on disconnect
- Check permissions before all BLE operations
- Use scan filters in background

❌ **DON'T:**
- Call multiple GATT operations without queueing
- Block binder thread in callbacks
- Forget to close GATT connections
- Scan without filters in background
- Exceed scan frequency limits (5 per 30s)
- Start foreground service from background (Android 14+)
- Assume MTU without negotiation
- Assume peripheral mode is supported

---

## See Also

- `BLE_ARCHITECTURE_OVERVIEW.md` - Component architecture
- `TROUBLESHOOTING.md` - Debugging guide
- `../checklists/ble-service-setup-checklist.md` - Setup verification
- `../templates/gatt-operations.kt` - Code examples
