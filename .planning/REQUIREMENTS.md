# Requirements: v0.8.0 Kotlin LXST Audio Pipeline

## v0.8.0 Requirements

### Core Audio Pipeline

- [ ] **CODEC-01**: Kotlin Opus encoder/decoder with all 9 profiles (VOICE_LOW through AUDIO_MAX)
- [ ] **CODEC-02**: Kotlin Codec2 encoder/decoder with all 7 modes (700C through 3200)
- [ ] **CODEC-03**: Wire-compatible packet format (codec header byte + encoded data)
- [x] **SOURCE-01**: LineSource captures audio from microphone with configurable frame time
- [x] **SOURCE-02**: LineSource applies filters and gain before encoding
- [x] **SINK-01**: LineSink plays decoded audio with buffer management
- [x] **SINK-02**: LineSink handles underrun/overrun gracefully
- [x] **MIX-01**: Mixer combines multiple audio sources with gain control
- [x] **MIX-02**: Mixer supports mute/unmute for receive and transmit
- [x] **PIPE-01**: Pipeline orchestrates source → codec → sink flow
- [x] **GEN-01**: ToneSource generates dial/busy tones with ease-in/out

### Network Integration

- [ ] **NET-01**: Packetizer sends encoded frames to Python Reticulum
- [ ] **NET-02**: LinkSource receives encoded frames from Python Reticulum
- [ ] **NET-03**: SignallingReceiver handles inband call signalling
- [ ] **BRIDGE-01**: Python↔Kotlin bridge passes only encoded packets (<100 bytes)
- [ ] **BRIDGE-02**: Bridge latency under 5ms for packet transfer

### Telephony

- [ ] **TEL-01**: All 8 quality profiles work (ULBW through ULL)
- [ ] **TEL-02**: Outgoing calls connect with dial tone feedback
- [ ] **TEL-03**: Incoming calls ring with configurable ringtone
- [ ] **TEL-04**: Profile switching works mid-call
- [ ] **TEL-05**: Mute/unmute controls work correctly

### Quality

- [ ] **QUAL-01**: Voice calls on LAN have no audible pops/delays
- [x] **QUAL-02**: Filter latency under 1ms (already achieved in KotlinAudioFilters)
- [ ] **QUAL-03**: Encode/decode latency under 5ms per frame
- [ ] **QUAL-04**: End-to-end audio latency under 200ms on LAN

## File-by-File Implementation Breakdown

### 1. Codecs/Codec.kt (from Codec.py — 62 lines)

**Classes:**
- `Codec` (abstract base class)
- `CodecError` (exception)
- `Null` (passthrough codec)

**Methods:**
```kotlin
// Codec (abstract)
- var preferredSamplerate: Int?
- var frameQuantaMs: Float?
- var frameMaxMs: Float?
- var validFrameMs: List<Float>?
- var source: LocalSource?
- var sink: Sink?

// Null : Codec
- fun encode(frame: FloatArray): ByteArray  // passthrough
- fun decode(frameBytes: ByteArray): FloatArray  // passthrough

// Utility functions
- fun resampleBytes(sampleBytes: ByteArray, bitdepth: Int, channels: Int, inputRate: Int, outputRate: Int): ByteArray
- fun resample(inputSamples: FloatArray, bitdepth: Int, channels: Int, inputRate: Int, outputRate: Int): FloatArray
```

**Lines:** ~80 Kotlin

---

### 2. Codecs/Opus.kt (from Opus.py — 167 lines)

**Classes:**
- `Opus` : `Codec`

**Constants:**
```kotlin
FRAME_QUANTA_MS = 2.5f
FRAME_MAX_MS = 60f
VALID_FRAME_MS = listOf(2.5f, 5f, 10f, 20f, 40f, 60f)

// Profiles
PROFILE_VOICE_LOW = 0x00
PROFILE_VOICE_MEDIUM = 0x01
PROFILE_VOICE_HIGH = 0x02
PROFILE_VOICE_MAX = 0x03
PROFILE_AUDIO_MIN = 0x04
PROFILE_AUDIO_LOW = 0x05
PROFILE_AUDIO_MEDIUM = 0x06
PROFILE_AUDIO_HIGH = 0x07
PROFILE_AUDIO_MAX = 0x08
```

**Methods:**
```kotlin
- constructor(profile: Int = PROFILE_VOICE_LOW)
- fun setProfile(profile: Int)
- fun updateBitrate(frameDurationMs: Float)
- override fun encode(frame: FloatArray): ByteArray
- override fun decode(frameBytes: ByteArray): FloatArray

// Static profile helpers
companion object {
    fun profileChannels(profile: Int): Int
    fun profileSamplerate(profile: Int): Int
    fun profileApplication(profile: Int): String
    fun profileBitrateCeiling(profile: Int): Int
    fun maxBytesPerFrame(bitrateCeiling: Int, frameDurationMs: Float): Int
}
```

