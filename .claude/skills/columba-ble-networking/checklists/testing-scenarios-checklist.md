# BLE Testing Scenarios Checklist

Complete checklist for verifying BLE implementation functionality.

## Pre-Testing Setup

- [ ] Device has Bluetooth 4.0+ hardware
- [ ] Android 7.0+ (API 24+) installed
- [ ] Columba APK installed
- [ ] Bluetooth enabled in device settings
- [ ] Battery optimization disabled for Columba (Settings → Apps → Columba → Battery)
- [ ] ADB connected for log monitoring (optional but recommended)

---

## Phase 1: Basic Functionality

### Permissions

- [ ] App requests Bluetooth permissions on first launch
- [ ] Android 12+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE requested
- [ ] Android 11 and below: ACCESS_FINE_LOCATION requested
- [ ] Permission rationale shown before request
- [ ] App handles permission denial gracefully
- [ ] App requests re-grant if permanently denied (redirects to Settings)

**Test:** Deny permissions → verify app shows error, doesn't crash

### Service Lifecycle

- [ ] BleService starts successfully
- [ ] Foreground notification appears
- [ ] Notification shows "BLE Service starting..."
- [ ] Service appears in: `adb shell dumpsys activity services | grep BleService`
- [ ] Service survives screen off
- [ ] Service survives app switch
- [ ] Service stops cleanly when requested

**Test:** Start service → lock screen → wait 1 minute → unlock → verify service still running

---

## Phase 2: Discovery & Scanning

### Central Mode (Scanning)

- [ ] Scan starts without errors
- [ ] Scan filter includes Reticulum service UUID (`00000001-5824...`)
- [ ] Devices with matching UUID appear in list
- [ ] Devices without matching UUID are filtered out
- [ ] RSSI values update every 1-5 seconds
- [ ] Device priorityScore calculated correctly
- [ ] Scan interval adapts: 5s (active) → 30s (idle)
- [ ] Scan mode adapts: LOW_LATENCY → BALANCED → LOW_POWER
- [ ] Scan doesn't trigger "SCAN_FAILED" errors
- [ ] Scan works in background (screen off)

**Test:** Scan for 5 minutes → verify scan interval increases → verify scan mode changes to LOW_POWER

### Peripheral Mode (Advertising)

- [ ] Advertising starts without errors
- [ ] Device name appears in advertisement
- [ ] Reticulum service UUID included in advertisement
- [ ] Other devices can discover this device via nRF Connect
- [ ] Advertising survives screen off
- [ ] Advertising handles "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS" gracefully
- [ ] Advertising retries on failure (up to 5 attempts)

**Test:** Start advertising → open nRF Connect on another phone → verify device appears with correct UUID

---

## Phase 3: Connection Establishment

### Outgoing Connection (Central Mode)

- [ ] Can connect to discovered device
- [ ] Connection completes within 5 seconds (typical)
- [ ] Service discovery succeeds
- [ ] Reticulum service found (`00000001-5824...`)
- [ ] RX and TX characteristics found
- [ ] MTU negotiation attempted (requests 517 bytes)
- [ ] MTU callback received
- [ ] Notifications enabled on TX characteristic
- [ ] `onConnected` callback fires
- [ ] Connection state updates in UI
- [ ] Statistics increment (totalCentralConnections++)

**Test:** Connect to device → verify all callbacks → check logs for proper flow

### Incoming Connection (Peripheral Mode)

- [ ] GATT server opens successfully
- [ ] Service added to GATT server
- [ ] Characteristics created correctly (RX: WRITE, TX: NOTIFY)
- [ ] CCCD descriptor added to TX characteristic
- [ ] Other device can connect (via nRF Connect or Columba)
- [ ] `onCentralConnected` callback fires
- [ ] Connection state updates
- [ ] Statistics increment (totalPeripheralConnections++)

**Test:** Start peripheral mode → connect from nRF Connect → verify connection

### Dual Connections

- [ ] Same peer can connect via both modes simultaneously
- [ ] Central connection tracked separately from peripheral
- [ ] Both connections use same fragmenter/reassembler
- [ ] Effective MTU uses max of both connections
- [ ] Data can be sent via either connection
- [ ] Disconnecting one connection doesn't affect the other

**Test:** Device A connects to Device B (central) → Device B connects to Device A (central) → verify 2 connections exist

---

## Phase 4: Data Transfer

### Sending Data

