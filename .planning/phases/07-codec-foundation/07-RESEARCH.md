# Phase 7: Codec Foundation - Research

**Researched:** 2026-02-04
**Domain:** Audio codec implementation (Opus, Codec2) in Kotlin
**Confidence:** MEDIUM

## Summary

This research investigates implementing Opus and Codec2 codecs in Kotlin with wire-compatible output matching Python LXST's byte format. The Python LXST implementation uses `pyogg.OpusEncoder/OpusDecoder` for Opus and `pycodec2.Codec2` for Codec2, with specific packet header formats and resampling via pydub's AudioSegment.

The Android ecosystem has multiple Opus JNI implementations but limited Codec2 options. The recommended libraries (`io.rebble.cobble:opus-jni` and `com.ustadmobile.codec2:codec2-android`) are not yet verified to exist at those exact Maven coordinates, requiring validation during implementation.

**Primary recommendation:** Start with wire format verification - implement Python→Kotlin codec test harness to validate byte-identical output before building the full codec API surface.

**Key findings:**
- Python LXST uses single-byte codec headers (0x00-0x06 for Codec2 modes, raw Opus frames have no header)
- Opus profiles determine channels, sample rate, application mode (voip/audio), and bitrate ceiling
- Codec2 operates at 8kHz input; Opus at profile-specific rates (8k-48k)
- Resampling is critical: 48kHz Android AudioRecord → codec-specific rates
- FloatArray sample format bridges Kotlin ShortArray (int16) and codec expectations

## Standard Stack

### Core Libraries

| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| android-opus-codec (JNI) | Latest | Opus encode/decode | C++ libopus 1.3.1+ wrapper with Kotlin-friendly API |
| UstadMobile Codec2-Android | v0.8+ | Codec2 encode/decode | JNI wrapper for FreeDV Codec2 library |

**Status:** Library coordinates need verification. The Python codebase uses:
- `pyogg.OpusEncoder/OpusDecoder` (wraps libopus)
- `pycodec2.Codec2` (wraps codec2 C library)

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| pydub AudioSegment | N/A (Python) | Resampling reference | Python LXST uses this; Kotlin needs equivalent |
| Android AudioTrack/Record | Platform | Sample rate handling | Built-in Android audio; already used in KotlinAudioBridge |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JNI wrappers | Pure Kotlin codecs | No production-ready Kotlin Opus/Codec2 implementations exist; C libraries are reference implementations |
| pydub-style resampling | Android SRC | Android's Kaiser-windowed sinc resampler is high-quality but less documented than pydub |
| FloatArray bridge | Direct short[] | Python uses float32 numpy arrays; FloatArray matches conceptually for API parity |

**Installation:**
```gradle
// Requires Maven coordinate verification
implementation("io.rebble.cobble:opus-jni:VERSION")
implementation("com.ustadmobile.codec2:codec2-android:VERSION")
```

**Confidence:** LOW - Maven coordinates unverified. Alternative: vendor libraries or build from GitHub sources.

## Architecture Patterns

### Recommended Project Structure
```
reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/
├── codec/
│   ├── Codec.kt              # Base interface (matches Python Codec.py API)
│   ├── OpusCodec.kt          # Opus implementation
│   ├── Codec2Codec.kt        # Codec2 implementation
│   ├── CodecProfile.kt       # Profile constants (0x00-0x08 for Opus, 700-3200 for Codec2)
│   └── CodecResampler.kt     # Sample rate conversion utilities
├── bridge/
│   ├── KotlinAudioBridge.kt  # Existing (handles AudioTrack/AudioRecord)
│   └── KotlinAudioFilters.kt # Existing (AGC, bandpass filters)
└── test/
    └── codec/
        ├── CodecWireFormatTest.kt  # Verify byte-identical output vs Python
        └── CodecResamplingTest.kt  # Verify resampling quality
```