**Lines:** ~200 Kotlin

---

### 3. Codecs/Codec2.kt (from Codec2.py — 121 lines)

**Classes:**
- `Codec2` : `Codec`

**Constants:**
```kotlin
CODEC2_700C = 700
CODEC2_1200 = 1200
CODEC2_1300 = 1300
CODEC2_1400 = 1400
CODEC2_1600 = 1600
CODEC2_2400 = 2400
CODEC2_3200 = 3200

INPUT_RATE = 8000
OUTPUT_RATE = 8000
FRAME_QUANTA_MS = 40f

MODE_HEADERS = mapOf(
    CODEC2_700C to 0x00,
    CODEC2_1200 to 0x01,
    ...
)
```

**Methods:**
```kotlin
- constructor(mode: Int = CODEC2_2400)
- fun setMode(mode: Int)
- override fun encode(frame: FloatArray): ByteArray
- override fun decode(frameBytes: ByteArray): FloatArray
```

**Lines:** ~150 Kotlin

---

### 4. Sources.kt (from Sources.py — 361 lines)

**Classes:**
- `Source` (interface)
- `LocalSource` (interface)
- `RemoteSource` (interface)
- `LineSource` : `LocalSource`
- `OpusFileSource` : `LocalSource` (optional, for ringtones)
- `Loopback` : `LocalSource`, `LocalSink`

**LineSource Methods:**
```kotlin
- constructor(
    preferredDevice: String? = null,
    targetFrameMs: Int = DEFAULT_FRAME_MS,
    codec: Codec? = null,
    sink: Sink? = null,
    filters: List<Filter>? = null,
    gain: Float = 0f,
    easeIn: Float = 0f,
    skip: Float = 0f
)
- var codec: Codec?  // setter configures samples_per_frame
- fun start()
- fun stop()
- private fun ingestJob()  // recording thread

companion object {
    fun linearGain(gainDb: Float): Float
}
```

**Lines:** ~300 Kotlin (excluding backends, already in KotlinAudioBridge)

---

### 5. Sinks.kt (from Sinks.py — 348 lines)

**Classes:**
- `Sink` (interface)
- `LocalSink` (interface)
- `RemoteSink` (interface)
- `LineSink` : `LocalSink`
- `OpusFileSink` : `LocalSink` (optional)

**LineSink Methods:**
```kotlin
- constructor(
    preferredDevice: String? = null,
    autodigest: Boolean = true,
    lowLatency: Boolean = false
)
- fun canReceive(fromSource: Source? = null): Boolean
- fun handleFrame(frame: FloatArray, source: Source? = null)
- fun start()
- fun stop()
- fun enableLowLatency()
- private fun digestJob()  // playback thread
```

**Lines:** ~300 Kotlin

---

### 6. Mixer.kt (from Mixer.py — 177 lines)

**Classes:**
- `Mixer` : `LocalSource`, `LocalSink`

**Methods:**
```kotlin
- constructor(
    targetFrameMs: Int = 40,
    samplerate: Int? = null,
    codec: Codec? = null,
    sink: Sink? = null,
    gain: Float = 0f
)
- fun start()
- fun stop()
- fun setGain(gain: Float?)
- fun mute(mute: Boolean = true)
- fun unmute(unmute: Boolean = true)
- fun setSourceMaxFrames(source: Source, maxFrames: Int)
- fun canReceive(fromSource: Source): Boolean
- fun handleFrame(frame: ByteArray, source: Source, decoded: Boolean = false)
- private val mixingGain: Float
- private fun mixerJob()  // mixing thread
- var codec: Codec?
- var source: Source?
- var sink: Sink?
```

**Lines:** ~200 Kotlin

---

### 7. Network.kt (from Network.py — 145 lines)

**Classes:**
- `SignallingReceiver`
- `Packetizer` : `RemoteSink`
- `LinkSource` : `RemoteSource`, `SignallingReceiver`

**SignallingReceiver Methods:**
```kotlin
- constructor(proxy: SignallingReceiver? = null)
- fun handleSignallingFrom(source: Source)
- fun signallingReceived(signals: List<Int>, source: Any?)
- fun signal(signal: Int, destination: Any, immediate: Boolean = true)
- fun packet(data: ByteArray, packet: Any, unpacked: Map<Any, Any>? = null)
```

