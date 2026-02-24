# Requirements: Columba v0.10.0 Voice Messages

**Defined:** 2026-02-23
**Core Value:** Reliable off-grid messaging with a polished, responsive user experience.

## v1 Requirements

Requirements for v0.10.0 release. Each maps to roadmap phases.

### Recording

- [ ] **REC-01**: User can record a voice message by holding the mic button in chat input
- [ ] **REC-02**: Mic button replaces send button when text field is empty
- [ ] **REC-03**: User can cancel recording by sliding finger left (slide-to-cancel)
- [ ] **REC-04**: Recording timer counts up from 0:00, visible during recording
- [ ] **REC-05**: Recording auto-stops and auto-sends at 30-second limit
- [ ] **REC-06**: Visual recording indicator (pulsing animation) during active recording
- [ ] **REC-07**: Recordings shorter than 300ms are discarded (prevent accidental sends)

### Protocol & Transport

- [x] **PROTO-01**: Voice messages encode with Opus VOICE_MEDIUM (8kbps) via LXST-kt NativeCaptureEngine + Opus codec
- [x] **PROTO-02**: Encoded audio sent as FIELD_AUDIO (0x07) in LXMF message
- [x] **PROTO-03**: FIELD_AUDIO (0x07) / LEGACY_LOCATION_FIELD (7) collision resolved safely
- [x] **PROTO-04**: Waveform amplitude data stored alongside audio for rendering without re-decode
- [x] **PROTO-05**: Received FIELD_AUDIO extracted from LXMF message and stored in MessageEntity.fieldsJson

### Playback

- [ ] **PLAY-01**: Voice messages render inline as audio bubble with play/pause button
- [ ] **PLAY-02**: Waveform visualization shows audio shape in message bubble
- [ ] **PLAY-03**: Waveform animates as progress indicator during playback
- [ ] **PLAY-04**: Duration displayed in mm:ss format
- [ ] **PLAY-05**: Unplayed voice messages have visual indicator (distinct from played)
- [ ] **PLAY-06**: Only one voice message plays at a time (new play stops previous)
- [ ] **PLAY-07**: Playback uses LXST-kt Opus decode + Oboe native output

### Audio Focus & Edge Cases

- [ ] **EDGE-01**: Recording requests AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE (silences notifications)
- [ ] **EDGE-02**: Playback requests AUDIOFOCUS_GAIN_TRANSIENT (ducks other audio)
- [ ] **EDGE-03**: Incoming call during recording stops and discards recording gracefully
- [ ] **EDGE-04**: App backgrounded during recording stops and discards
- [ ] **EDGE-05**: Headphone disconnect during playback pauses playback
- [ ] **EDGE-06**: Corrupt/incomplete audio data shows error state in bubble (no crash)
- [ ] **EDGE-07**: RECORD_AUDIO permission requested before first recording attempt

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Playback Enhancements

- **PLAY-08**: Playback speed controls (1x / 1.5x / 2x toggle)
- **PLAY-09**: Remember playback position within session
- **PLAY-10**: Sequential auto-play of unplayed voice messages
- **PLAY-11**: Proximity sensor switches audio to earpiece when held to ear

### Recording Enhancements

- **REC-08**: Lock recording mode (swipe up on mic to record hands-free)
- **REC-09**: Draft preview — listen to recording before sending (requires REC-08)

### System Integration

- **SYS-01**: Background/out-of-chat playback with mini-player bar
- **SYS-02**: Bluetooth audio routing for playback

## Out of Scope

| Feature | Reason |
|---------|--------|
| Voice-to-text transcription | Requires large ML models or cloud API; privacy-hostile for mesh messenger |
| Voice message forwarding | Same as sending a new message; no protocol change needed |
| Raise-to-speak recording | Fragile sensor fusion, high false-positive rate |
| Self-destructing voice messages | LXMF has no deletion protocol; false sense of security |
| Video notes (round video messages) | Video at mesh-compatible bitrates is unusable quality |
| Audio effects / voice filters | Unnecessary complexity; Opus VOIP mode has built-in signal processing |
| Generic audio file inline player | Voice messages (field 7) are distinct from file attachments (field 5) |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| REC-01 | Phase 8 | Pending |
| REC-02 | Phase 10 | Pending |
| REC-03 | Phase 10 | Pending |
| REC-04 | Phase 10 | Pending |
| REC-05 | Phase 8 | Pending |
| REC-06 | Phase 10 | Pending |
| REC-07 | Phase 8 | Pending |
| PROTO-01 | Phase 7 | Complete |
| PROTO-02 | Phase 7 | Complete |
| PROTO-03 | Phase 7 | Complete |
| PROTO-04 | Phase 7 | Complete |
| PROTO-05 | Phase 7 | Complete |
| PLAY-01 | Phase 9 | Pending |
| PLAY-02 | Phase 9 | Pending |
| PLAY-03 | Phase 9 | Pending |
| PLAY-04 | Phase 9 | Pending |
| PLAY-05 | Phase 9 | Pending |
| PLAY-06 | Phase 9 | Pending |
| PLAY-07 | Phase 9 | Pending |
| EDGE-01 | Phase 8 | Pending |
| EDGE-02 | Phase 9 | Pending |
| EDGE-03 | Phase 10 | Pending |
| EDGE-04 | Phase 10 | Pending |
| EDGE-05 | Phase 10 | Pending |
| EDGE-06 | Phase 9 | Pending |
| EDGE-07 | Phase 8 | Pending |

**Coverage:**
- v1 requirements: 22 total
- Mapped to phases: 22
- Unmapped: 0

---
*Requirements defined: 2026-02-23*
*Last updated: 2026-02-24 after Phase 7 completion*
