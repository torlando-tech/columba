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
    box rgb(30, 73, 102) Kotlin Native Layer
    participant Server as BleGattServer
    participant Bridge as KotlinBLEBridge
    end
    box rgb(55, 118, 71) Python Layer
    participant Python as AndroidBLEDriver
    end

    Central->>Server: connectGatt()
    Server->>Server: onConnectionStateChange(CONNECTED)
    Server->>Bridge: onCentralConnected(address, MIN_MTU)
    Note over Bridge: Tracks as pending connection<br/>(awaiting identity from central)

    Central->>Server: discoverServices()
    Central->>Server: Read Identity Characteristic
    Server-->>Central: Our 16-byte identity

    Central->>Server: requestMtu()
    Server->>Bridge: onMtuChanged(address, mtu)

    Central->>Server: Enable CCCD notifications

    rect rgb(147, 112, 219)
        Note over Central,Python: Identity Handshake (Protocol v2.2)
        Central->>Server: Write 16 bytes to RX
        Server->>Bridge: onDataReceived(address, data)
        Bridge->>Python: on_data_received(address, data)
        Python->>Python: _handle_identity_handshake()<br/>Detect: len=16, no existing identity
        Note over Python: Store identity mapping:<br/>address_to_identity[addr] = bytes<br/>identity_to_address[hash] = addr<br/>
        Note over Python: Create fragmenter/reassembler<br/>key = identity.hex() (32 chars)<br/>fragmenters[key] = BLEFragmenter(mtu)<br/>reassemblers[key] = BLEReassembler()
    end

    Python->>Python: _spawn_peer_interface<br/>(address, identity, mtu, role)
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
    Note over C: Store bidirectional mapping:<br/>address_to_identity[addr] = bytes<br/>identity_to_address[hash] = addr

    C->>P: Write 16 bytes to RX Characteristic
    Note over P: Detect identity handshake:<br/>exactly 16 bytes, no existing identity
    Note over P: Store bidirectional mapping:<br/>address_to_identity[addr] = bytes<br/>identity_to_address[hash] = addr

    Note over C,P: Both sides now have<br/>bidirectional identity ↔ address mapping
```

### Identity Tracking Data Structures

```mermaid
flowchart LR
    subgraph Python["Python (BLEInterface)"]
        P_A2I["address_to_identity<br/>MAC → 16 bytes"]
        P_I2A["identity_to_address<br/>16-char hex → MAC"]
        P_SI["spawned_interfaces<br/>16-char hex → BLEPeerInterface"]
        P_Cache["_identity_cache<br/>MAC → (16 bytes, timestamp)<br/>TTL: 60s"]
    end

    subgraph Kotlin["Kotlin (KotlinBLEBridge)"]
        K_A2I["addressToIdentity<br/>MAC → 16 bytes<br/>(central mode only)"]
        K_I2A["identityToAddress<br/>16-char hex → MAC<br/>(central mode only)"]
        K_Peers["connectedPeers<br/>MAC → PeerConnection"]
        K_Pending["pendingConnections<br/>MAC → PendingConnection"]
    end

    K_A2I -.->|"central mode: populates"| P_A2I
    K_I2A -.->|"central mode: populates"| P_I2A
```

> **Note on 16-char hex keys:** Maps like `identity_to_address` and `spawned_interfaces` use truncated 64-bit keys (16 hex chars) for shorter log output. Birthday collision risk is ~2³² (~4 billion) identities — astronomically safe for BLE mesh networks with <100 peers. Fragmenter/reassembler keys use full 32-char hex for maximum precision in packet reassembly.

**Identity detection by connection role:**
| Role | Who Detects | How |
|------|-------------|-----|
| Central (we connect to them) | Kotlin (GattClient) | Reads Identity Characteristic |
| Peripheral (they connect to us) | Python (BLEInterface) | Detects 16-byte write to RX |

### MAC Rotation Handling

**Problem:** Android devices rotate their BLE MAC address every ~15 minutes for privacy. This breaks naive address-based peer tracking — the same physical device appears as a "new" peer after rotation.

**Solution:** Use the 16-byte Reticulum identity (exchanged during handshake) as the stable peer identifier. When a new connection arrives with a known identity but different MAC, migrate all address-based mappings to the new MAC while preserving the peer interface and fragmenter state.

**Important:** Identity detection differs by connection role:
- **Central mode** (we connect to peer): Kotlin reads Identity Characteristic → `handleIdentityReceived`
- **Peripheral mode** (peer connects to us): Python detects 16-byte handshake → `_handle_identity_handshake`

**Central mode flow** (we connect to peer with new MAC):

```mermaid
flowchart TD
    A[We connect to MAC_NEW<br/>Read Identity Characteristic] --> B[Kotlin: handleIdentityReceived]
    B --> C{Kotlin: onDuplicateIdentityDetected?<br/>Calls Python _check_duplicate_identity<br/>Returns: bool}
    C -->|"Returns True<br/>(identity already at different MAC)"| D[Reject: disconnect MAC_NEW<br/>Log: Duplicate identity rejected]
    C -->|"Returns False or no callback"| E[Allow: continue processing]

    E --> F{Kotlin: existingAddress =<br/>identityToAddress‹hash›}

    F -->|"null<br/>(new identity)"| G[shouldUpdate = true]
    F -->|"MAC_OLD exists"| H{Is MAC_OLD actually connected?<br/>gattClient.isConnected‹MAC_OLD›}

    H -->|"No, or MAC_NEW has central"| G
    H -->|"Yes, and MAC_NEW lacks central"| I[shouldUpdate = false<br/>Keep MAC_OLD as primary]

    G --> J[Update identityToAddress‹hash› = MAC_NEW]
    J --> K{existingAddress ≠ null<br/>AND existingAddress ≠ MAC_NEW?}
    K -->|Yes| L[Kotlin: onAddressChanged‹MAC_OLD, MAC_NEW›]
    K -->|No| M[No address change notification]

    I --> N[Keep identityToAddress‹hash› = MAC_OLD]

    L --> O[Python: _address_changed_callback<br/>Migrate peer mappings]

    N --> P[Always: addressToIdentity‹MAC_NEW› = hash]
    M --> P
    O --> P
