# Columba BLE Architecture Documentation

This document describes the complete Bluetooth Low Energy (BLE) architecture for Reticulum networking on Android, covering all layers from Python protocol handling through native Android BLE operations.

## Architecture Overview

The BLE implementation follows a layered architecture with clear separation of concerns:

```mermaid
flowchart TB
    subgraph Python["Python Layer (ble-reticulum)"]
        BLEInterface["BLEInterface<br/>Protocol handler, fragmentation,<br/>peer lifecycle"]
        AndroidDriver["AndroidBLEDriver<br/>Chaquopy bridge to Kotlin"]
                BLEPeerInterface["BLEPeerInterface<br/>Per-peer Reticulum interface"]

    end

    subgraph Kotlin["Kotlin Native Layer"]
        OpQueue["BleOperationQueue<br/>Serialized GATT ops"]
        Bridge["KotlinBLEBridge<br/>Main entry point,<br/>PeerInfo tracking,<br/>deduplication"]
        Scanner["BleScanner<br/>Adaptive intervals,<br/>service filtering"]
        Advertiser["BleAdvertiser<br/>Identity naming,<br/>proactive refresh"]
        GattClient["BleGattClient<br/>Central mode,<br/>4-step handshake"]
        GattServer["BleGattServer<br/>Peripheral mode,<br/>GATT service"]
    end

    subgraph Android["Android BLE Stack"]
            BluetoothAdapter["BluetoothAdapter"]

        BluetoothLeScanner["BluetoothLeScanner"]
        BluetoothLeAdvertiser["BluetoothLeAdvertiser"]
        BluetoothGatt["BluetoothGatt"]
        BluetoothGattServer["BluetoothGattServer"]
        
    end

    BLEInterface --> AndroidDriver
    AndroidDriver -->|Chaquopy| Bridge
    Bridge --> OpQueue
    OpQueue --> BluetoothAdapter
    Scanner --> BluetoothLeScanner
    Advertiser --> BluetoothLeAdvertiser
    GattClient --> BluetoothGatt
    GattServer --> BluetoothGattServer
```

### Layer Responsibilities

| Layer | Component | Responsibility |
|-------|-----------|----------------|
| Python | `BLEInterface` | Reticulum interface, packet fragmentation/reassembly, peer lifecycle |
| Python | `BLEPeerInterface` | Per-peer Reticulum routing interface |
| Python | `AndroidBLEDriver` | Bridge to Kotlin, callback routing |
| Kotlin | `KotlinBLEBridge` | Entry point for Python, connection tracking, deduplication |
| Kotlin | `BleScanner` | Device discovery with adaptive intervals |
| Kotlin | `BleAdvertiser` | Peripheral advertising with identity |
| Kotlin | `BleGattClient` | Central mode GATT operations |
| Kotlin | `BleGattServer` | Peripheral mode GATT service |
| Kotlin | `BleOperationQueue` | Serialized GATT operations (Android limitation) |

---

## GATT Service Structure

The Reticulum BLE service follows Protocol v2.2 specification:

```mermaid
classDiagram
    class ReticulumService {
        UUID: 37145b00-442d-4a94-917f-8f42c5da28e3
        Type: PRIMARY
    }

    class RXCharacteristic {
        UUID: 37145b00-442d-4a94-917f-8f42c5da28e5
        Properties: WRITE, WRITE_NO_RESPONSE
        Permissions: WRITE
        Purpose: Centrals write data here
    }

    class TXCharacteristic {
        UUID: 37145b00-442d-4a94-917f-8f42c5da28e4
        Properties: READ, NOTIFY, INDICATE
        Permissions: READ
        Purpose: Peripherals notify data here
    }

    class IdentityCharacteristic {
        UUID: 37145b00-442d-4a94-917f-8f42c5da28e6
        Properties: READ
        Permissions: READ
        Purpose: 16-byte transport identity
    }

    class CCCDDescriptor {
        UUID: 00002902-0000-1000-8000-00805f9b34fb
        Purpose: Enable/disable notifications
    }

    ReticulumService --> RXCharacteristic
    ReticulumService --> TXCharacteristic
    ReticulumService --> IdentityCharacteristic
    TXCharacteristic --> CCCDDescriptor
```