**Packetizer Methods:**
```kotlin
- constructor(destination: Any, failureCallback: (() -> Unit)? = null)
- fun handleFrame(frame: ByteArray, source: Source? = null)
- fun start()
- fun stop()
```

**LinkSource Methods:**
```kotlin
- constructor(link: Any, signallingReceiver: SignallingReceiver, sink: Sink? = null)
- fun packet(data: ByteArray, packet: Any)  // receive callback
- fun start()
- fun stop()
```

**Lines:** ~180 Kotlin

---

### 8. Pipeline.kt (from Pipeline.py — 58 lines)

**Classes:**
- `PipelineError` (exception)
- `Pipeline`

**Methods:**
```kotlin
- constructor(source: Source, codec: Codec, sink: Sink, processor: Any? = null)
- var codec: Codec
- val sink: Sink
- val running: Boolean
- fun start()
- fun stop()
```

**Lines:** ~70 Kotlin

---

### 9. Generators.kt (from Generators.py — 134 lines)

**Classes:**
- `ToneSource` : `LocalSource`

**Methods:**
```kotlin
- constructor(
    frequency: Float = DEFAULT_FREQUENCY,
    gain: Float = 0.1f,
    ease: Boolean = true,
    easeTimeMs: Float = EASE_TIME_MS,
    targetFrameMs: Int = DEFAULT_FRAME_MS,
    codec: Codec? = null,
    sink: Sink? = null,
    channels: Int = 1
)
- var codec: Codec?
- var gain: Float
- val running: Boolean
- fun start()
- fun stop()
- private fun generate(): FloatArray
- private fun generateJob()  // generation thread
```

**Lines:** ~150 Kotlin

---

### 10. Filters.kt — ALREADY DONE (KotlinAudioFilters.kt — 303 lines)

**Existing Classes:**
- `HighPassState`, `LowPassState`, `AGCState`
- `VoiceFilterChain`

**Existing Methods:**
- `applyHighPass()`
- `applyLowPass()`
- `applyAGC()`
- `VoiceFilterChain.process()`

**Status:** ✅ Complete — just need to expose as `Filter` interface

---

### 11. Telephony.kt (from Primitives/Telephony.py — 732 lines)

**Classes:**
- `Profiles` (object)
- `Signalling` (object)
- `Telephone` : `SignallingReceiver`

**Profiles Methods:**
```kotlin
object Profiles {
    const val BANDWIDTH_ULTRA_LOW = 0x10
    const val BANDWIDTH_VERY_LOW = 0x20
    const val BANDWIDTH_LOW = 0x30
    const val QUALITY_MEDIUM = 0x40
    const val QUALITY_HIGH = 0x50
    const val QUALITY_MAX = 0x60
    const val LATENCY_LOW = 0x70
    const val LATENCY_ULTRA_LOW = 0x80

    fun availableProfiles(): List<Int>
    fun profileIndex(profile: Int): Int?
    fun profileName(profile: Int): String
    fun profileAbbreviation(profile: Int): String
    fun getCodec(profile: Int): Codec
    fun getFrameTime(profile: Int): Int
    fun nextProfile(profile: Int): Int?
}
```

**Signalling Constants:**
```kotlin
object Signalling {
    const val STATUS_BUSY = 0x00
    const val STATUS_REJECTED = 0x01
    const val STATUS_CALLING = 0x02
    const val STATUS_AVAILABLE = 0x03
    const val STATUS_RINGING = 0x04
    const val STATUS_CONNECTING = 0x05
    const val STATUS_ESTABLISHED = 0x06
    const val PREFERRED_PROFILE = 0xFF
}
```