### Pattern 1: Codec Interface
**What:** Match Python LXST's Codec base class API exactly
**When to use:** All codec implementations
**Example:**
```kotlin
// Based on Python LXST/Codecs/Codec.py and Opus.py/Codec2.py
interface Codec {
    val preferredSampleRate: Int?
    val frameQuantaMs: Float?

    fun encode(frame: FloatArray): ByteArray
    fun decode(frameBytes: ByteArray): FloatArray

    // Opus-specific
    fun setProfile(profile: Int)  // 0x00-0x08

    // Codec2-specific
    fun setMode(mode: Int)  // 700, 1200, 1300, 1400, 1600, 2400, 3200
}
```

### Pattern 2: Wire Format Compliance
**What:** Single-byte header + encoded data packet format
**When to use:** All codec encode operations
**Example:**
```kotlin
// Python LXST Codec2.py line 85: return self.mode_header+encoded
class Codec2Codec : Codec {
    private val MODE_HEADERS = mapOf(
        700 to 0x00.toByte(),
        1200 to 0x01.toByte(),
        1300 to 0x02.toByte(),
        1400 to 0x03.toByte(),
        1600 to 0x04.toByte(),
        2400 to 0x05.toByte(),
        3200 to 0x06.toByte()
    )

    override fun encode(frame: FloatArray): ByteArray {
        // Convert float32 [-1,1] to int16
        val int16Samples = frame.map { (it * 32767f).toInt().toShort() }.toShortArray()

        // Resample to 8kHz if needed (Codec2 input requirement)
        val resampled = resampleTo8kHz(int16Samples)

        // Encode via JNI wrapper
        val encoded = codec2Jni.encode(resampled)

        // Prepend mode header byte (matches Python line 85)
        return byteArrayOf(MODE_HEADERS[currentMode]!!) + encoded
    }
}
```

### Pattern 3: Float/Short Sample Bridge
**What:** Convert between FloatArray (API) and ShortArray (JNI codecs)
**When to use:** All encode/decode operations
**Example:**
```kotlin
// Python LXST uses: input_samples = frame*TYPE_MAP_FACTOR (32767)
//                   input_samples = input_samples.astype(np.int16)
private fun floatToShort(samples: FloatArray): ShortArray {
    return ShortArray(samples.size) { i ->
        (samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }
}

private fun shortToFloat(samples: ShortArray): FloatArray {
    return FloatArray(samples.size) { i ->
        samples[i] / 32767f
    }
}
```

### Pattern 4: Resampling Pipeline
**What:** Convert between capture rate (48kHz) and codec rates (8k-48k)
**When to use:** Before encoding, after decoding
**Example:**
```kotlin
// Python LXST Codec.py lines 27-51: resample_bytes using pydub AudioSegment
class CodecResampler {
    fun resample(
        samples: ShortArray,
        fromRate: Int,
        toRate: Int,
        channels: Int = 1
    ): ShortArray {
        // Use Android's built-in resampler (Kaiser-windowed sinc)
        // or implement linear interpolation for simple cases

        if (fromRate == toRate) return samples

        val ratio = toRate.toDouble() / fromRate
        val outputSize = (samples.size * ratio).toInt()
        val output = ShortArray(outputSize)

        // High-quality resampling via Android AudioTrack.setPlaybackRate
        // OR manual windowed-sinc implementation
        // Priority: quality over CPU (as per CONTEXT.md decisions)

        return output
    }
}
```

### Anti-Patterns to Avoid

- **Don't allocate per-frame:** Pre-allocate resampling and conversion buffers (matches CONTEXT.md decision)
- **Don't skip header byte:** Codec2 MUST prepend mode header; Opus frames are raw (no header in LXST)
- **Don't assume sample rates:** Always resample to codec requirements (8kHz for Codec2, profile-specific for Opus)
- **Don't use different constants:** Profile/mode hex values MUST match Python exactly for wire compatibility

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Opus encoding | Custom Opus encoder | libopus via JNI | Opus spec is complex (RFC 6716); reference implementation is battle-tested |
| Codec2 encoding | Custom Codec2 port | FreeDV Codec2 via JNI | Codec2 is C-optimized for low-bitrate voice; porting loses performance |
| Sample rate conversion | Naive linear interpolation | Android SRC or windowed-sinc | Quality priority (CONTEXT.md); Kaiser windowed-sinc is proven high-quality |
| Audio format validation | Manual checks | Existing KotlinAudioBridge patterns | Already handles sample format, channel count, buffer sizing |