### Characteristic Details

| Characteristic | UUID Suffix | Direction | Purpose |
|----------------|-------------|-----------|---------|
| RX | `...28e5` | Central → Peripheral | Data and identity handshake writes |
| TX | `...28e4` | Peripheral → Central | Notifications for outbound data |
| Identity | `...28e6` | Read-only | Provides 16-byte transport identity hash |

---

## Connection Flows

### Central Mode Connection Sequence

When this device discovers and connects to a peripheral:

```mermaid
sequenceDiagram
    box rgb(30, 73, 102) Kotlin Native Layer
    participant Scan as BleScanner
    participant Bridge as KotlinBLEBridge
    participant Client as BleGattClient
    end
    participant Peer as Remote Peripheral
    box rgb(55, 118, 71) Python Layer
    participant Python as AndroidBLEDriver
    end

    Scan->>Bridge: onDeviceDiscovered(address, rssi)
    Bridge->>Bridge: shouldConnect(address)?
    Note over Bridge: MAC comparison:<br/>our MAC < peer MAC = connect
    Bridge->>Client: connect(address)

    Note over Client,Peer: GATT Connection Setup
    Client->>Peer: connectGatt()
    Peer-->>Client: onConnectionStateChange(CONNECTED)
    Client->>Peer: discoverServices()
    Peer-->>Client: onServicesDiscovered()

    rect rgb(147, 112, 219)
        Note over Client,Peer: Identity Handshake (Protocol v2.2)
        Client->>Peer: Step 1: Read Identity Characteristic
        Peer-->>Client: 16-byte identity hash
        Client->>Bridge: onIdentityReceived(address, hash)

        Client->>Peer: Step 2: requestMtu(517)
        Peer-->>Client: onMtuChanged(negotiated_mtu)

        Client->>Peer: Step 3: Enable CCCD notifications
        Peer-->>Client: onDescriptorWrite(success)

        Client->>Peer: Step 4: Write our identity to RX
        Peer-->>Client: onCharacteristicWrite(success)
    end

    Client->>Bridge: onConnected(address, mtu)
    Note over Bridge: Retrieves stored identity<br/>from addressToIdentity map
    Bridge->>Python: onConnected(address, mtu, role, identity)
    Python->>Python: _spawn_peer_interface<br/>(address, identity, mtu, role)
```

### Peripheral Mode Connection Sequence

When a remote central connects to us:

```mermaid
sequenceDiagram
    participant Central as Remote Central
    participant Server as BleGattServer
    participant Bridge as KotlinBLEBridge
    participant Python as AndroidBLEDriver

    Central->>Server: connectGatt()
    Server->>Server: onConnectionStateChange(CONNECTED)
    Server->>Bridge: onCentralConnected(address, MIN_MTU)
    Note over Bridge: Track as pending connection<br/>(identity not yet received)

    Central->>Server: discoverServices()
    Central->>Server: Read Identity Characteristic
    Server-->>Central: Our 16-byte identity

    Central->>Server: requestMtu()
    Server->>Server: onMtuChanged()
    Server->>Bridge: onMtuChanged(address, mtu)

    Central->>Server: Enable CCCD notifications

    rect rgb(30, 73, 102)
        Note over Central,Server: Identity Handshake
        Central->>Server: Write 16 bytes to RX
        Server->>Server: Detect: len=16, no existing identity
        Server->>Bridge: onIdentityReceived(address, hash)
        Server->>Bridge: onDataReceived(address, identity_bytes)
    end

    Bridge->>Bridge: Complete connection with identity
    Bridge->>Python: onConnected(address, mtu, "peripheral", identity)
    Bridge->>Python: onIdentityReceived(address, hash)
    Python->>Python: _spawn_peer_interface(address, identity, mtu, role)
```

---

## Identity Protocol (v2.2)

### Purpose

Android randomizes MAC addresses for privacy. The identity protocol provides stable peer identification across MAC rotations.