**Telephone Methods:**
```kotlin
- constructor(
    identity: Any,
    ringTime: Int = RING_TIME,
    waitTime: Int = WAIT_TIME,
    autoAnswer: Float? = null,
    allowed: Int = ALLOW_ALL,
    receiveGain: Float = 0f,
    transmitGain: Float = 0f
)
- fun teardown()
- fun announce(attachedInterface: Any? = null)
- fun setAllowed(allowed: Any)
- fun setBlocked(blocked: List<ByteArray>?)
- fun setConnectTimeout(timeout: Int)
- fun setAnnounceInterval(interval: Int)
- fun setRingingCallback(callback: (Any) -> Unit)
- fun setEstablishedCallback(callback: (Any) -> Unit)
- fun setEndedCallback(callback: (Any?) -> Unit)
- fun setBusyCallback(callback: (Any?) -> Unit)
- fun setRejectedCallback(callback: (Any?) -> Unit)
- fun setSpeaker(device: String?)
- fun setMicrophone(device: String?)
- fun setRinger(device: String?)
- fun setRingtone(path: String, gain: Float = 0f)
- fun setBusyToneTime(seconds: Float = 4.25f)
- fun enableAgc(enable: Boolean = true)
- fun disableAgc(disable: Boolean = true)
- fun setLowLatencyOutput(enabled: Boolean)
- fun setBusy(busy: Boolean)
- val busy: Boolean
- val activeProfile: Int?
- val receiveMuted: Boolean
- val transmitMuted: Boolean
- fun signal(signal: Int, link: Any)
- fun answer(identity: Any): Boolean
- fun hangup(reason: Int? = null)
- fun muteReceive(mute: Boolean = true)
- fun unmuteReceive(unmute: Boolean = true)
- fun muteTransmit(mute: Boolean = true)
- fun unmuteTransmit(unmute: Boolean = true)
- fun setReceiveGain(gain: Float = 0f)
- fun setTransmitGain(gain: Float = 0f)
- fun switchProfile(profile: Int?, fromSignalling: Boolean = false)
- fun call(identity: Any, profile: Int? = null)
- override fun signallingReceived(signals: List<Int>, source: Any?)

// Private methods
- private fun jobs()
- private fun isAllowed(remoteIdentity: Any): Boolean
- private fun timeoutIncomingCallAt(call: Any, timeout: Long)
- private fun timeoutOutgoingCallAt(call: Any, timeout: Long)
- private fun timeoutOutgoingEstablishmentAt(call: Any, timeout: Long)
- private fun incomingLinkEstablished(link: Any)
- private fun callerIdentified(link: Any, identity: Any)
- private fun linkClosed(link: Any)
- private fun selectCallProfile(profile: Int?)
- private fun selectCallCodecs(profile: Int?)
- private fun selectCallFrameTime(profile: Int?)
- private fun resetDiallingPipelines()
- private fun prepareDiallingPipelines()
- private fun activateRingTone()
- private fun playBusyTone()
- private fun activateDialTone()
- private fun enableDialTone()
- private fun muteDialTone()
- private fun disableDialTone()
- private fun reconfigureTransmitPipeline()
- private fun openPipelines(identity: Any)
- private fun packetizerFailure()
- private fun startPipelines()
- private fun stopPipelines()
- private fun outgoingLinkEstablished(link: Any)
- private fun outgoingLinkClosed(link: Any)
```

**Lines:** ~800 Kotlin

---

### 12. Python Bridge (NEW — call_manager.py integration)

**New Classes:**
- `KotlinLxstBridge` — Exposes Kotlin audio pipeline to Python

**Methods:**
```kotlin
// Called from Python
@JvmStatic fun startCall(profile: Int, remoteIdentity: ByteArray)
@JvmStatic fun endCall()
@JvmStatic fun muteTransmit(mute: Boolean)
@JvmStatic fun muteReceive(mute: Boolean)
@JvmStatic fun switchProfile(profile: Int)
@JvmStatic fun sendEncodedPacket(packet: ByteArray)  // from Python to remote

// Callback to Python
fun onEncodedPacketReady(packet: ByteArray)  // Kotlin→Python for network TX
fun onCallStateChanged(state: Int)
```

**Lines:** ~150 Kotlin + Python glue

---

## Summary

| File | Python Lines | Kotlin Est. | Status |
|------|-------------|-------------|--------|
| Codec.kt | 62 | ~80 | — |
| Opus.kt | 167 | ~200 | — |
| Codec2.kt | 121 | ~150 | — |
| Sources.kt | 361 | ~300 | — |
| Sinks.kt | 348 | ~300 | — |
| Mixer.kt | 177 | ~200 | — |
| Network.kt | 145 | ~180 | — |
| Pipeline.kt | 58 | ~70 | — |
| Generators.kt | 134 | ~150 | — |
| Filters.kt | 398 | ~303 | ✅ Done |
| Telephony.kt | 732 | ~800 | — |
| Bridge | — | ~150 | NEW |
| **Total** | **2,703** | **~2,883** | — |

## Traceability

*Populated during roadmap creation.*

| Requirement | Phase |
|-------------|-------|
| CODEC-01 | — |
| CODEC-02 | — |
| ... | — |

## Future Requirements (Deferred)

- **FILE-01**: OpusFileSource for playing audio files (ringtones can use simpler approach)
- **FILE-02**: OpusFileSink for recording calls
- **RETICULUM-01**: Full Kotlin Reticulum implementation (massive scope)

## Out of Scope

- Non-Android platforms (desktop LXST stays Python)
- GUI changes (call UI works, just swap backend)
- Reticulum/LXMF networking (stays in Python)