```

**Key code reference**: `KotlinBLEBridge.handleIdentityReceived()` lines 1997-2150

**Peripheral mode flow** (peer with new MAC connects to us):

```mermaid
flowchart TD
    A[MAC_NEW connects to us<br/>Writes 16-byte identity to RX] --> B{Python: _handle_identity_handshake<br/>Entry check: len=16 AND<br/>no address_to_identity‹MAC_NEW›}
    B -->|Check fails| Z[Return False: not a handshake<br/>Pass to normal data handler]
    B -->|Check passes| C{Python: _check_duplicate_identity<br/>Returns: bool}

    C -->|"Returns True<br/>(identity_to_address‹hash› = MAC_OLD<br/>AND MAC_OLD ≠ MAC_NEW)"| D[Reject: driver.disconnect‹MAC_NEW›<br/>Log: duplicate identity rejected<br/>Return True: handshake consumed]
    C -->|"Returns False<br/>(new identity OR same MAC)"| E[Allow: continue processing]

    E --> F[Store mappings unconditionally:<br/>address_to_identity‹MAC_NEW› = identity<br/>identity_to_address‹hash› = MAC_NEW]
    F --> G{spawned_interfaces‹hash› exists?}
    G -->|No| H[Create new BLEPeerInterface<br/>via _spawn_peer_interface]
    G -->|Yes| I{existing.peer_address ≠ MAC_NEW?}
    I -->|Yes| J[Update existing interface:<br/>peer_address = MAC_NEW<br/>address_to_interface‹MAC_NEW› = interface]
    I -->|No| K[No update needed<br/>Same address already set]
```

**Key code reference**: `BLEInterface._handle_identity_handshake()` lines 1108-1200

**On disconnect (MAC_OLD disconnects while MAC_NEW still connected):**
```mermaid
flowchart LR
    A[MAC_OLD disconnects] --> B{Identity still<br/>connected at MAC_NEW?}
    B -->|Yes| C[Cache: staleAddressToIdentity<br/>MAC_OLD → identity]
    B -->|No| D[Clean up all mappings<br/>for this identity]
```

**Key behaviors:**
- **Early rejection (both modes)**: `_check_duplicate_identity` rejects if identity already connected at different MAC — works in both central mode (via Kotlin callback) and peripheral mode (in `_handle_identity_handshake`)
- **Central preference**: Kotlin prefers mappings with live central connections (more reliable for sending)
- **Stale cache**: On disconnect, `staleAddressToIdentity` caches old address → identity, allowing `send()` to resolve old addresses during transition
- **Fragmenter/Reassembler unaffected**: Keyed by identity (32-char hex), not address — they continue working across MAC rotations without migration

---

## Deduplication State Machine

When the same identity is connected via both central and peripheral paths (dual connection):

```mermaid
stateDiagram-v2
    [*] --> NONE: Initial state

    NONE --> DualDetected: peer.isCentral && peer.isPeripheral

    DualDetected --> DecisionPoint: Compare identity hashes

    DecisionPoint --> CLOSING_CENTRAL: Keep peripheral<br/>(our identity > peer identity)
    DecisionPoint --> CLOSING_PERIPHERAL: Keep central<br/>(our identity < peer identity)

    CLOSING_CENTRAL --> NONE: Central disconnected
    CLOSING_PERIPHERAL --> NONE: Peripheral disconnected

    note right of DecisionPoint
        Decision based on identity hash comparison:
        - Lower identity hash = keeps central role
        - Higher identity hash = keeps peripheral role
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

    Note over Bridge: Dual connection detected<br/>peer.isCentral && peer.isPeripheral

    Bridge->>Bridge: Compare identity hashes<br/>localIdentityHex vs peerIdentity
    alt localIdentityHex < peerIdentity (we keep central)
        Bridge->>Bridge: Set state = CLOSING_PERIPHERAL
        Bridge->>Bridge: Set dedupeAction = CLOSE_PERIPHERAL
    else localIdentityHex > peerIdentity (we keep peripheral)
        Bridge->>Bridge: Set state = CLOSING_CENTRAL
        Bridge->>Bridge: Set dedupeAction = CLOSE_CENTRAL
    end

    Bridge->>Bridge: Add peerIdentity to<br/>recentlyDeduplicatedIdentities (cooldown)

    Note over Bridge: Execute disconnect outside mutex

    alt dedupeAction == CLOSE_CENTRAL
        Bridge->>Client: disconnect(address)
    else dedupeAction == CLOSE_PERIPHERAL
        Bridge->>Server: disconnectCentral(address)
    end

    Note over Bridge: On disconnect callback:<br/>state returns to NONE
```

**Key code reference**: `KotlinBLEBridge.handlePeerConnected()` lines 1701-1775

**Note**: Deduplication does NOT call `onAddressChanged`. Python is notified via the normal `onConnected` callback, and Python handles its own deduplication logic if needed. The `onAddressChanged` callback is only used for MAC rotation address migration in `handleIdentityReceived`.

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