### Handshake Sequence (Central → Peripheral)

```mermaid
sequenceDiagram
    participant C as Central
    participant P as Peripheral

    Note over C: Connect as GATT client
    C->>P: Read Identity Characteristic
    P-->>C: Peripheral's 16-byte identity
    Note over C: Store: address → identity

    C->>P: Write 16 bytes to RX Characteristic
    Note over P: Detect identity handshake:<br/>exactly 16 bytes, no existing identity
    Note over P: Store: address → identity

    Note over C,P: Both sides now have<br/>identity ↔ address mapping
```

### Identity Tracking Data Structures

```mermaid
flowchart LR
    subgraph Python["Python (BLEInterface)"]
        P_A2I["address_to_identity<br/>MAC → 16-byte identity"]
        P_I2A["identity_to_address<br/>hash → MAC"]
        P_SI["spawned_interfaces<br/>hash → BLEPeerInterface"]
        P_Cache["_identity_cache<br/>MAC → (identity, timestamp)<br/>TTL: 60s"]
    end

    subgraph Kotlin["Kotlin (KotlinBLEBridge)"]
        K_A2I["addressToIdentity<br/>MAC → 32-char hex"]
        K_I2A["identityToAddress<br/>hex → MAC"]
        K_Peers["connectedPeers<br/>MAC → PeerConnection"]
        K_Pending["pendingConnections<br/>MAC → PendingConnection"]
    end

    P_A2I -.->|sync| K_A2I
    P_I2A -.->|sync| K_I2A
```

### MAC Rotation Handling

When a peer reconnects with a new MAC address:

```mermaid
flowchart TD
    A[New connection from MAC_NEW] --> B{Identity received?}
    B -->|Yes| C[Compute identity_hash]
    C --> D{identity_hash in identityToAddress?}
    D -->|Yes, points to MAC_OLD| E[MAC Rotation Detected]
    E --> F{Is MAC_OLD still connected?}
    F -->|No| G[Clean up stale mappings]
    G --> H[Update: identity → MAC_NEW]
    F -->|Yes| I[Dual connection - deduplicate]
    D -->|No| J[New identity - normal flow]
    B -->|No, peripheral| K[Wait for handshake]
```

---

## Deduplication State Machine

When the same identity is connected via both central and peripheral paths:

```mermaid
stateDiagram-v2
    [*] --> NONE: Initial state

    NONE --> DualDetected: Same identity on both paths

    DualDetected --> DecisionPoint: Determine which to keep

    DecisionPoint --> CLOSING_CENTRAL: Keep peripheral<br/>(our MAC > peer MAC)
    DecisionPoint --> CLOSING_PERIPHERAL: Keep central<br/>(our MAC < peer MAC)

    CLOSING_CENTRAL --> NONE: Central disconnected
    CLOSING_PERIPHERAL --> NONE: Peripheral disconnected

    note right of DecisionPoint
        Decision based on MAC comparison:
        - Lower MAC = central role
        - Higher MAC = peripheral role
    end note
```

### DeduplicationState Enum

```kotlin
enum class DeduplicationState {
    NONE,              // Normal - use actual isCentral/isPeripheral
    CLOSING_CENTRAL,   // Keeping peripheral, central disconnect pending
    CLOSING_PERIPHERAL // Keeping central, peripheral disconnect pending
}
```

### Deduplication Flow

```mermaid
sequenceDiagram
    participant Bridge as KotlinBLEBridge
    participant Client as BleGattClient
    participant Server as BleGattServer
    participant Python as AndroidBLEDriver

    Note over Bridge: Dual connection detected<br/>Same identity on both paths

    Bridge->>Bridge: Compare MAC addresses
    alt Our MAC < Peer MAC (we should be central)
        Bridge->>Bridge: Set state = CLOSING_PERIPHERAL
        Bridge->>Server: disconnectCentral(address)
        Bridge->>Python: onAddressChanged(peripheral_addr, central_addr, identity)
    else Our MAC > Peer MAC (we should be peripheral)
        Bridge->>Bridge: Set state = CLOSING_CENTRAL
        Bridge->>Client: disconnect(address)
        Bridge->>Python: onAddressChanged(central_addr, peripheral_addr, identity)
    end

    Note over Python: Update address mappings<br/>Migrate fragmenter keys

    Bridge->>Bridge: Set state = NONE
```

