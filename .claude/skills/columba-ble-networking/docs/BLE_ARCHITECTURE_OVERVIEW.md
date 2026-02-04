# BLE Architecture Overview

Comprehensive guide to the BLE implementation architecture in Columba.

## Table of Contents
1. [GATT Protocol Basics](#gatt-protocol-basics)
2. [Central vs Peripheral Modes](#central-vs-peripheral-modes)
3. [Dual-Mode Operation](#dual-mode-operation)
4. [Component Architecture](#component-architecture)
5. [Data Flow](#data-flow)
6. [State Management](#state-management)

---

## GATT Protocol Basics

### What is GATT?

**GATT (Generic Attribute Profile)** is the protocol BLE devices use to exchange data.

**Hierarchy:**
```
BluetoothAdapter (Your Bluetooth hardware)
    ├── BluetoothLeScanner (Scans for devices)
    └── BluetoothDevice (Discovered device)
            └── BluetoothGatt (Connection to device)
                    └── BluetoothGattService (Group of characteristics)
                            └── BluetoothGattCharacteristic (Data endpoint)
                                    └── BluetoothGattDescriptor (Metadata)
```

### Reticulum GATT Service

**Service UUID:** `00000001-5824-4f48-9e1a-3b3e8f0c1234`

**Characteristics:**

**RX Characteristic** (`00000002-5824-4f48-9e1a-3b3e8f0c1234`):
- **Properties:** WRITE, WRITE_WITHOUT_RESPONSE
- **Permissions:** WRITE
- **Purpose:** Peer writes data here → we receive
- **Data flow:** Peer → Us

**TX Characteristic** (`00000003-5824-4f48-9e1a-3b3e8f0c1234`):
- **Properties:** READ, NOTIFY
- **Permissions:** READ
- **Purpose:** We notify peer here → they receive
- **Data flow:** Us → Peer
- **CCCD Descriptor:** `00002902-0000-1000-8000-00805f9b34fb` (enables notifications)

---

## Central vs Peripheral Modes

### Central Mode (GATT Client)

**Role:** Scanner and connector

**Capabilities:**
- Scan for BLE devices
- Initiate connections
- Read/write characteristics
- Subscribe to notifications

**In Columba:**
- Implemented by: `BleScanner` + `BleGattClient`
- Scans for Reticulum service UUID
- Connects to discovered peers
- Writes to their RX characteristic
- Receives notifications from their TX characteristic

### Peripheral Mode (GATT Server)

**Role:** Advertiser and acceptor

**Capabilities:**
- Advertise presence
- Accept incoming connections
- Expose characteristics for read/write
- Send notifications

**In Columba:**
- Implemented by: `BleAdvertiser` + `BleGattServer`
- Advertises Reticulum service UUID
- Accepts connections from centrals
- Receives writes on RX characteristic
- Sends notifications via TX characteristic

### Comparison

| Aspect | Central Mode | Peripheral Mode |
|--------|--------------|-----------------|
| **Initiative** | We connect to them | They connect to us |
| **Scanning** | We scan | We advertise |
| **Control** | High (we control connection) | Low (central controls) |
| **Power** | Higher (scanning is expensive) | Lower (advertising is cheap) |
| **Compatibility** | All Android devices | Android 5.0+ only |
| **Connection limit** | Limited by resources | Limited by hardware (~7) |

---

## Dual-Mode Operation

### Why Dual-Mode?

**Problem:** Mesh networking requires bidirectional peer discovery

**Solution:** Run both central and peripheral modes simultaneously

**Benefits:**
- **Symmetric discovery:** A discovers B, B discovers A
- **Redundancy:** Two connection paths per peer
- **Load balancing:** Distribute connection costs
- **Robustness:** If one mode fails, other still works

### Dual Connections Per Peer

Each peer can have **two simultaneous connections:**

1. **Central connection** - We connect to them (outgoing)
   - ConnectionID: `"AA:BB:CC:DD:EE:FF-central"`
   - We control disconnection
   - We initiate data transfer

2. **Peripheral connection** - They connect to us (incoming)
   - ConnectionID: `"AA:BB:CC:DD:EE:FF-peripheral"`
   - They control disconnection
   - They initiate data transfer

**Fragmentation State:** Shared between both connections
- One fragmenter per peer (keyed by address)
- One reassembler per peer (keyed by address)
- Both connections use same fragmenter/reassembler

### Implementation

```kotlin
// Start both modes
connectionManager.start(
    deviceName = "Reticulum-Node",
    enableCentral = true,      // Scan and connect
    enablePeripheral = true    // Advertise and accept
)

// Track connections
data class ConnectionInfo(
    val address: String,
    var hasCentralConnection: Boolean = false,     // We → them
    var hasPeripheralConnection: Boolean = false,  // Them → us
    var centralMtu: Int,
    var peripheralMtu: Int
)

val effectiveMtu = max(centralMtu, peripheralMtu)  // Use best MTU
```

**Routing Decision:**
```kotlin
suspend fun sendData(address: String, data: ByteArray) {
    val info = connections[address]

    when {
        info.hasCentralConnection -> sendViaCentral(address, data)  // Preferred
        info.hasPeripheralConnection -> sendViaPeripheral(address, data)  // Fallback
        else -> error("Not connected")
    }
}
```

---

## Component Architecture

### Layer 1: Foundation

**BleOperationQueue** (Critical!)
- Ensures serial GATT operation execution
- Prevents Android's silent operation failures
- Timeout handling (default: 5s per operation)
- Thread-safe with coroutines

**BleFragmenter/BleReassembler**
- MTU-aware packet splitting/joining
- 5-byte header protocol
- Per-peer state management
- Timeout-based cleanup

**BlePermissionManager**
- Android 12+ permission handling
- Version-aware permission detection
- Permission rationale text

### Layer 2: BLE Components

**BleScanner**
- Adaptive scan intervals (2s active → 30s idle)
- RSSI threshold filtering
- Device prioritization scoring
- StateFlow for reactive UI

**BleGattClient**
- Connect to devices (central mode)
- Service/characteristic discovery
- MTU negotiation
- Data read/write via operation queue

**BleGattServer**
- Create GATT server (peripheral mode)
- Handle characteristic requests
- Notify connected centrals
- Track per-central MTU

**BleAdvertiser**
- BLE advertising lifecycle
- Retry with exponential backoff
- Hardware capability checking

### Layer 3: Integration

**BleConnectionManager**
- Orchestrates all BLE components
- Connection pool management (max 7)
- Dual-mode coordination
- Fragmentation integration
- Blacklisting and retry logic
- Statistics tracking

### Layer 4: Service & UI

**BleService**
- Foreground service (persistent notification)
- AIDL interface implementation
- IPC with app process
- Lifecycle management

**BleTestScreen**
- Compose UI for testing
- Real-time device list
- Statistics dashboard
- Connection controls

---

## Data Flow

### Outgoing Packet Flow (Sending)

```
User/Reticulum
    ↓ sendData(address, packet)
BleConnectionManager.sendData()
    ↓ Get fragmenter for peer
BleFragmenter.fragmentPacket(packet)
    ↓ Returns: [fragment1, fragment2, ...]
    ↓ Each fragment has 5-byte header
Route to best connection
    ↓ Prefer central (we control it)
    ↓ Fallback to peripheral
BleGattClient.sendData(fragment) OR BleGattServer.notifyCentrals(fragment)
    ↓ For each fragment
BleOperationQueue.enqueue(WriteCharacteristic)
    ↓ Serial execution
BluetoothGatt.writeCharacteristic()
    ↓ Asynchronous Android call
[Wait for callback]
    ↓
BluetoothGattCallback.onCharacteristicWrite()
    ↓ status == GATT_SUCCESS?
BleOperationQueue.completeOperation()
    ↓ Next operation starts
Statistics.fragmentsSent++
```

### Incoming Packet Flow (Receiving)

```
BluetoothGattCallback.onCharacteristicChanged(characteristic, value)
  OR
BluetoothGattServerCallback.onCharacteristicWriteRequest(value)
    ↓ Fragment received (5-byte header + payload)
scope.launch { ... }  // Off binder thread immediately!
    ↓
BleConnectionManager.handleDataReceived(address, fragment)
    ↓ Get reassembler for peer
BleReassembler.receiveFragment(fragment, senderId)
    ↓ Parse header: [Type][Sequence][Total]
    ↓ Store in reassembly buffer
    ↓ Check if all fragments received
If complete:
    ↓ Reassemble in sequence order
    ↓ Return complete packet
    onDataReceived(address, completePacket)
        ↓ Statistics.packetsReceived++
        ↓ Forward to Reticulum/User
Else:
    ↓ Return null (waiting for more)
    ↓ Timeout job active (30s)
```

---

## State Management

### Connection States

```kotlin
sealed class BleConnectionState {
    object Idle                          // Not scanning or connected
    object Scanning                      // Actively scanning
    data class Connecting(address)       // Connection in progress
    data class Connected(                // Active connections
        peers: List<String>,
        centralConnections: Int,
        peripheralConnections: Int
    )
    data class Error(message, recoverable)
    object BluetoothDisabled
    data class PermissionDenied(missingPermissions)
}
```

### State Transitions

```
Idle
  ↓ startScanning()
Scanning
  ↓ connect(address)
Connecting(address)
  ↓ onConnected()
Connected(peers=[address], central=1, peripheral=0)
  ↓ Another device connects to us
Connected(peers=[address], central=1, peripheral=1)
  ↓ disconnect() or error
Scanning (if still scanning) OR Idle
```

### StateFlow Integration

```kotlin
// In BleConnectionManager
val connectionState: StateFlow<BleConnectionState>

// In UI (Compose)
val state by connectionManager.connectionState.collectAsState()

when (state) {
    is Connected -> Text("${state.peers.size} peers connected")
    is Scanning -> CircularProgressIndicator()
    is Error -> Text("Error: ${state.message}")
    else -> {}
}
```

---

## Threading Architecture

### Dispatcher Usage (Following columba-threading-redesign)

**Dispatchers.Main:**
- All BluetoothGatt operations
- All BluetoothGattServer operations
- Service discovery (Android quirk)
- Advertising start/stop

**Dispatchers.IO:**
- Data processing
- Fragmentation/reassembly
- File I/O (if logging)
- Network operations (if bridging to TCP)

**Dispatchers.Default:**
- Service orchestration
- State management
- JSON parsing/serialization
- Statistics calculation

**Example:**
```kotlin
// GATT operation - Main dispatcher
withContext(Dispatchers.Main) {
    gatt.discoverServices()
}

// Data processing - IO dispatcher
withContext(Dispatchers.IO) {
    val packet = reassembler.receiveFragment(fragment, sender)
}

// Orchestration - Default dispatcher
private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

### Callback Threading

**All GATT callbacks run on Main thread:**
```kotlin
private inner class GattCallback : BluetoothGattCallback() {
    override fun onConnectionStateChange(...) {
        // Running on: Dispatchers.Main
        scope.launch {  // Dispatch to appropriate thread
            handleConnectionStateChange(...)
        }
    }
}
```

**Rule:** Callbacks MUST return quickly (<1ms)
- Dispatch long operations to coroutines
- Use `scope.launch` for CPU/IO work
- Never block the callback

---

## AIDL IPC Architecture

### Service Process Isolation

**Columba runs multi-process:**
```
App Process (:app)
├── UI (MainActivity, BleTestScreen)
└── ServiceBinders (AIDL stubs)

BLE Process (:ble) - Optional, can share :reticulum
└── BleService (AIDL implementation)
    └── BleConnectionManager
        ├── BleScanner
        ├── BleGattClient
        ├── BleGattServer
        └── BleAdvertiser
```

**Benefits of Separate Process:**
- Clean restarts (kill process without affecting UI)
- Resource isolation
- Crash isolation

### AIDL Interface

**IBleService.aidl** (main interface):
- Control methods: `startScanning()`, `connect()`, `sendData()`
- Query methods: `getConnectionState()`, `getStatistics()`
- Lifecycle: `shutdown()`

**IBleServiceCallback.aidl** (events):
- Discovery: `onDeviceDiscovered(address, name, rssi)`
- Connection: `onConnected()`, `onDisconnected()`
- Data: `onDataReceived(address, data)`
- Errors: `onError(message, recoverable)`

**Usage Pattern:**
```kotlin
// In app process
val bleService: IBleService = // bind via ServiceConnection

bleService.registerCallback(object : IBleServiceCallback.Stub() {
    override fun onDeviceDiscovered(address: String, name: String?, rssi: Int) {
        // Update UI
    }
})

bleService.startScanning()
```

---

## Connection Management

### Connection Pool

**Limits:**
- **Max connections:** 7 (Android typically supports ~8 total across all apps)
- **Per-peer connections:** Up to 2 (one central, one peripheral)

**Tracking:**
```kotlin
data class ConnectionInfo(
    val address: String,
    var hasCentralConnection: Boolean,
    var hasPeripheralConnection: Boolean,
    var centralMtu: Int,
    var peripheralMtu: Int
)

val connections: ConcurrentHashMap<String, ConnectionInfo>
```

### Connection Prioritization

**Scoring Algorithm (from ble-reticulum):**
```
Score = RSSI (60%) + History (30%) + Recency (10%)

Where:
- RSSI: 0-70 points based on signal strength (-85 to 0 dBm)
- History: 0-50 points based on connection success rate
- Recency: 0-25 points based on last seen time
```

**Implementation:**
```kotlin
fun BleDevice.calculatePriorityScore(minRssi: Int = -85): Double {
    val rssiNormalized = (rssi - minRssi).toDouble() / (0 - minRssi)
    val rssiScore = rssiNormalized * 70

    val historyScore = getSuccessRate() * 50

    val ageSeconds = (currentTime - lastSeen) / 1000.0
    val recencyScore = max(0.0, 25 - (ageSeconds / 10))

    return rssiScore + historyScore + recencyScore
}
```

**Auto-Connection:**
- Scanner discovers devices
- Calculate priority scores
- Connect to highest-priority devices first
- Respect connection limit (7 max)
- Skip blacklisted devices

### Blacklisting & Retry Logic

**Exponential Backoff:**
```
Failure #1: No blacklist, retry immediately
Failure #2: No blacklist, retry immediately
Failure #3: No blacklist, retry immediately
Failure #4: Blacklist for 30 seconds
Failure #5: Blacklist for 60 seconds
Failure #6: Blacklist for 120 seconds
Failure #7: Blacklist for 240 seconds (max)
```

**Blacklist Expiration:**
- Blacklist cleared after timeout
- Blacklist cleared on successful connection from peer
- Manual clear via `clearBlacklist(address)`

---

## Reticulum Integration (Future)

### Bridge Architecture

**Python BLE Interface:**
```python
# python/ble_interface.py
from RNS.Interfaces import Interface

class BLEInterface(Interface):
    def __init__(self, owner, configuration):
        # Bridge to Kotlin via Chaquopy
        from com.lxmf.messenger.reticulum.ble.service import BleConnectionManager
        self.ble_manager = BleConnectionManager.getInstance(context)

    def processOutgoing(self, data):
        # Reticulum → BLE
        self.ble_manager.sendData(peer_address, bytes(data))

    def on_data_received(self, address, data):
        # BLE → Reticulum
        self.owner.inbound(bytes(data), self)
```

**Kotlin Bridge:**
```kotlin
// BlePythonBridge.kt
object BlePythonBridge {
    fun onDataReceived(address: String, data: ByteArray) {
        val py = Python.getInstance()
        val module = py.getModule("ble_interface")
        module.callAttr("on_data_received", address, data)
    }
}
```

### Spawned Peer Interfaces

**Pattern from ble-reticulum:**

Each connection spawns a `BLEPeerInterface` registered with Reticulum Transport:

```python
peer_if = BLEPeerInterface(parent=self, address=address, name=name)
peer_if.OUT = True
peer_if.IN = True
peer_if.bitrate = 700000  # ~700 Kbps
RNS.Transport.interfaces.append(peer_if)
```

**Benefits:**
- Reticulum sees each connection as separate interface
- Independent routing decisions per peer
- Statistics per interface
- Announce propagation control

---

## See Also

**Skill Documentation:**
- `../README.md` - Quick reference
- `FRAGMENTATION_PROTOCOL.md` - Packet splitting details
- `ANDROID_BLE_RULES.md` - Permission & API requirements
- `CONNECTION_MANAGEMENT.md` - State machine & pooling
- `TROUBLESHOOTING.md` - Common issues

**Patterns:**
- `../patterns/dual-mode-operation.md` - Detailed dual-mode guide
- `../patterns/fragmentation-reassembly.md` - Complete flow example

**External:**
- [Android BLE Guide](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)
- [Nordic BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library)
- [ble-reticulum](https://github.com/markqvist/ble-reticulum)