**Key insight:** Opus and Codec2 are reference C implementations optimized over years. JNI overhead is negligible vs encoding/decoding CPU cost. Python LXST uses the same C libraries via ctypes/Cython wrappers.

## Common Pitfalls

### Pitfall 1: Wire Format Mismatch
**What goes wrong:** Kotlin-encoded packets can't be decoded by Python LXST or vice versa
**Why it happens:** Codec2 header byte omitted, wrong endianness, or padding differences
**How to avoid:**
- Write test harness that encodes same audio in Python and Kotlin, compare byte-by-byte
- Verify Codec2 header byte matches Python's `MODE_HEADERS` dict exactly
- Test with actual LXST Python decoder to confirm interop
**Warning signs:**
- "Codec header not recognized" errors in Python
- Silent audio after decode despite non-zero input

### Pitfall 2: Sample Rate Confusion
**What goes wrong:** Audio is garbled, too fast, or too slow after decode
**Why it happens:** Forgot to resample before encoding, or wrong rate after decode
**How to avoid:**
- Codec2 ALWAYS requires 8kHz input (Python `INPUT_RATE = 8000`)
- Opus requires profile-specific rate (8k/12k/24k/48k from `profile_samplerate()`)
- Document rate conversions clearly in codec class
**Warning signs:**
- Audio plays at wrong pitch
- Codec2 encode fails with "wrong buffer size"

### Pitfall 3: Opus Profile Bitrate Ceiling
**What goes wrong:** Encoded packets exceed expected size, causing network issues
**Why it happens:** `set_max_bytes_per_frame()` not called or calculated wrong
**How to avoid:**
- Python LXST: `max_bytes_per_frame = ceil((bitrate_ceiling/8)*(frame_duration_ms/1000))`
- Call this in Kotlin before each encode based on frame duration
- Verify Opus encoder respects the limit
**Warning signs:**
- Packets larger than expected for profile
- Network congestion on low-bandwidth links

### Pitfall 4: JNI Memory Management
**What goes wrong:** Native crashes, memory leaks, or corrupted audio
**Why it happens:** Forgetting to release encoder/decoder, or passing invalid pointers
**How to avoid:**
- Wrap JNI codecs in Kotlin class with `close()` method (AutoCloseable)
- Use `try-with-resources` pattern or explicit cleanup
- Never pass Kotlin arrays directly; copy to JNI-accessible buffers
**Warning signs:**
- App crashes in native code (`SIGSEGV`)
- Memory usage grows over multiple calls

### Pitfall 5: Float/Short Conversion Clipping
**What goes wrong:** Audio distorts or clips during conversion
**Why it happens:** Float values outside [-1.0, 1.0] cause overflow when converting to int16
**How to avoid:**
- Always clamp: `(value * 32767).coerceIn(-32768, 32767)`
- Match Python exactly: `TYPE_MAP_FACTOR = np.iinfo("int16").max` (32767)
- Consider AGC before codec (KotlinAudioFilters already does this)
**Warning signs:**
- Crackling or harsh distortion in decoded audio
- Silent output when input had audio

## Code Examples

Verified patterns from Python LXST and Android best practices:

### Codec2 Encoding (Wire-Compatible)
```kotlin
// Source: Python LXST Codecs/Codec2.py lines 54-85
class Codec2Codec(private val mode: Int = 2400) : Codec, AutoCloseable {
    private val codec2 = Codec2Jni(mode)  // Hypothetical JNI wrapper

    private val MODE_HEADERS = mapOf(
        700 to 0x00.toByte(),
        1200 to 0x01.toByte(),
        1300 to 0x02.toByte(),
        1400 to 0x03.toByte(),
        1600 to 0x04.toByte(),
        2400 to 0x05.toByte(),
        3200 to 0x06.toByte()
    )

    override fun encode(frame: FloatArray): ByteArray {
        // Convert float32 to int16 (matches Python line 60-61)
        val int16 = floatToShort(frame)

        // Resample to 8kHz if needed (Python lines 64-67)
        val resampled = if (sourceRate != 8000) {
            resampleTo8kHz(int16)
        } else {
            int16
        }

        // Encode in chunks of samples_per_frame (Python lines 69-79)
        val spf = codec2.samplesPerFrame()
        val nFrames = resampled.size / spf
        val encoded = ByteArray(nFrames * codec2.bytesPerFrame())

        for (i in 0 until nFrames) {
            val frameStart = i * spf
            val frameEnd = frameStart + spf
            val chunk = resampled.sliceArray(frameStart until frameEnd)
            val encodedChunk = codec2.encode(chunk)
            encodedChunk.copyInto(encoded, i * codec2.bytesPerFrame())
        }

        // Prepend mode header (Python line 85: return self.mode_header+encoded)
        return byteArrayOf(MODE_HEADERS[mode]!!) + encoded
    }

    override fun close() {
        codec2.release()
    }
}
```