- [ ] Can send data to connected device
- [ ] Small packets (< MTU) sent as single fragment
- [ ] Large packets (> MTU) fragmented correctly
- [ ] Fragment headers correct (type, sequence, total)
- [ ] All fragments sent successfully
- [ ] `onCharacteristicWrite` callbacks received
- [ ] Statistics increment (packetsSent++, fragmentsSent++)
- [ ] No silent failures

**Test:** Send 500-byte packet with MTU 185 → verify 3 fragments sent → check logs for "Write successful"

### Receiving Data

- [ ] Notifications received from peer
- [ ] Fragments stored in reassembly buffer
- [ ] Out-of-order fragments handled correctly
- [ ] Complete packet reassembled when all fragments arrive
- [ ] `onDataReceived` callback fires with complete packet
- [ ] Statistics increment (packetsReceived++, fragmentsReceived++)
- [ ] Reassembly timeout cleans up incomplete packets

**Test:** Receive fragmented packet → verify reassembly → check packet integrity

### Fragmentation Edge Cases

- [ ] Empty packet rejected (IllegalArgumentException)
- [ ] Single-byte packet sent correctly
- [ ] Max-size packet (500 bytes) handled
- [ ] MTU changes update fragmenter
- [ ] Reassembly handles duplicates (ignores)
- [ ] Reassembly handles missing fragments (timeout)
- [ ] Reassembly handles out-of-order arrival

**Test:** Send packets of varying sizes (1, 50, 200, 500 bytes) → verify all succeed

---

## Phase 5: Error Handling & Recovery

### Status 133 Recovery

- [ ] Status 133 triggers close + retry
- [ ] Retry uses exponential backoff (30s, 60s, 120s)
- [ ] After 3 failures, device blacklisted
- [ ] Blacklist duration: 5 minutes
- [ ] Blacklist expires and clears
- [ ] Successful connection clears retry state

**Test:** Force Status 133 (turn off Bluetooth on peer mid-connection) → verify retry → verify blacklist

### Connection Timeout

- [ ] Connection timeout triggers after 30 seconds
- [ ] Timeout cancels on successful connection
- [ ] Timeout cleans up GATT connection
- [ ] Timeout triggers retry logic

**Test:** Connect to device that's out of range → verify timeout after 30s → verify cleanup

### Scan Frequency Limit

- [ ] Doesn't exceed 5 scans per 30 seconds
- [ ] Adaptive intervals prevent scan failures
- [ ] If scan fails (error 2), backs off gracefully

**Test:** Monitor scan frequency → verify never exceeds limit

### Disconnect Handling

- [ ] Normal disconnect handled gracefully
- [ ] Error disconnect (status != 0) logged
- [ ] GATT connection closed
- [ ] Reassembly buffer cleared for peer
- [ ] Statistics updated
- [ ] UI updated (peer removed from connected list)

**Test:** Disconnect peer → verify cleanup → verify no memory leaks

---

## Phase 6: Performance & Stability

### Connection Limits

- [ ] Max 7 simultaneous connections enforced
- [ ] 8th connection attempt rejected or queues
- [ ] Lowest-priority connection dropped when at limit (optional)
- [ ] Connection count accurate in statistics

**Test:** Connect to 8 devices → verify limit enforcement

### MTU Negotiation

- [ ] MTU negotiation requested on every connection
- [ ] Max MTU (517) requested
- [ ] Fallback to default (185) if negotiation fails
- [ ] Fallback to minimum (23) if no negotiation
- [ ] Fragmenter MTU updated after negotiation

**Test:** Connect to various devices → check negotiated MTU values → verify fragmenter uses correct MTU

### Battery Impact

- [ ] Foreground service prevents Doze restrictions
- [ ] Scan intervals adapt to save battery
- [ ] Idle connections don't drain battery excessively
- [ ] Battery drain measured: < 5% per hour (scanning), < 2% per hour (idle)

**Test:** Run for 1 hour → check battery stats → verify drain within targets

### Long-Duration Stability

- [ ] Connections stable for 24+ hours
- [ ] No memory leaks (check memory usage over time)
- [ ] No crashes
- [ ] Statistics accurate after extended operation
- [ ] Scan intervals still adapting correctly

**Test:** Run for 24 hours → verify no crashes → check memory usage → verify statistics

---

## Phase 7: Multi-Device Testing

### Two-Device Mesh

**Device A and Device B both run Columba**

- [ ] Device A discovers Device B
- [ ] Device B discovers Device A
- [ ] Both devices auto-connect
- [ ] Dual connections established (A→B and B→A)
- [ ] Data transfer works both directions
- [ ] Statistics show connections on both devices

**Test:** Set up 2 devices → verify mutual discovery → verify dual connections

### Three-Device Mesh

**Devices A, B, C in range of each other**