---

## Data Flow

### Sending Data (Python → BLE)

```mermaid
flowchart TB
    subgraph Python["Python Layer"]
        A[BLEPeerInterface.process_outgoing] --> B[Get fragmenter by identity_key]
        B --> C[BLEFragmenter.fragment]
        C --> D["Fragments with header:<br/>type(1) + seq(2) + total(2)"]
        D --> E[AndroidBLEDriver.send]
    end

    subgraph Kotlin["Kotlin Layer"]
        E --> F[KotlinBLEBridge.sendAsync]
        F --> G{Check deduplicationState}
        G -->|NONE| H{isCentral?}
        G -->|CLOSING_*| I[Block send - in transition]
        H -->|Yes| J[GattClient.sendData]
        H -->|No| K[GattServer.notifyCentrals]
        J --> L[Write to RX characteristic]
        K --> M[Notify via TX characteristic]
    end

    L --> N[Remote peripheral receives]
    M --> O[Remote central receives]
```

### Receiving Data (BLE → Python)

```mermaid
flowchart TB
    subgraph BLE["BLE Stack"]
        A[Notification/Write received]
    end

    subgraph Kotlin["Kotlin Layer"]
        A --> B{Is central or peripheral?}
        B -->|Central| C[onCharacteristicChanged]
        B -->|Peripheral| D[onCharacteristicWriteRequest]
        C --> E[Bridge.handleDataReceived]
        D --> E
        E --> F{First 16 bytes, no identity?}
        F -->|Yes| G[Identity handshake - store]
        F -->|No| H[Forward to Python]
    end

    subgraph Python["Python Layer"]
        H --> I[AndroidBLEDriver._handle_data_received]
        I --> J{Check identity handshake}
        J -->|Yes, 16 bytes| K[_handle_identity_handshake]
        J -->|No| L[_handle_ble_data]
        L --> M[Get reassembler by identity_key]
        M --> N[BLEReassembler.add_fragment]
        N --> O{Complete packet?}
        O -->|Yes| P[BLEPeerInterface.process_incoming]
        O -->|No| Q[Wait for more fragments]
    end
```

---

## Keepalive Mechanism

Android BLE connections timeout after 20-30 seconds of inactivity. Both layers implement keepalives:

```mermaid
sequenceDiagram
    participant Client as BleGattClient
    participant Timer as Keepalive Timer<br/>(15s interval)
    participant Peer as Remote Peripheral

    Note over Client: Connection established
    Client->>Timer: Start keepalive job

    loop Every 15 seconds
        Timer->>Client: Send keepalive
        Client->>Peer: Write 0x00 to RX
        alt Success
            Peer-->>Client: Write confirmed
            Client->>Timer: Reset failure counter
        else Failure
            Client->>Timer: Increment failures
            alt failures >= 3
                Timer->>Client: Connection dead
                Client->>Client: disconnect()
            end
        end
    end
```

### Keepalive Configuration

| Parameter | Value | Source |
|-----------|-------|--------|
| Interval | 15 seconds | `BleConstants.CONNECTION_KEEPALIVE_INTERVAL_MS` |
| Max failures | 3 | `BleConstants.MAX_CONNECTION_FAILURES` |
| Packet | `0x00` (1 byte) | Minimal overhead |

Both `BleGattClient` (central) and `BleGattServer` (peripheral) maintain independent keepalive mechanisms.

---

## Scanning and Advertising

### Adaptive Scanning

```mermaid
stateDiagram-v2
    [*] --> Active: Start scanning

    Active --> Active: New device discovered
    Active --> Idle: 3 scans without new devices

    Idle --> Active: New device discovered
    Idle --> Idle: No new devices

    note right of Active
        Interval: 5s
        Mode: BALANCED or LOW_LATENCY
    end note

    note right of Idle
        Interval: 30s
        Mode: LOW_POWER
    end note
```