### Opus Profile Configuration
```kotlin
// Source: Python LXST Codecs/Opus.py lines 43-93
object OpusProfiles {
    const val PROFILE_VOICE_LOW = 0x00
    const val PROFILE_VOICE_MEDIUM = 0x01
    const val PROFILE_VOICE_HIGH = 0x02
    const val PROFILE_VOICE_MAX = 0x03
    const val PROFILE_AUDIO_MIN = 0x04
    const val PROFILE_AUDIO_LOW = 0x05
    const val PROFILE_AUDIO_MEDIUM = 0x06
    const val PROFILE_AUDIO_HIGH = 0x07
    const val PROFILE_AUDIO_MAX = 0x08

    fun channels(profile: Int): Int = when (profile) {
        PROFILE_VOICE_LOW, PROFILE_VOICE_MEDIUM, PROFILE_VOICE_HIGH -> 1
        PROFILE_VOICE_MAX, PROFILE_AUDIO_MEDIUM, PROFILE_AUDIO_HIGH, PROFILE_AUDIO_MAX -> 2
        PROFILE_AUDIO_MIN, PROFILE_AUDIO_LOW -> 1
        else -> throw IllegalArgumentException("Unknown profile: $profile")
    }

    fun sampleRate(profile: Int): Int = when (profile) {
        PROFILE_VOICE_LOW, PROFILE_AUDIO_MIN -> 8000
        PROFILE_AUDIO_LOW -> 12000
        PROFILE_VOICE_MEDIUM, PROFILE_AUDIO_MEDIUM -> 24000
        PROFILE_VOICE_HIGH, PROFILE_VOICE_MAX, PROFILE_AUDIO_HIGH, PROFILE_AUDIO_MAX -> 48000
        else -> throw IllegalArgumentException("Unknown profile: $profile")
    }

    fun application(profile: Int): String = when (profile) {
        in PROFILE_VOICE_LOW..PROFILE_VOICE_MAX -> "voip"
        in PROFILE_AUDIO_MIN..PROFILE_AUDIO_MAX -> "audio"
        else -> throw IllegalArgumentException("Unknown profile: $profile")
    }

    fun bitrateCeiling(profile: Int): Int = when (profile) {
        PROFILE_VOICE_LOW -> 6000
        PROFILE_VOICE_MEDIUM -> 8000
        PROFILE_VOICE_HIGH -> 16000
        PROFILE_VOICE_MAX -> 32000
        PROFILE_AUDIO_MIN -> 8000
        PROFILE_AUDIO_LOW -> 14000
        PROFILE_AUDIO_MEDIUM -> 28000
        PROFILE_AUDIO_HIGH -> 56000
        PROFILE_AUDIO_MAX -> 128000
        else -> throw IllegalArgumentException("Unknown profile: $profile")
    }
}
```

