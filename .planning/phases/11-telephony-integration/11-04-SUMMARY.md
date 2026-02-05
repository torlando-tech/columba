---
phase: 11
plan: 04
subsystem: telephony
tags:
  - kotlin-telephone
  - python-bridge
  - bidirectional-communication
  - call-manager
dependency-graph:
  requires:
    - "11-02: Telephone class with NetworkTransport"
    - "11-03: Audio feedback (dial tone, ringtone)"
    - "10-05: NetworkPacketBridge for packet transfer"
  provides:
    - "Bidirectional Kotlin-Python call communication"
    - "Python call_manager Kotlin integration"
    - "PythonWrapperManager Telephone initialization"
  affects:
    - "Future: End-to-end call testing"
    - "Future: UI integration with getTelephone()"
tech-stack:
  added: []
  patterns:
    - "Callback-based bidirectional communication"
    - "PyObject abstraction via PythonNetworkTransport"
key-files:
  created: []
  modified:
    - "python/lxst_modules/call_manager.py"
    - "app/src/main/java/com/lxmf/messenger/service/manager/PythonWrapperManager.kt"
decisions:
  - id: "callback-pattern"
    choice: "Python notifies Kotlin via set_kotlin_telephone_callback"
    rationale: "Matches existing bridge patterns, allows Python LXST to notify Kotlin of incoming calls"
  - id: "callmanager-pyobject-storage"
    choice: "Store callManagerPyObject in PythonWrapperManager"
    rationale: "Needed for PythonNetworkTransport creation; wrapper.get_call_manager() returns the instance"
  - id: "event-based-python-notifications"
    choice: "Python fires events (ringing, established, ended, busy, rejected)"
    rationale: "Kotlin Telephone handles events and updates state machine accordingly"
metrics:
  duration: "~3 minutes"
  completed: "2026-02-05"
---

# Phase 11 Plan 04: Wire Kotlin Telephone to Python call_manager Summary

Bidirectional Kotlin-Python communication wired for Telephone. call_manager.py now has Kotlin integration methods (on_state_changed, on_profile_changed, receive_audio_packet, receive_signal) and PythonWrapperManager creates Telephone with PythonNetworkTransport.

## Performance

- **Duration:** ~3 minutes
- **Started:** 2026-02-05
- **Completed:** 2026-02-05
- **Tasks:** 2
- **Files modified:** 2

## Accomplishments

- Python call_manager.py now has Kotlin Telephone integration:
  - `on_state_changed()` for Kotlin state notifications
  - `on_profile_changed()` for profile sync
  - `set_kotlin_telephone_callback()` for callback registration
  - `_notify_kotlin()` for Python->Kotlin event notifications
  - `receive_audio_packet()` wired to forward to LXST Packetizer
  - `receive_signal()` wired to forward to LXST signalling
  - Existing handlers (_handle_ringing, _handle_established, etc.) now notify Kotlin

- PythonWrapperManager.kt now initializes Kotlin Telephone:
  - `setupTelephone()` creates Telephone with PythonNetworkTransport
  - `getTelephone()` accessor for UI layer
  - `handlePythonTelephoneEvent()` handles Python callbacks
  - Proper cleanup in shutdown()

## Task Commits

1. **Task 1: Update call_manager.py** - `8c557f1e` (feat)
   - Kotlin integration methods added
   - Existing handlers wired to notify Kotlin
   - receive_audio_packet/receive_signal stubs replaced with LXST forwarding

2. **Task 2: Update PythonWrapperManager.kt** - `606ee9b6` (feat)
   - Telephone and PythonNetworkTransport imports
   - setupTelephone() with full initialization
   - handlePythonTelephoneEvent() for Python callbacks
   - getTelephone() accessor
   - Shutdown cleanup

## Files Created/Modified

- `python/lxst_modules/call_manager.py` - Added Kotlin Telephone integration
- `app/src/main/java/com/lxmf/messenger/service/manager/PythonWrapperManager.kt` - Added Telephone initialization

## Decisions Made

1. **Callback-based bidirectional communication** - Python uses `set_kotlin_telephone_callback` to register callback for notifying Kotlin of state events. Matches existing bridge patterns.

2. **Store callManagerPyObject reference** - PythonWrapperManager stores reference to Python call_manager (via `wrapper.get_call_manager()`) for PythonNetworkTransport creation.

3. **Event-based Python notifications** - Python fires events (ringing, established, ended, busy, rejected) to Kotlin via callback. Kotlin Telephone's `onIncomingCall()` is invoked for incoming calls.

## Deviations from Plan

1. **[Rule 3 - Blocking] PythonWrapperManager location** - Plan stated file was in `reticulum` module but it's actually in `app` module. Updated path accordingly.

## Issues Encountered

None.

## Next Phase Readiness

**Phase 11 Telephony Integration Complete:**
- Profile and NetworkTransport interface (11-01)
- Telephone class core structure (11-02)
- Audio feedback (11-03)
- Bidirectional Kotlin-Python communication (11-04)

**Integration API:**
- `PythonWrapperManager.setupCallManager()` - Initialize Python call manager
- `PythonWrapperManager.setupTelephone()` - Initialize Kotlin Telephone
- `PythonWrapperManager.getTelephone()` - Access Telephone for UI layer

**Call Flow:**
1. Kotlin initiates: `telephone.call(destinationHash)` -> PythonNetworkTransport -> Python call_manager
2. Python responds: `_notify_kotlin('ringing')` -> handlePythonTelephoneEvent -> telephone.onIncomingCall()
3. Audio flows: Kotlin Packetizer -> `receive_audio_packet()` -> Python LXST Packetizer -> network
4. Signals flow: Kotlin Signalling -> `receive_signal()` -> Python LXST signalling -> network

---
*Phase: 11-telephony-integration*
*Completed: 2026-02-05*
