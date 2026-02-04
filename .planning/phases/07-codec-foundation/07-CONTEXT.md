# Phase 7: Codec Foundation - Context

**Gathered:** 2026-02-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Implement Opus and Codec2 codecs in Kotlin that produce wire-compatible encoded packets. This includes the base Codec interface, resampling utilities, and all profile/mode configurations. Audio capture (LineSource), playback (LineSink), and network integration are separate phases.

</domain>

<decisions>
## Implementation Decisions

### Library selection
- Use `io.rebble.cobble:opus-jni` (android-opus-codec) for Opus encoding/decoding
- Use `com.ustadmobile.codec2:codec2-android` for Codec2 encoding/decoding
- No fallback — make recommended libraries work, block if issues arise
- Bundle native .so libraries in APK (not downloaded at runtime)

### Wire format compatibility
- **Decode-compatible required** — Kotlin packets must be decodable by Python decoders (and vice versa)
- Bit-identical encoding is NOT required (different encoder implementations produce different bytes)
- What matters: header bytes correct + valid packets decodable by any compliant decoder
- Document codec header byte format in code comments (0x00-0x06 for Codec2 modes, 0x01 for Opus)
- Packet format: header byte + encoded data (same as Python LXST)

### API surface design
- **Exact method name match** with Python LXST: `encode()`, `decode()`, `setProfile()`, `setMode()`
- Use `FloatArray` for audio samples (matches numpy float32 conceptually)
- Implement **all 9 Opus profiles** (VOICE_LOW through AUDIO_MAX)
- Implement **all 7 Codec2 modes** (700C through 3200)
- Profile constants use same hex values as Python (0x00-0x08 for Opus, 700-3200 for Codec2)

### Resampling approach
- Use Android's SRC (Kaiser-windowed sinc resampler) via AudioTrack/AudioRecord
- Prioritize audio quality over CPU efficiency
- Pre-allocate resampling buffers (no per-frame allocation)
- Reuse buffers to avoid GC pressure during calls

### Claude's Discretion
- Verification approach for decode-compatible output (cross-implementation decode test)
- Debugging strategy for decode failures
- Where resampling happens in the pipeline (in codec or before codec)

</decisions>

<specifics>
## Specific Ideas

- Match Python LXST's `Codecs/__init__.py` codec_header_byte() and codec_type() functions exactly
- Opus profiles determine: channels (1-2), samplerate (8k-48k), application (voip/audio), bitrate ceiling
- Codec2 modes determine: samples_per_frame, bytes_per_frame (from pycodec2 API)

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 07-codec-foundation*
*Context gathered: 2026-02-04*