### Scan Configuration

| Parameter | Active | Idle |
|-----------|--------|------|
| Interval | 5 seconds | 30 seconds |
| Duration | 10 seconds | 10 seconds |
| Mode | `SCAN_MODE_BALANCED` | `SCAN_MODE_LOW_POWER` |
| Threshold | 3 devices | 3 empty scans |

### Advertising with Proactive Refresh

```mermaid
sequenceDiagram
    participant Adv as BleAdvertiser
    participant Timer as Refresh Timer<br/>(60s interval)
    participant Android as Android BLE

    Adv->>Android: startAdvertising()
    Android-->>Adv: onStartSuccess()
    Adv->>Timer: Start refresh job

    loop Every 60 seconds
        Timer->>Adv: Proactive refresh
        Adv->>Android: stopAdvertising()
        Adv->>Android: startAdvertising()
        Note over Adv: Ensures advertising persists<br/>after screen off/background
    end
```

### Advertisement Data Structure

```
Advertising Data (31 bytes max):
├── Flags (3 bytes)
└── Service UUID (19 bytes for 128-bit UUID)

Scan Response (31 bytes separate budget):
└── Device Name: "RNS-{truncated_identity_hex}"
```

---

## Address/Identity Mapping Summary

### Python Layer (`BLEInterface`)

| Dictionary | Key | Value | Purpose |
|------------|-----|-------|---------|
| `address_to_identity` | MAC address | 16-byte identity | MAC → identity lookup |
| `identity_to_address` | 16-char hash | MAC address | Identity → current MAC |
| `spawned_interfaces` | 16-char hash | BLEPeerInterface | Identity → interface |
| `address_to_interface` | MAC address | BLEPeerInterface | Fallback cleanup |
| `_identity_cache` | MAC address | (identity, timestamp) | Reconnection cache (60s TTL) |
| `_pending_identity_connections` | MAC address | timestamp | Timeout tracking |
| `_pending_detach` | 16-char hash | timestamp | Grace period detach |
| `pending_mtu` | MAC address | MTU value | MTU/identity race handling |
| `fragmenters` | identity_key | BLEFragmenter | Per-identity fragmentation |
| `reassemblers` | identity_key | BLEReassembler | Per-identity reassembly |

### Kotlin Layer (`KotlinBLEBridge`)

| Map | Key | Value | Purpose |
|-----|-----|-------|---------|
| `addressToIdentity` | MAC address | 32-char hex | MAC → identity |
| `identityToAddress` | 32-char hex | MAC address | Identity → MAC |
| `connectedPeers` | MAC address | PeerConnection | Active connections |
| `pendingConnections` | MAC address | PendingConnection | Awaiting identity |
| `pendingCentralConnections` | Set<MAC> | - | In-progress central connects |
| `recentlyDeduplicatedIdentities` | 32-char hex | timestamp | Dedup cooldown (60s) |
| `processedIdentityCallbacks` | Set<key> | - | Prevent duplicate notifications |
| `staleAddressToIdentity` | MAC address | 32-char hex | Cache disconnected addresses for send() resolution |

---

## Potential Issues & Recommendations

### 1. GATT Operation Timeout (5s default)

**Issue**: The default 5-second timeout in `BleOperationQueue` may be too short for slow or congested BLE environments.

**Impact**: GATT operations may fail prematurely on:
- Older devices with slower BLE stacks
- Environments with high 2.4GHz interference
- During rapid connection/disconnection cycles

**Recommendation**: Consider adaptive timeouts based on operation type and historical success rates.

### 2. Advertising Refresh Interval (60s)

**Issue**: The 60-second advertising refresh may miss discovery windows.

**Impact**: If Android silently stops advertising immediately after screen-off, devices may be undiscoverable for up to 60 seconds.

**Recommendation**:
- Reduce to 30 seconds when battery is not a concern
- Add `BroadcastReceiver` for `ACTION_SCREEN_OFF` to trigger immediate refresh

### 3. Identity Cache Coherence