### Resampling with Quality Priority
```kotlin
// Source: Python LXST Codecs/Codec.py lines 27-51 (pydub AudioSegment.set_frame_rate)
// Android equivalent using built-in high-quality resampler
class CodecResampler {
    // Pre-allocated buffers for resampling (CONTEXT.md: pre-allocate buffers)
    private var bufferIn: ShortArray? = null
    private var bufferOut: ShortArray? = null

    fun resample(
        input: ShortArray,
        fromRate: Int,
        toRate: Int,
        channels: Int = 1
    ): ShortArray {
        if (fromRate == toRate) return input

        // Calculate output size
        val ratio = toRate.toDouble() / fromRate
        val outputSize = (input.size * ratio).toInt()

        // Allocate/reuse buffer
        if (bufferOut == null || bufferOut!!.size < outputSize) {
            bufferOut = ShortArray(outputSize)
        }
        val output = bufferOut!!

        // High-quality windowed-sinc resampling (Android uses Kaiser window)
        // This matches pydub's quality, which uses ffmpeg's resampler
        // Priority: quality over CPU (CONTEXT.md decision)

        // Option 1: Use Android MediaCodec for resampling (heavyweight but guaranteed quality)
        // Option 2: Implement windowed-sinc manually (lightweight, requires careful implementation)
        // Option 3: Use Oboe resampler (if available, production-grade)

        // Placeholder for windowed-sinc implementation
        for (i in 0 until outputSize) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx

            // Linear interpolation (minimum quality)
            // TODO: Replace with windowed-sinc for production
            val sample = if (srcIdx + 1 < input.size) {
                input[srcIdx] * (1 - frac) + input[srcIdx + 1] * frac
            } else {
                input[srcIdx].toDouble()
            }

            output[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }

        return output.sliceArray(0 until outputSize)
    }
}
```

