---
phase: 11
plan: 01
subsystem: telephony
tags:
  - profile
  - sealed-class
  - network-transport
  - codec2
  - opus
dependency-graph:
  requires:
    - "07-01: Codec base class"
    - "07-02: Opus codec"
    - "07-03: Codec2 codec"
  provides:
    - "Profile sealed class with 8 quality profiles"
    - "NetworkTransport interface for network abstraction"
  affects:
    - "11-02: Telephone class (uses Profile and NetworkTransport)"
    - "11-03: PythonNetworkTransport implementation"
tech-stack:
  added: []
  patterns:
    - "sealed class for type-safe profiles"
    - "interface abstraction for network transport"
key-files:
  created:
    - "reticulum/src/main/java/com/lxmf/messenger/reticulum/call/telephone/Profile.kt"
    - "reticulum/src/main/java/com/lxmf/messenger/reticulum/call/telephone/NetworkTransport.kt"
  modified: []
decisions:
  - id: "profile-embedded-codec"
    choice: "Profile.createCodec() returns codec instance"
    rationale: "Cleaner than mapping in Telephone, profile knows its codec config"
  - id: "data-object-profiles"
    choice: "Use data object instead of object for profile definitions"
    rationale: "Modern Kotlin idiom, provides equals/hashCode/toString automatically"
  - id: "suspend-establish-link"
    choice: "NetworkTransport.establishLink() is suspend function"
    rationale: "Link establishment takes time (path requests), avoid blocking caller"
metrics:
  duration: "~3 minutes"
  completed: "2026-02-04"
---

# Phase 11 Plan 01: Profile and NetworkTransport Summary

Profile sealed class with 8 quality profiles (ULBW-ULL) and NetworkTransport interface abstracting Python bridge for future pure-Kotlin Reticulum.

## What Changed

### Profile.kt

Created sealed class with 8 profile objects matching Python LXST Telephony.py exactly:

| Profile | ID | Codec | Mode/Profile | Frame Time |
|---------|-----|-------|--------------|------------|
| ULBW | 0x10 | Codec2 | CODEC2_700C | 400ms |
| VLBW | 0x20 | Codec2 | CODEC2_1600 | 320ms |
| LBW | 0x30 | Codec2 | CODEC2_3200 | 200ms |
| MQ | 0x40 | Opus | PROFILE_VOICE_MEDIUM | 60ms |
| HQ | 0x50 | Opus | PROFILE_VOICE_HIGH | 60ms |
| SHQ | 0x60 | Opus | PROFILE_VOICE_MAX | 60ms |
| LL | 0x70 | Opus | PROFILE_VOICE_MEDIUM | 20ms |
| ULL | 0x80 | Opus | PROFILE_VOICE_MEDIUM | 10ms |

Key features:
- `createCodec()` factory method returns configured codec instance
- `fromId(id: Int)` lookup by profile ID
- `next(profile)` cycles to next profile (for UI toggle)
- `DEFAULT = MQ` (Medium Quality)

### NetworkTransport.kt

Created interface abstracting network layer from Telephone class:

| Method | Purpose |
|--------|---------|
| `suspend establishLink(hash)` | Set up Reticulum link to destination |
| `teardownLink()` | Clean up active link |
| `sendPacket(frame)` | Fire-and-forget audio packet |
| `sendSignal(signal)` | Send signalling message |
| `setPacketCallback(cb)` | Register incoming packet handler |
| `setSignalCallback(cb)` | Register incoming signal handler |
| `isLinkActive` | Check link state |

This interface:
- Decouples Telephone from PyObject/Chaquopy
- Enables future pure-Kotlin Reticulum implementation
- Keeps signalling protocol transport-agnostic

## Deviations from Plan

None - plan executed exactly as written.

## Decisions Made

1. **Profile.createCodec() returns codec instance** - Encapsulates codec configuration in Profile rather than mapping in Telephone class. Cleaner architecture since profile knows its requirements.

2. **Use data object for profiles** - Modern Kotlin idiom (1.9+) provides automatic equals/hashCode/toString. More explicit than plain object.

3. **suspend fun establishLink()** - Link establishment involves network I/O (path requests, link handshake). Making it suspend avoids blocking the caller and enables proper timeout handling.

## Commits

| Hash | Message |
|------|---------|
| a3f42aa7 | feat(11-01): create Profile sealed class with 8 quality profiles |
| 5a6b3de7 | feat(11-01): create NetworkTransport interface for network abstraction |

## Verification

- [x] Both files compile without errors
- [x] Profile IDs match Python LXST exactly (verified: 0x10-0x80)
- [x] Profile.createCodec() returns correct codec types
- [x] NetworkTransport interface has all required methods

## Next Phase Readiness

**Ready for 11-02 (Telephone class):**
- Profile class available for state management
- NetworkTransport interface ready for implementation
- Codec factory pattern established

**Dependencies satisfied:**
- Opus.kt provides PROFILE_VOICE_MEDIUM/HIGH/MAX constants
- Codec2.kt provides CODEC2_700C/1600/3200 constants
- Codec base class provides common interface