**Issue**: The 60-second identity cache in Python may become stale if not properly synchronized with Kotlin state.

**Impact**: Race conditions during rapid reconnection cycles could cause identity mismatches.

**Recommendation**: Add explicit cache invalidation when Kotlin detects MAC rotation or deduplication.

### 4. Fragmenter Key Complexity

**Issue**: Fragmenter keys use `_get_fragmenter_key(identity, address)` but the address parameter is unused.

**Current code**:
```python
def _get_fragmenter_key(self, peer_identity, address):
    # Address unused - key is identity-based for MAC rotation immunity
    return self._compute_identity_hash(peer_identity)
```

**Recommendation**: Remove unused `address` parameter to avoid confusion.

### 5. Double Identity Callback Processing

**Issue**: Both Kotlin (`onIdentityReceived`) and Python (`_handle_identity_handshake`) detect and process identity handshakes.

**Impact**: Additional complexity and potential for desynchronization.

**Recommendation**: Single point of identity detection (Kotlin) with Python purely as a consumer.

### 6. Grace Period Timing

**Issue**: The 2-second detach grace period (`_pending_detach_grace_period`) is hardcoded.

**Impact**: May not be sufficient for slow network conditions or concurrent reconnection attempts.

**Recommendation**: Make configurable via interface parameters, with a suggested default of 3-5 seconds.

---

## Key Constants Reference

### UUIDs (BleConstants.kt)

| Constant | Value |
|----------|-------|
| `SERVICE_UUID` | `37145b00-442d-4a94-917f-8f42c5da28e3` |
| `CHARACTERISTIC_RX_UUID` | `37145b00-442d-4a94-917f-8f42c5da28e5` |
| `CHARACTERISTIC_TX_UUID` | `37145b00-442d-4a94-917f-8f42c5da28e4` |
| `CHARACTERISTIC_IDENTITY_UUID` | `37145b00-442d-4a94-917f-8f42c5da28e6` |
| `CCCD_UUID` | `00002902-0000-1000-8000-00805f9b34fb` |

### Timing Constants

| Constant | Value | Location |
|----------|-------|----------|
| `CONNECTION_TIMEOUT_MS` | 30,000 ms | BleConstants |
| `CONNECTION_KEEPALIVE_INTERVAL_MS` | 15,000 ms | BleConstants |
| `DISCOVERY_INTERVAL_MS` | 5,000 ms | BleConstants |
| `DISCOVERY_INTERVAL_IDLE_MS` | 30,000 ms | BleConstants |
| `SCAN_DURATION_MS` | 10,000 ms | BleConstants |
| `ADVERTISING_REFRESH_INTERVAL_MS` | 60,000 ms | BleAdvertiser |
| `_identity_cache_ttl` | 60 s | BLEInterface |
| `_pending_detach_grace_period` | 2.0 s | BLEInterface |
| `deduplicationCooldownMs` | 60,000 ms | KotlinBLEBridge |

### MTU Constants

| Constant | Value | Meaning |
|----------|-------|---------|
| `MIN_MTU` | 23 | BLE 4.0 minimum |
| `DEFAULT_MTU` | 185 | Reasonable default |
| `MAX_MTU` | 517 | BLE 5.0 maximum |
| `HW_MTU` | 500 | Reticulum standard |

---

## File Locations

| Component | Path |
|-----------|------|
| BLEInterface.py | `app/build/python/pip/release/common/ble_reticulum/BLEInterface.py` |
| AndroidBLEDriver | `python/ble_modules/android_ble_driver.py` |
| KotlinBLEBridge | `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/bridge/KotlinBLEBridge.kt` |
| BleGattClient | `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/client/BleGattClient.kt` |
| BleGattServer | `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/server/BleGattServer.kt` |
| BleScanner | `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/client/BleScanner.kt` |
| BleAdvertiser | `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/server/BleAdvertiser.kt` |
| BleOperationQueue | `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/util/BleOperationQueue.kt` |
| BleConstants | `reticulum/src/main/java/com/lxmf/messenger/reticulum/ble/model/BleConstants.kt` |