### Wire Format Test Harness
```kotlin
// Verify byte-identical output vs Python LXST
class CodecWireFormatTest {
    @Test
    fun testCodec2WireFormat() {
        // Load test vector encoded by Python LXST
        val pythonEncoded = loadPythonTestVector("codec2_2400_test.bin")

        // Encode same audio in Kotlin
        val codec = Codec2Codec(mode = 2400)
        val input = loadTestAudio("test_audio_8khz.raw")  // 8kHz int16 PCM
        val kotlinEncoded = codec.encode(shortToFloat(input))

        // Compare byte-by-byte
        assertArrayEquals(
            "Codec2 output must be byte-identical to Python LXST",
            pythonEncoded,
            kotlinEncoded
        )

        // Verify header byte
        assertEquals(
            "Codec2 2400 header must be 0x05",
            0x05.toByte(),
            kotlinEncoded[0]
        )
    }

    @Test
    fun testOpusWireFormat() {
        // Opus has no header byte in LXST (raw Opus packets)
        val pythonEncoded = loadPythonTestVector("opus_voice_low_test.bin")

        val codec = OpusCodec(profile = OpusProfiles.PROFILE_VOICE_LOW)
        val input = loadTestAudio("test_audio_8khz.raw")
        val kotlinEncoded = codec.encode(shortToFloat(input))

        // Opus output may vary slightly due to encoder settings
        // Verify decode produces same audio instead
        val pythonDecoded = decodeWithPythonOpus(pythonEncoded)
        val kotlinDecoded = codec.decode(kotlinEncoded)

        assertAudioSimilar(pythonDecoded, kotlinDecoded, maxDiffDb = -40f)
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| PyJnius audio backend | Chaquopy + KotlinAudioBridge | 2024-2025 | LXST now uses Chaquopy-compatible bridge instead of PyJnius |
| Python CFFI filters | Kotlin native filters | 2024-2025 | <1ms latency vs 20-50ms in Python (KotlinAudioFilters.kt) |
| Pyjnius soundcard | ChaquopyAudioBackend | 2024-2025 | Drop-in replacement for LXST's Android audio access |
| Opus 1.3.1 | Opus 1.6 (Dec 2025) | Dec 2025 | New BWE module, 96kHz support, 24-bit API |

**Deprecated/outdated:**
- PyJnius: Incompatible with modern Chaquopy; replaced by direct Kotlin bridge
- Python-only codec wrapper: Too slow for real-time on Android; moving to Kotlin with JNI

**Current best practices:**
- Use Kotlin for audio pipeline (capture → filter → codec → network)
- Call Python LXST only for network layer (RNS/LXMF protocol)
- Pre-allocate buffers to avoid GC during calls (already done in KotlinAudioBridge)

## Open Questions

Things that couldn't be fully resolved:

1. **Maven Coordinates for Recommended Libraries**
   - What we know: `io.rebble.cobble:opus-jni` and `com.ustadmobile.codec2:codec2-android` were identified
   - What's unclear: These may not exist on Maven Central; may need to build from GitHub sources
   - Recommendation: Verify Maven availability first; if not found, add as git submodules and build locally

2. **Resampling Quality vs Performance**
   - What we know: CONTEXT.md prioritizes quality; Android has Kaiser-windowed sinc resampler; pydub uses ffmpeg
   - What's unclear: Android SRC performance impact on battery during long calls
   - Recommendation: Implement with Android SRC first (quality priority); profile battery usage; optimize if needed

3. **Opus Bitrate Enforcement**
   - What we know: Python calls `set_max_bytes_per_frame()` before each encode
   - What's unclear: How strictly Android Opus JNI libraries enforce this vs libopus guarantees
   - Recommendation: Test with network bandwidth monitoring; verify packets don't exceed profile ceilings

4. **Codec2 Mode Switching**
   - What we know: Python LXST allows mid-stream mode changes based on header byte
   - What's unclear: Whether Codec2 JNI wrapper supports reinitializing with new mode without full teardown
   - Recommendation: Implement mode switch as full codec recreate first; optimize later if needed

5. **Wire Format Test Vectors**
   - What we know: Need byte-identical output for Codec2; similar audio for Opus
   - What's unclear: Best way to generate test vectors (run Python LXST in test harness vs manual generation)
   - Recommendation: Use Python subprocess in Android instrumented test to generate reference vectors

## Sources

### Primary (HIGH confidence)
- [PyOgg Opus API Documentation](https://pyogg.readthedocs.io/en/latest/api/opus.html) - Verified Python Opus API
- [Android AOSP Sample Rate Conversion](https://source.android.com/docs/core/audio/src) - Android resampler details
- Python LXST source code (read locally):
  - `Codecs/Codec2.py` - Wire format (header bytes), resample usage
  - `Codecs/Opus.py` - Profile constants, bitrate ceilings, encode/decode logic
  - `Codecs/Codec.py` - Base class, resample_bytes implementation

### Secondary (MEDIUM confidence)
- [android-opus-codec GitHub](https://github.com/theeasiestway/android-opus-codec) - Android Opus JNI example
- [Codec2-Android GitHub](https://github.com/UstadMobile/Codec2-Android) - Codec2 JNI wrapper
- [LabyMod opus-jni](https://github.com/LabyMod/opus-jni) - Alternative Java Opus library
- [Android NDK Audio Sampling](https://developer.android.com/ndk/guides/audio/sampling-audio) - Best practices
- Existing Kotlin code (read locally):
  - `KotlinAudioBridge.kt` - Sample format patterns, buffer management
  - `KotlinAudioFilters.kt` - Float/Short conversion, filter pipeline
  - `CodecProfile.kt` - Profile constants (UI layer, needs codec layer equivalent)

### Tertiary (LOW confidence - needs verification)
- [pycodec2 GitHub](https://github.com/gregorias/pycodec2) - Python Codec2 wrapper (API not fully documented)
- [pycodec2 PyPI](https://pypi.org/project/pycodec2/) - Installation only, no API details
- Maven coordinates for `io.rebble.cobble:opus-jni` - Not verified to exist
- Maven coordinates for `com.ustadmobile.codec2:codec2-android` - Not verified to exist

## Metadata

**Confidence breakdown:**
- Standard stack: LOW - Maven coordinates unverified; may need to vendor or build from source
- Architecture: HIGH - Python LXST code provides exact wire format and API requirements
- Pitfalls: HIGH - Verified from Python implementation patterns and known Android JNI issues
- Resampling: MEDIUM - Android SRC quality confirmed; pydub equivalence requires testing

**Research date:** 2026-02-04
**Valid until:** 30 days (stable domain, but library coordinates need immediate verification)

**Critical next steps:**
1. Verify Maven coordinates or identify alternative distribution
2. Create wire format test harness with Python LXST subprocess
3. Implement CodecResampler with Android SRC and profile quality
4. Write Codec2 implementation first (simpler: single sample rate, single channel)
5. Write Opus implementation second (complex: 9 profiles, dynamic bitrate)
