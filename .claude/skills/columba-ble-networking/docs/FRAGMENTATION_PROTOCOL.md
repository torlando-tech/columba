# BLE Fragmentation Protocol

Complete specification for packet fragmentation and reassembly in Columba BLE implementation.

## Overview

**Problem:** Reticulum packets can be up to 500 bytes, but BLE MTU is typically 20-512 bytes.

**Solution:** Fragment large packets with sequence headers for reassembly.

**Protocol:** 5-byte header format matching [ble-reticulum](https://github.com/markqvist/ble-reticulum) for compatibility.

---

## Fragment Header Format

### Structure (5 bytes)

```
┌──────┬────────────┬──────────────┬─────────────┐
│ Type │  Sequence  │    Total     │   Payload   │
│ 1 B  │  2 B (BE)  │  2 B (BE)    │  Variable   │
└──────┴────────────┴──────────────┴─────────────┘

Type:     Fragment type (START/CONTINUE/END)
Sequence: Fragment number (0-indexed, big-endian)
Total:    Total number of fragments (big-endian)
Payload:  Fragment data (MTU - 5 bytes)

BE = Big-endian byte order
```

### Fragment Types

| Type | Value | Meaning | When Used |
|------|-------|---------|-----------|
| **START** | `0x01` | First fragment | Sequence = 0 |
| **CONTINUE** | `0x02` | Middle fragment | Sequence > 0 and < (Total-1) |
| **END** | `0x03` | Last fragment | Sequence = Total - 1 |

### Example

**Packet:** 400 bytes, MTU: 185 bytes

**Fragmentation:**
- Payload per fragment: 185 - 5 = 180 bytes
- Fragments needed: ceil(400 / 180) = 3 fragments

```
Fragment 0 (185 bytes total):
[0x01][0x00 0x00][0x00 0x03][...180 bytes payload...]
 START  Seq=0      Total=3

Fragment 1 (185 bytes total):
[0x02][0x00 0x01][0x00 0x03][...180 bytes payload...]
 CONT   Seq=1      Total=3

Fragment 2 (145 bytes total):
[0x03][0x00 0x02][0x00 0x03][...40 bytes payload...]
 END    Seq=2      Total=3
```

**Reassembly:** Combine payloads in sequence order → 400 bytes original packet

---

## BleFragmenter Implementation

### Initialization

```kotlin
val fragmenter = BleFragmenter(mtu = 185)

// MTU can be updated after negotiation
fragmenter.updateMtu(newMtu = 517)  // Max MTU after negotiation
```

### Fragmentation

```kotlin
val packet = ByteArray(400) // Large packet
val fragments = fragmenter.fragmentPacket(packet)

// Returns: List<ByteArray>
// fragments[0]: [0x01][0x00 0x00][0x00 0x03][payload...]
// fragments[1]: [0x02][0x00 0x01][0x00 0x03][payload...]
// fragments[2]: [0x03][0x00 0x02][0x00 0x03][payload...]
```

### MTU Awareness

```kotlin
val fragmenter = BleFragmenter(mtu = 185)

// Payload capacity
val payloadSize = fragmenter.payloadSize  // 180 bytes (185 - 5)

// Efficiency calculation
val packet = ByteArray(400)
val efficiency = fragmenter.calculateEfficiency(packet.size)
// efficiency ≈ 0.974 (400 / (400 + 15 overhead))

// Overhead calculation
val overhead = fragmenter.calculateOverhead(packet.size)
// overhead = 15 bytes (3 fragments × 5 bytes/fragment)
```

### MTU Update

**Important:** Update fragmenter when MTU changes!

```kotlin
// In onMtuChanged callback
override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
        val usableMtu = mtu - 3  // Subtract ATT header (3 bytes)
        fragmenters[address]?.updateMtu(usableMtu)

        log("Updated fragmenter MTU: $usableMtu")
        log("  New payload size: ${usableMtu - 5}")
        log("  Efficiency improved: ${calculateEfficiency(500)}")
    }
}
```

---

## BleReassembler Implementation

### Initialization

```kotlin
val reassembler = BleReassembler(
    timeoutMs = 30000,  // 30 seconds
    scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
)
```

### Reassembly

```kotlin
// Fragments arrive (possibly out of order)
val fragment1 = fragmentSource.receive()  // Sequence 1 arrives first!
val packet1 = reassembler.receiveFragment(fragment1, senderId = "AA:BB:CC:DD:EE:FF")
// Returns: null (waiting for fragment 0)

val fragment0 = fragmentSource.receive()  // Sequence 0 arrives
val packet2 = reassembler.receiveFragment(fragment0, senderId = "AA:BB:CC:DD:EE:FF")
// Returns: null (waiting for fragment 2)

val fragment2 = fragmentSource.receive()  // Sequence 2 arrives
val packet3 = reassembler.receiveFragment(fragment2, senderId = "AA:BB:CC:DD:EE:FF")
// Returns: ByteArray(400) - COMPLETE PACKET!
```

### Out-of-Order Handling

**Reassembler handles any order:**
- Fragments stored in map keyed by sequence number
- Reassembly triggers when all sequences present
- No requirement for in-order reception

### Timeout Management

**Per-sender timeout jobs:**
```kotlin
// Fragment 0 arrives at T=0
reassembler.receiveFragment(fragment0, sender1)
// Timeout job starts: expires at T=30s

// Fragment 1 arrives at T=5s
reassembler.receiveFragment(fragment1, sender1)
// Same timeout job (per sender)

// If fragment 2 doesn't arrive by T=30s:
// → Timeout fires
// → Incomplete packet discarded
// → Statistics.packetsTimedOut++
```

**Cleanup:**
```kotlin
// When peer disconnects
reassembler.clearSender("AA:BB:CC:DD:EE:FF")

// When shutting down
reassembler.clearAll()
reassembler.shutdown()
```

### Duplicate Detection

```kotlin
// Fragment 1 arrives twice (retransmission)
val packet1 = reassembler.receiveFragment(fragment1, sender)  // Stored
val packet2 = reassembler.receiveFragment(fragment1, sender)  // Ignored (duplicate)

// Statistics
val stats = reassembler.getStatistics()
stats.fragmentsDuplicate++  // Incremented
```

---

## MTU Optimization

### MTU Negotiation

**Request maximum MTU:**
```kotlin
// After connection, request 517 bytes (max supported by Android)
operationQueue.enqueue(
    RequestMtu(gatt, mtu = 517)
)

// Wait for callback
override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
    if (status == GATT_SUCCESS) {
        // Negotiated MTU (typically 185-517)
        // Usable MTU = mtu - 3 (ATT header)
        val usableMtu = mtu - 3
        updateFragmenter(usableMtu)
    }
}
```

**Fallback Strategy:**
```kotlin
// If MTU negotiation fails or times out
val mtu = when {
    negotiationSucceeded -> negotiatedMtu - 3
    negotiationFailed -> DEFAULT_MTU - 3  // 185 - 3 = 182
    else -> MIN_MTU - 3  // 23 - 3 = 20 (BLE spec minimum)
}
```

### Fragmentation Efficiency

**Single-Fragment Optimization:**
```kotlin
// Packet: 150 bytes, MTU: 185
val fragments = fragmenter.fragmentPacket(packet)
// fragments.size == 1
// fragments[0] = [0x01][0x00 0x00][0x00 0x01][150 bytes]
//                 START  Seq=0      Total=1

// Efficiency: 150 / (150 + 5) = 96.8%
```

**Multi-Fragment:**
```kotlin
// Packet: 500 bytes, MTU: 185
val fragments = fragmenter.fragmentPacket(packet)
// fragments.size == 3
// Fragment 0: 180 bytes payload
// Fragment 1: 180 bytes payload
// Fragment 2: 140 bytes payload

// Overhead: 3 fragments × 5 bytes = 15 bytes
// Efficiency: 500 / (500 + 15) = 97.1%
```

**Optimal MTU:**
```
MTU 23 (min):   Payload: 18 bytes,  Efficiency: ~78% (for 500-byte packet)
MTU 185 (typical): Payload: 180 bytes, Efficiency: ~97%
MTU 517 (max):  Payload: 512 bytes, Efficiency: ~99%

Conclusion: Request max MTU (517) for best efficiency!
```

---

## Error Handling

### Fragmentation Errors

**Empty Packet:**
```kotlin
try {
    fragmenter.fragmentPacket(ByteArray(0))
} catch (e: IllegalArgumentException) {
    // "Cannot fragment empty packet"
}
```

**Invalid MTU:**
```kotlin
try {
    fragmenter.updateMtu(20)  // Below minimum (23)
} catch (e: IllegalArgumentException) {
    // "MTU must be at least 23 bytes"
}
```

### Reassembly Errors

**Fragment Too Small:**
```kotlin
try {
    reassembler.receiveFragment(ByteArray(3), sender)  // < 5 bytes
} catch (e: IllegalArgumentException) {
    // "Fragment too small: 3 bytes (minimum 5)"
}
```

**Invalid Header:**
```kotlin
try {
    // Fragment with invalid type (0x99)
    val badFragment = byteArrayOf(0x99, 0x00, 0x00, 0x00, 0x01)
    reassembler.receiveFragment(badFragment, sender)
} catch (e: IllegalArgumentException) {
    // "Invalid fragment type: 153"
}
```

**Sequence Out of Range:**
```kotlin
// Fragment claims sequence 5 but total is 3
// [0x02][0x00 0x05][0x00 0x03][payload]
try {
    reassembler.receiveFragment(badFragment, sender)
} catch (e: IllegalArgumentException) {
    // "Sequence 5 >= total 3"
}
```

**Missing START Fragment:**
```kotlin
// Receive CONTINUE without START
try {
    reassembler.receiveFragment(continueFragment, sender)
} catch (e: IllegalStateException) {
    // "Received CONTINUE fragment without START for sender X"
}
```

---

## Performance Optimization

### Minimize Fragmentation

**Strategy 1: Negotiate Max MTU**
```kotlin
// Always request 517 bytes
operationQueue.enqueue(RequestMtu(gatt, mtu = 517))

// 500-byte packet:
// - MTU 23:  28 fragments (19% overhead)
// - MTU 185: 3 fragments  (3% overhead)
// - MTU 517: 1 fragment   (1% overhead)
```

**Strategy 2: Batch Small Packets**
```kotlin
// Instead of sending 10 × 50-byte packets (50 fragments)
// Batch into 1 × 500-byte packet (1 fragment at MTU 517)

val batch = packets.reduce { acc, packet -> acc + packet }
connectionManager.sendData(address, batch)
```

### Minimize Reassembly Memory

**Strategy: Aggressive Timeout**
```kotlin
// Default: 30 seconds (conservative)
val reassembler = BleReassembler(timeoutMs = 30000)

// For high-throughput: Reduce timeout
val reassembler = BleReassembler(timeoutMs = 10000)  // 10 seconds

// Tradeoff:
// - Faster cleanup = less memory
// - But: More packet loss if transmission is slow
```

**Strategy: Per-Peer Cleanup**
```kotlin
// When peer disconnects
reassembler.clearSender(peerAddress)

// Immediately frees memory for that peer
// Prevents memory leaks from disconnected peers
```

---

## Testing Fragmentation

### Unit Tests

**Test Fragment Header:**
```kotlin
@Test
fun `fragment header format is correct`() {
    val fragmenter = BleFragmenter(mtu = 185)
    val packet = ByteArray(400)
    val fragments = fragmenter.fragmentPacket(packet)

    // First fragment
    assertEquals(0x01, fragments[0][0])  // Type: START
    assertEquals(0, fragments[0][1].toInt() shl 8 or fragments[0][2].toInt())  // Seq: 0
    assertEquals(3, fragments[0][3].toInt() shl 8 or fragments[0][4].toInt())  // Total: 3
}
```

**Test Reassembly:**
```kotlin
@Test
fun `reassembly handles out-of-order fragments`() = runTest {
    val reassembler = BleReassembler()
    val fragments = createTestFragments(size = 400, mtu = 185)

    // Send out of order: 1, 0, 2
    val result1 = reassembler.receiveFragment(fragments[1], "peer")
    assertNull(result1)  // Incomplete

    val result2 = reassembler.receiveFragment(fragments[0], "peer")
    assertNull(result2)  // Still incomplete

    val result3 = reassembler.receiveFragment(fragments[2], "peer")
    assertNotNull(result3)  // Complete!
    assertEquals(400, result3.size)
}
```

### Integration Testing

**Test with Real BLE:**
```kotlin
@Test
fun `send large packet over BLE`() = runTest {
    val packet = ByteArray(500) { it.toByte() }

    // Send
    connectionManager.sendData(peerAddress, packet)

    // Verify fragments sent
    verify(gattClient, times(1)).sendData(eq(peerAddress), any())

    // Verify received packet matches
    val received = awaitDataReceived()
    assertArrayEquals(packet, received)
}
```

**Test MTU Variations:**
```kotlin
@Test
fun `fragmentation works with different MTUs`() {
    val mtus = listOf(23, 50, 100, 185, 244, 517)
    val packet = ByteArray(500)

    mtus.forEach { mtu ->
        val fragmenter = BleFragmenter(mtu)
        val fragments = fragmenter.fragmentPacket(packet)

        // Verify no fragment exceeds MTU
        fragments.forEach { fragment ->
            assertTrue(fragment.size <= mtu)
        }

        // Verify reassembly
        val reassembler = BleReassembler()
        var result: ByteArray? = null
        fragments.forEach { fragment ->
            result = reassembler.receiveFragment(fragment, "test")
        }

        assertArrayEquals(packet, result)
    }
}
```

---

## Debugging Fragmentation

### Enable Verbose Logging

```kotlin
// In BleFragmenter
Log.v(TAG, "Fragmenting ${packet.size} bytes into $numFragments fragments (MTU: $mtu)")
fragments.forEachIndexed { index, fragment ->
    Log.v(TAG, "  Fragment $index: ${fragment.size} bytes, type: ${fragment[0]}")
}

// In BleReassembler
Log.v(TAG, "Received fragment seq=$sequence/$total from $senderId (${payload.size} bytes)")
Log.d(TAG, "Reassembly progress: ${buffer.fragments.size}/$total fragments for $senderId")
```

### Check Fragment Integrity

```kotlin
fun validateFragment(fragment: ByteArray): Boolean {
    if (fragment.size < 5) {
        log("Fragment too small: ${fragment.size}")
        return false
    }

    val type = fragment[0]
    if (type !in listOf(0x01, 0x02, 0x03)) {
        log("Invalid type: $type")
        return false
    }

    val sequence = (fragment[1].toInt() and 0xFF) shl 8 or (fragment[2].toInt() and 0xFF)
    val total = (fragment[3].toInt() and 0xFF) shl 8 or (fragment[4].toInt() and 0xFF)

    if (sequence >= total) {
        log("Sequence $sequence >= total $total")
        return false
    }

    return true
}
```

### Monitor Reassembly Buffers

```kotlin
// Check for memory leaks
val stats = reassembler.getStatistics()
log("In-progress packets: ${stats.packetsInProgress}")
log("Timed out packets: ${stats.packetsTimedOut}")

// Too many in-progress? Possible issues:
// - Peers disconnecting mid-transmission
// - Network packet loss
// - Timeout too long
```

---

## Efficiency Analysis

### Overhead Comparison

| Packet Size | MTU 23 | MTU 185 | MTU 517 |
|-------------|--------|---------|---------|
| **50 bytes** | 15 bytes (30%) | 5 bytes (10%) | 5 bytes (10%) |
| **100 bytes** | 25 bytes (25%) | 5 bytes (5%) | 5 bytes (5%) |
| **200 bytes** | 45 bytes (22.5%) | 10 bytes (5%) | 5 bytes (2.5%) |
| **500 bytes** | 105 bytes (21%) | 15 bytes (3%) | 5 bytes (1%) |

**Conclusion:** Larger MTU = better efficiency!

### Fragment Count Comparison

| Packet Size | MTU 23 | MTU 185 | MTU 517 |
|-------------|--------|---------|---------|
| **50 bytes** | 3 fragments | 1 fragment | 1 fragment |
| **100 bytes** | 6 fragments | 1 fragment | 1 fragment |
| **200 bytes** | 12 fragments | 2 fragments | 1 fragment |
| **500 bytes** | 28 fragments | 3 fragments | 1 fragment |

**Impact:**
- More fragments = more GATT writes
- More GATT writes = more latency (operation queue)
- More GATT writes = more radio time = more battery

### Transmission Time Estimation

**Assumptions:**
- GATT write time: ~10ms per operation
- Fragmentation overhead: 5 bytes per fragment

**Examples:**
```
500-byte packet at MTU 23:
- Fragments: 28
- Transmission time: 28 × 10ms = 280ms
- Efficiency: 78%

500-byte packet at MTU 185:
- Fragments: 3
- Transmission time: 3 × 10ms = 30ms
- Efficiency: 97%

500-byte packet at MTU 517:
- Fragments: 1
- Transmission time: 1 × 10ms = 10ms
- Efficiency: 99%
```

**Optimization:** Always negotiate max MTU!

---

## Common Issues

### Issue: Packet Never Completes

**Symptoms:**
- Fragments arrive but `receiveFragment()` always returns `null`
- `packetsInProgress` keeps growing
- Eventually timeout fires

**Causes:**
1. **Lost fragment** - One fragment never arrives
2. **Wrong sender ID** - Fragments have different sender IDs
3. **Duplicate START** - New START fragment clears buffer

**Solutions:**
```kotlin
// Check sender ID consistency
Log.d(TAG, "Fragment from: $senderId")

// Check sequence coverage
val buffer = reassemblyBuffers[senderId]
Log.d(TAG, "Sequences received: ${buffer.fragments.keys.sorted()}")
Log.d(TAG, "Expecting: 0..${buffer.totalFragments-1}")

// Monitor for duplicates
Log.w(TAG, "Duplicate fragment: seq=$sequence")
```

### Issue: Efficiency Lower Than Expected

**Symptoms:**
- High fragmentation overhead
- Many small fragments
- Slow transmission

**Cause:** Not negotiating MTU or using old MTU value

**Solution:**
```kotlin
// Always request max MTU
operationQueue.enqueue(RequestMtu(gatt, mtu = 517))

// Verify MTU used
Log.d(TAG, "Current MTU: ${fragmenter.mtu}")
Log.d(TAG, "Payload size: ${fragmenter.payloadSize}")
```

### Issue: Memory Leak from Incomplete Packets

**Symptoms:**
- Memory usage grows over time
- `packetsInProgress` never decreases
- App eventually crashes (OOM)

**Cause:** Reassembly buffers not cleaned up

**Solution:**
```kotlin
// Set appropriate timeout
val reassembler = BleReassembler(timeoutMs = 30000)

// Clean up on disconnect
reassembler.clearSender(peerAddress)

// Monitor buffer sizes
val inProgress = reassembler.getInProgressCount()
if (inProgress > 10) {
    log("WARNING: $inProgress incomplete packets (possible memory leak?)")
}
```

---

## See Also

- `BLE_ARCHITECTURE_OVERVIEW.md` - Full architecture context
- `../patterns/fragmentation-reassembly.md` - Complete implementation example
- `../templates/fragmentation-worker.kt` - Reusable code
- `../checklists/performance-targets-checklist.md` - Efficiency targets
- `TROUBLESHOOTING.md` - Debugging fragmentation issues
