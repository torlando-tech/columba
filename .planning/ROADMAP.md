# Roadmap: Columba

## Milestones

- v0.7.3-beta (Phases 1-2.3) - shipped 2026-01-28
- v0.7.4-beta Bug Fixes (Phases 3-6) - in progress
- **v0.10.0 Voice Messages** (Phases 7-10) - planned

## Phases

<details>
<summary>v0.7.3-beta (Phases 1-2.3) - SHIPPED 2026-01-28</summary>

See git history for v0.7.3-beta milestone.

</details>

<details>
<summary>v0.7.4-beta Bug Fixes (Phases 3-6) - IN PROGRESS</summary>

### Phase 3: ANR Elimination
**Goal**: ViewModel initialization never blocks the main thread
**Plans**: 1 plan

Plans:
- [x] 03-01-PLAN.md - Move IPC methods to IO dispatcher with suspend functions

### Phase 4: Relay Loop Resolution
**Goal**: Relay selection completes without looping under any conditions
**Plans**: 1 plan

Plans:
- [x] 04-01-PLAN.md - Change StateFlow sharing from Eagerly to WhileSubscribed(5000L)

### Phase 5: Memory Optimization
**Goal**: App runs indefinitely without OOM crashes
**Plans**: 3 plans

Plans:
- [ ] 05-01-PLAN.md - Add memory profiling infrastructure (tracemalloc + build flag)
- [ ] 05-02-PLAN.md - Identify and fix memory leaks
- [ ] 05-03-PLAN.md - Verify fixes with extended runtime testing

### Phase 6: Native Stability Verification
**Goal**: Confirm MapLibre native crashes are resolved by memory optimization
**Plans**: 1 plan

Plans:
- [ ] 06-01: Verify native stability after memory fix

</details>

### v0.10.0 Voice Messages

**Milestone Goal:** Add voice message recording, sending, and inline playback to conversations, using Opus encoding via LXST-kt and LXMF FIELD_AUDIO transport.

- [x] **Phase 7: Protocol and Transport Foundation** - Wire format, field collision resolution, send/receive pipeline
- [ ] **Phase 8: Recording and Send Path** - VoiceRecorder with Opus encoding, wired through to LXMF send
- [ ] **Phase 9: Playback and Audio Bubble** - Opus decode, inline voice message bubble with waveform playback
- [ ] **Phase 10: Recording UI and Edge Cases** - Hold-to-record gesture polish and interruption handling

## Phase Details

### Phase 7: Protocol and Transport Foundation
**Goal**: Audio data can round-trip through the LXMF pipeline -- encoded, packed, sent, received, stored, and extracted
**Depends on**: Nothing (foundation for all voice message work)
**Requirements**: PROTO-01, PROTO-02, PROTO-03, PROTO-04, PROTO-05
**Success Criteria** (what must be TRUE):
  1. Opus VOICE_MEDIUM (8kbps, 24kHz mono) audio bytes can be packed into FIELD_AUDIO (0x07) and sent as an LXMF message
  2. A received LXMF message containing FIELD_AUDIO is stored in MessageEntity.fieldsJson with the audio data intact and extractable
  3. Messages with legacy location data in field 7 continue to work correctly (no misinterpretation as audio)
  4. Waveform amplitude data is stored alongside the encoded audio bytes and can be retrieved without re-decoding
  5. MessageMapper correctly identifies messages with audio fields and extracts audio metadata (duration, codec, waveform)
**Plans**: 3 plans

Plans:
- [x] 07-01-PLAN.md -- Send path plumbing: AIDL + ServiceBinder + Protocol + Python audio params and field 7 disambiguation
- [x] 07-02-PLAN.md -- Kotlin extraction: MessageMapper audio detection/extraction + MessageUi audio fields
- [x] 07-03-PLAN.md -- ViewModel wiring: buildFieldsJson field 7 support + end-to-end build verification

### Phase 8: Recording and Send Path
**Goal**: User can record a voice message from the chat screen and send it through the LXMF pipeline
**Depends on**: Phase 7 (wire format and transport must be defined)
**Requirements**: REC-01, REC-05, REC-07, EDGE-01, EDGE-07
**Success Criteria** (what must be TRUE):
  1. User can hold the mic button to record audio, and releasing sends the voice message to the conversation partner
  2. Recording automatically stops and sends at 30 seconds
  3. Accidental taps shorter than 300ms are silently discarded (no message sent)
  4. RECORD_AUDIO permission is requested on first recording attempt (recording does not start without permission)
  5. Other app audio (notifications, media) is silenced during recording via AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
**Plans**: TBD

Plans:
- [ ] 08-01: TBD
- [ ] 08-02: TBD

### Phase 9: Playback and Audio Bubble
**Goal**: Received voice messages render inline as audio bubbles and play back with waveform progress
**Depends on**: Phase 7 (audio extraction from fieldsJson), Phase 8 (need sent messages to test with)
**Requirements**: PLAY-01, PLAY-02, PLAY-03, PLAY-04, PLAY-05, PLAY-06, PLAY-07, EDGE-02, EDGE-06
**Success Criteria** (what must be TRUE):
  1. Voice messages appear inline in the conversation as audio bubbles with a play/pause button, waveform visualization, and mm:ss duration
  2. Tapping play decodes and plays the audio; the waveform animates left-to-right as a progress indicator during playback
  3. Playing a new voice message automatically stops any currently playing message
  4. Unplayed voice messages are visually distinct from played ones (indicator disappears after first play)
  5. Corrupt or incomplete audio data renders as an error state in the bubble without crashing the app
**Plans**: TBD

Plans:
- [ ] 09-01: TBD
- [ ] 09-02: TBD

### Phase 10: Recording UI and Edge Cases
**Goal**: Recording interaction is polished with familiar gesture controls, and all interruption edge cases are handled gracefully
**Depends on**: Phase 8 (recording infrastructure must work), Phase 9 (playback must work for end-to-end verification)
**Requirements**: REC-02, REC-03, REC-04, REC-06, EDGE-03, EDGE-04, EDGE-05
**Success Criteria** (what must be TRUE):
  1. Mic button appears in place of the send button when the text field is empty, and the send button reappears when text is entered
  2. User can cancel an in-progress recording by sliding their finger left (slide-to-cancel gesture with visual affordance)
  3. A visible timer counts up from 0:00 during recording, and a pulsing animation indicates active recording
  4. An incoming phone call during recording stops and discards the recording without crashing or sending a partial message
  5. App being backgrounded during recording stops and discards the recording; headphone disconnect during playback pauses playback
**Plans**: TBD

Plans:
- [ ] 10-01: TBD
- [ ] 10-02: TBD

## Progress

**Execution Order:** Phases execute in numeric order: 7 -> 8 -> 9 -> 10

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 7. Protocol and Transport Foundation | 3/3 | Complete | 2026-02-24 |
| 8. Recording and Send Path | 0/TBD | Not started | - |
| 9. Playback and Audio Bubble | 0/TBD | Not started | - |
| 10. Recording UI and Edge Cases | 0/TBD | Not started | - |