- [ ] All devices discover each other
- [ ] All devices connect (6 connections total: A↔B, B↔C, A↔C)
- [ ] Data can route through mesh (A→B→C)
- [ ] Connection limits respected per device

**Test:** Set up 3 devices → verify full mesh → test multi-hop routing (future)

### Seven-Device Mesh (Stress Test)

- [ ] Device A can handle 7 simultaneous connections
- [ ] All connections remain stable
- [ ] Data transfer works to all 7 devices
- [ ] Performance remains acceptable

**Test:** Connect 7 devices to one device → verify all connections stable → test data transfer

---

## Phase 8: Real-World Scenarios

### Moving Out of Range

- [ ] Connection drops gracefully when out of range
- [ ] Reassembly buffers cleared
- [ ] Auto-reconnect when back in range
- [ ] No crashes or errors

**Test:** Connect → walk away until disconnection → walk back → verify reconnection

### Bluetooth Toggle

- [ ] Disabling Bluetooth stops all operations
- [ ] Connections cleaned up properly
- [ ] Re-enabling Bluetooth resumes operations
- [ ] Auto-reconnect to previously connected devices

**Test:** Toggle Bluetooth off/on → verify graceful handling

### App Background/Foreground

- [ ] Connections maintained when app backgrounded
- [ ] Scanning continues in background
- [ ] Foreground service keeps app alive
- [ ] Returning to foreground works correctly

**Test:** Connect → background app → wait 5 minutes → foreground → verify connections still active

### Device Reboot

- [ ] Service stops on device shutdown
- [ ] Connections cleaned up
- [ ] After reboot, service can restart
- [ ] Can re-establish connections

**Test:** Reboot device → restart app → verify BLE functionality restored

---

## Phase 9: UI/UX Testing

### BLE Test Screen

- [ ] Screen loads without errors
- [ ] Permission request works
- [ ] Service status updates in real-time
- [ ] Discovered devices list populates
- [ ] Device cards show correct info (name, address, RSSI, score)
- [ ] Connect/Disconnect buttons work
- [ ] Statistics update every second
- [ ] UI doesn't freeze during operations

**Test:** Open BLE Test screen → verify all UI elements functional

### Notifications

- [ ] Foreground notification appears when service starts
- [ ] Notification shows current status
- [ ] Notification updates when connections change
- [ ] Notification cannot be dismissed while service running
- [ ] Notification has icon and title

**Test:** Start service → check notification → connect devices → verify notification updates

---

## Phase 10: Edge Cases

### Rapid Connection Cycling

- [ ] Connect/disconnect 100 times in succession
- [ ] No crashes
- [ ] No memory leaks
- [ ] Connection success rate > 80%

**Test:** Script rapid connect/disconnect → monitor for issues

### Large Packet Transfer

- [ ] Can send/receive 500-byte packets
- [ ] Fragmentation works correctly
- [ ] Reassembly works correctly
- [ ] No data corruption

**Test:** Send maximum-size Reticulum packet → verify received correctly

### Simultaneous Data Transfer

- [ ] Can send data to multiple peers simultaneously
- [ ] Operation queue handles concurrent writes
- [ ] No interference between connections

**Test:** Send data to 3 devices at once → verify all succeed

---

## Failure Criteria

❌ **Critical Failures (Must Fix):**
- Crash or ANR
- Memory leak (growing over time)
- Permission bypass (security issue)
- Data corruption
- Complete inability to connect

⚠️ **Major Issues (Should Fix):**
- Connection success rate < 80%
- Battery drain > 10% per hour
- Packet loss > 5%
- MTU negotiation always fails

💡 **Minor Issues (Nice to Fix):**
- Slow connection (> 10s)
- UI lag during operations
- Verbose logging in production
- Suboptimal scan intervals

---

## Success Criteria

✅ **All checks passed in Phases 1-6** (basic functionality)

✅ **80%+ checks passed in Phases 7-9** (advanced features)

✅ **No critical failures in Phase 10** (edge cases)

✅ **Performance within targets:**
- Discovery: < 5s
- Connection: < 5s
- Packet latency: < 100ms
- Battery: < 5%/hr scanning

✅ **Stability:**
- 24-hour test passes
- No memory leaks
- No crashes

---

## After Testing

**Document results:**
- [ ] Record which tests passed/failed
- [ ] Note device models tested
- [ ] Note Android versions tested
- [ ] Measure battery impact
- [ ] Measure connection success rate
- [ ] Identify any bugs or issues

**Report:**
- Create GitHub issues for failures
- Update documentation with findings
- Share results with team
