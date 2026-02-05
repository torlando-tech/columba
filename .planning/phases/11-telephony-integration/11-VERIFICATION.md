# Phase 11 Verification: Telephony Integration

## Metadata
- **Phase:** 11 (Telephony Integration)
- **Verified:** 2026-02-05
- **Status:** `GAPS_FOUND`

## Verification Summary

| Criterion | Status | Notes |
|-----------|--------|-------|
| All 8 quality profiles (ULBW through ULL) work | ⚠️ PARTIAL | Profiles created but not exercised in calls |
| Outgoing calls connect with dial tone feedback | ❌ FAILS | Audio flows through Python LXST, not Kotlin |
| Incoming calls ring (using ringtone if configured) | ⚠️ PARTIAL | Ringtone code exists but untested |
| Mid-call profile switching works | ❌ NOT TESTED | Never reaches ESTABLISHED state |
| Mute controls work correctly | ⚠️ PARTIAL | Code exists, unit tests pass, but no real audio |

**Overall Score: 1/5 criteria fully satisfied**

## Critical Gaps

### Gap 1: Signal Type Mismatch (CRITICAL BLOCKER)

**Problem:** Python sends STRING events, Kotlin expects INTEGER signals

- **Python (call_manager.py line 503):** `_notify_kotlin('established', identity_hash)`
- **Kotlin (Telephone.kt line 152):** `networkTransport.setSignalCallback { signal -> onSignalReceived(signal) }`
- **Kotlin expects:** `signal: Int` (0x00-0x06 matching Signalling constants)
- **Python sends:** `event: String` ("ringing", "established", etc.)

**Impact:** Kotlin Telephone NEVER receives STATUS_CONNECTING or STATUS_ESTABLISHED signals, so:
- `openPipelines()` is never called for outgoing calls
- `startPipelines()` is never called
- Audio flows through Python LXST instead of Kotlin LXST

### Gap 2: Dual Audio Pipeline Conflict (CRITICAL BLOCKER)

**Problem:** Both Python LXST and Kotlin LXST try to use AudioTrack

**Evidence from logs:**
```
AudioFlinger: BUFFER TIMEOUT: remove track... due to underrun
📡 PKT.tx#201  (Python LXST sending packets)
🎤 LS: sink blocked!  (Python LXST microphone saturated)
```

**Root cause:** Python LXST audio pipeline is never disabled. The integration only routes *call control* to Kotlin Telephone, but Python LXST still:
- Captures from microphone
- Encodes with codecs
- Sends packets to network
- Receives packets from network
- Decodes and plays to speaker

**Impact:** AudioFlinger buffer underruns cause choppy audio

### Gap 3: Missing PythonNetworkTransport Signal Forwarding

**Problem:** `NetworkPacketBridge.setSignalCallback()` is not wired to Python signal path

**Current flow:**
1. Python call_manager receives signal from network
2. Python fires `_notify_kotlin('established', ...)`
3. `handlePythonTelephoneEvent(event: String, data)` receives STRING event
4. Kotlin Telephone.onSignalReceived() expects INTEGER signal - NEVER CALLED

**Required flow:**
1. Python call_manager receives signal from network
2. Python forwards INTEGER signal to NetworkPacketBridge
3. NetworkPacketBridge invokes signal callback with Integer
4. Kotlin Telephone.onSignalReceived(signal: Int) processes it

### Gap 4: ROADMAP.md vs STATE.md Discrepancy

**Problem:** Documentation shows conflicting completion status

- **ROADMAP.md line 157:** `| 11. Telephony Integration | 0/5 | Not started | - |`
- **STATE.md line 13:** `Phase: 11 of 12 (Telephony Integration)` + `Status: **Phase Complete**`
- **ROADMAP.md line 36:** `- [ ] **Phase 11: Telephony Integration**` (unchecked)

**Impact:** Project state is unclear, causing confusion about what's done

## Non-Critical Gaps (Tech Debt)

### TD-1: No Instrumented Tests for Telephone

SUMMARY files show only unit tests with mocked dependencies. No real audio tests exist.

### TD-2: Profile.createCodec() Creates JNI-Dependent Codecs

Unit tests catch `UnsatisfiedLinkError` to work around this. Real integration requires device.

### TD-3: Python LXST Disable Path Not Implemented

There's no code to disable Python LXST audio when Kotlin LXST is active.

## Requirements Coverage

| Requirement | Status | Blocking Gap |
|-------------|--------|--------------|
| TEL-01: Profile class with all 8 profiles | ✅ SATISFIED | - |
| TEL-02: NetworkTransport interface | ✅ SATISFIED | - |
| TEL-03: Telephone state machine | ⚠️ PARTIAL | Gap 1: signals don't flow |
| TEL-04: Audio pipeline lifecycle | ❌ BLOCKED | Gap 1, Gap 2 |
| TEL-05: Mute controls | ⚠️ PARTIAL | Code exists but no audio |

## Root Cause Analysis

The Phase 11 implementation built Kotlin components that MATCH Python LXST structure, but did not:

1. **Wire integer signals** from Python to Kotlin Telephone
2. **Disable Python LXST audio** when Kotlin LXST is active
3. **Test the actual signal flow** end-to-end

The SUMMARY files document successful code creation and unit tests, but integration was never verified with actual calls.

## Recommended Fix (Phase 11.5)

### Fix 1: Wire Integer Signals

Modify `call_manager.py` to call `NetworkPacketBridge.receiveSignal(int)` when Python LXST receives signals:

```python
def _handle_signal_from_network(self, signal):
    # Forward to Kotlin via NetworkPacketBridge
    if self._network_packet_bridge:
        self._network_packet_bridge.receiveSignal(signal)
```

### Fix 2: Disable Python LXST Audio

Add flag to Python LXST to disable audio processing when Kotlin LXST is active:

```python
class Telephone:
    def set_kotlin_audio_active(self, active):
        self._kotlin_audio_active = active
        if active:
            self._stop_python_audio_pipelines()
```

### Fix 3: Remove Duplicate Audio Path

Either:
- **Option A:** Kotlin LXST handles ALL audio, Python only handles network packets
- **Option B:** Python LXST handles audio, Kotlin only handles UI state (current broken state)

**Recommendation:** Option A (original v0.8.0 goal)

## Commits to Review

| Commit | Description | Issue |
|--------|-------------|-------|
| 606ee9b6 | Kotlin Telephone integration to call_manager.py | Added string events, not integer signals |
| 8c557f1e | call_manager.py Kotlin callbacks | `_notify_kotlin` sends strings |
| d6e4cabd | Kotlin-Python wiring plan | Plan didn't specify signal type conversion |

## Verification Method

To verify fixes:

1. Make a call between two devices
2. Check logs for: `📞 Signal received: 0x06` (STATUS_ESTABLISHED from Kotlin)
3. Verify NO Python LXST audio logs: `📡 PKT.tx`, `🎛️ MIX.j`, `🎤 LS:`
4. Verify Kotlin LXST audio logs show packet flow
5. Confirm no AudioFlinger BUFFER TIMEOUT errors
