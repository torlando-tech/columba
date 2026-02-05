# Phase 11: Telephony Integration - Research

**Researched:** 2026-02-04
**Domain:** Kotlin Telephone class, call state machine, quality profiles, mute controls, audio feedback
**Confidence:** HIGH

## Summary

Phase 11 ports the Python LXST Telephone class to Kotlin, making Kotlin the primary call controller while Python handles Reticulum network transport. The Telephone class orchestrates call lifecycle (dial, ring, answer, hangup), manages quality profiles (8 profiles from ULBW/Codec2-700C to ULL/Opus), and coordinates audio pipelines built in Phases 7-10.

Key architectural decisions from CONTEXT.md:
- **Kotlin owns state machine** - State transitions mirror Python LXST exactly (same states, same names)
- **Profiles defined in Kotlin only** - Single source of truth, pushed to Python via call_manager.on_profile_changed
- **NetworkTransport interface** - Abstract Python bridge for future pure-Kotlin Reticulum
- **Direct method calls** - Kotlin notifies Python via method calls, not signalling channel

The Python LXST Telephony.py (732 lines) provides the reference implementation. Key structures to port: `Profiles` class (lines 19-101), `Signalling` class (lines 102-113), and `Telephone` class (lines 114-732).

**Primary recommendation:** Create Kotlin Telephone class with identical state machine to Python LXST, using Phase 9 ToneSource for dial/busy tones and Phase 10 NetworkPacketBridge for network integration. Profile class contains codec config (cleaner than mapping in Telephone).

## Standard Stack

### Core Components (Already Implemented in Prior Phases)

| Component | Location | Purpose | Status |
|-----------|----------|---------|--------|
| Opus codec | audio/codec/Opus.kt | Encode/decode Opus | Phase 7 |
| Codec2 codec | audio/codec/Codec2.kt | Encode/decode Codec2 | Phase 7 |
| LineSource | audio/lxst/LineSource.kt | Microphone capture | Phase 8 |
| LineSink | audio/lxst/LineSink.kt | Speaker playback | Phase 8 |
| ToneSource | audio/lxst/ToneSource.kt | Dial/busy tone generation | Phase 9 |
| Mixer | audio/lxst/Mixer.kt | Multi-source audio mixing | Phase 9 |
| Pipeline | audio/lxst/Pipeline.kt | Source-Codec-Sink wiring | Phase 9 |
| NetworkPacketBridge | audio/bridge/NetworkPacketBridge.kt | Python-Kotlin packet transfer | Phase 10 |
| CallBridge | call/bridge/CallBridge.kt | UI call state | Existing |

### New Components (Phase 11)

| Component | Purpose | Python Reference |
|-----------|---------|-----------------|
| Telephone.kt | Call lifecycle controller | Telephony.py:114-732 |
| Profile.kt | Quality profile definitions | Telephony.py:19-101 |
| Signalling.kt | Signal constants | Telephony.py:102-113 |
| NetworkTransport.kt | Interface for network abstraction | New (future-proofing) |

### Supporting

| Component | Purpose | When to Use |
|-----------|---------|-------------|
| CallBridge.kt | UI state sync | All call state changes |
| call_manager.py | Python call manager | Network transport coordination |

**Installation:** No new dependencies - uses existing Phase 7-10 components.

## Architecture Patterns

### Recommended Project Structure

```
reticulum/src/main/java/com/lxmf/messenger/reticulum/
├── audio/
│   ├── codec/          # Phase 7: Opus, Codec2, Null
│   ├── lxst/           # Phase 8-9: LineSource, LineSink, Mixer, ToneSource, Pipeline
│   └── bridge/         # Phase 10: NetworkPacketBridge, KotlinAudioBridge
└── call/
    ├── bridge/
    │   └── CallBridge.kt       # Existing UI state bridge
    └── telephone/              # NEW: Phase 11
        ├── Telephone.kt        # Main call controller
        ├── Profile.kt          # Quality profiles with codec config
        ├── Signalling.kt       # Signal constants
        └── NetworkTransport.kt # Network abstraction interface
```

### Pattern 1: Profile with Embedded Codec Config

**What:** Profile class contains codec configuration, not just metadata.

**Why:** Cleaner than Telephone mapping. Profile knows its codec, frame time, and constraints.

**Python LXST reference (Telephony.py:72-93):**

```python
@staticmethod
def get_codec(profile):
    if   profile == Profiles.BANDWIDTH_ULTRA_LOW: return Codec2(mode=Codec2.CODEC2_700C)
    elif profile == Profiles.BANDWIDTH_VERY_LOW:  return Codec2(mode=Codec2.CODEC2_1600)
    elif profile == Profiles.BANDWIDTH_LOW:       return Codec2(mode=Codec2.CODEC2_3200)
    elif profile == Profiles.QUALITY_MEDIUM:      return Opus(profile=Opus.PROFILE_VOICE_MEDIUM)
    # ... etc

@staticmethod
def get_frame_time(profile):
    if   profile == Profiles.BANDWIDTH_ULTRA_LOW: return 400
    elif profile == Profiles.BANDWIDTH_VERY_LOW:  return 320
    elif profile == Profiles.BANDWIDTH_LOW:       return 200
    elif profile == Profiles.QUALITY_MEDIUM:      return 60
    # ... etc
```

**Kotlin implementation:**

```kotlin
// Source: Profile.kt (recommended design)
sealed class Profile(
    val id: Int,
    val name: String,
    val abbreviation: String,
    val frameTimeMs: Int
) {
    abstract fun createCodec(): Codec

    object ULBW : Profile(0x10, "Ultra Low Bandwidth", "ULBW", 400) {
        override fun createCodec() = Codec2(mode = Codec2.CODEC2_700C)
    }
    object VLBW : Profile(0x20, "Very Low Bandwidth", "VLBW", 320) {
        override fun createCodec() = Codec2(mode = Codec2.CODEC2_1600)
    }
    object LBW : Profile(0x30, "Low Bandwidth", "LBW", 200) {
        override fun createCodec() = Codec2(mode = Codec2.CODEC2_3200)
    }
    object MQ : Profile(0x40, "Medium Quality", "MQ", 60) {
        override fun createCodec() = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)
    }
    object HQ : Profile(0x50, "High Quality", "HQ", 60) {
        override fun createCodec() = Opus(profile = Opus.PROFILE_VOICE_HIGH)
    }
    object SHQ : Profile(0x60, "Super High Quality", "SHQ", 60) {
        override fun createCodec() = Opus(profile = Opus.PROFILE_VOICE_MAX)
    }
    object LL : Profile(0x70, "Low Latency", "LL", 20) {
        override fun createCodec() = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)
    }
    object ULL : Profile(0x80, "Ultra Low Latency", "ULL", 10) {
        override fun createCodec() = Opus(profile = Opus.PROFILE_VOICE_MEDIUM)
    }

    companion object {
        val DEFAULT = MQ
        val all = listOf(ULBW, VLBW, LBW, MQ, HQ, SHQ, LL, ULL)

        fun fromId(id: Int): Profile? = all.find { it.id == id }
        fun next(profile: Profile): Profile {
            val idx = all.indexOf(profile)
            return all[(idx + 1) % all.size]
        }
    }
}
```

### Pattern 2: State Machine Mirroring Python LXST

**What:** Kotlin state machine with identical states, transitions, and names to Python.

**Python LXST reference (Telephony.py:102-113):**

```python
class Signalling():
    STATUS_BUSY           = 0x00
    STATUS_REJECTED       = 0x01
    STATUS_CALLING        = 0x02
    STATUS_AVAILABLE      = 0x03
    STATUS_RINGING        = 0x04
    STATUS_CONNECTING     = 0x05
    STATUS_ESTABLISHED    = 0x06
    PREFERRED_PROFILE     = 0xFF
    AUTO_STATUS_CODES     = [STATUS_CALLING, STATUS_AVAILABLE, STATUS_RINGING,
                             STATUS_CONNECTING, STATUS_ESTABLISHED]
```

**Kotlin implementation:**

```kotlin
// Source: Signalling.kt
object Signalling {
    const val STATUS_BUSY = 0x00
    const val STATUS_REJECTED = 0x01
    const val STATUS_CALLING = 0x02
    const val STATUS_AVAILABLE = 0x03
    const val STATUS_RINGING = 0x04
    const val STATUS_CONNECTING = 0x05
    const val STATUS_ESTABLISHED = 0x06
    const val PREFERRED_PROFILE = 0xFF

    val AUTO_STATUS_CODES = setOf(
        STATUS_CALLING, STATUS_AVAILABLE, STATUS_RINGING,
        STATUS_CONNECTING, STATUS_ESTABLISHED
    )
}
```

### Pattern 3: NetworkTransport Interface (Future-Proofing)

**What:** Abstract network transport for Python bridge today, pure Kotlin Reticulum later.

**From CONTEXT.md:**
> Abstract network transport behind a Kotlin interface (not coupled to Chaquopy)
> Telephone class should not import or reference PyObject directly

**Kotlin implementation:**

```kotlin
// Source: NetworkTransport.kt
interface NetworkTransport {
    /** Set up link to destination for call */
    suspend fun establishLink(destinationHash: ByteArray): Boolean

    /** Tear down active link */
    fun teardownLink()

    /** Send encoded audio packet to remote */
    fun sendPacket(encodedFrame: ByteArray)

    /** Send signalling to remote */
    fun sendSignal(signal: Int)

    /** Register callback for incoming packets */
    fun setPacketCallback(callback: (ByteArray) -> Unit)

    /** Register callback for incoming signals */
    fun setSignalCallback(callback: (Int) -> Unit)

    /** Check if link is active */
    val isLinkActive: Boolean
}

// Python bridge implementation (today)
class PythonNetworkTransport(
    private val networkBridge: NetworkPacketBridge,
    private val callManager: PyObject
) : NetworkTransport {
    // Implementation wraps Chaquopy calls
}

// Future: Pure Kotlin Reticulum implementation
// class KotlinNetworkTransport : NetworkTransport { ... }
```

### Pattern 4: Mute at Mixer (Recommended Location)

**What:** Implement transmit mute via Mixer.mute() rather than LineSource or Packetizer.

**From CONTEXT.md:**
> Claude decides where in pipeline mute happens (LineSource, Packetizer, or Mixer gain)
> Should balance CPU efficiency with code simplicity

**Recommendation: Mixer.mute()**

Reasons:
1. **Already implemented** - Mixer.kt has mute() method (lines 167-168)
2. **CPU efficient** - Muting at mixer stops processing at mixing stage
3. **Code simple** - Single boolean toggle, no pipeline reconfiguration
4. **Matches Python** - Python LXST uses transmit_mixer.mute()

**Python LXST reference (Telephony.py:466-472):**

```python
def mute_transmit(self, mute=True):
    self.__transmit_muted = mute
    if self.transmit_mixer: self.transmit_mixer.mute(mute)
```

**Kotlin implementation:**

```kotlin
// In Telephone.kt
private var transmitMuted = false

fun muteTransmit(mute: Boolean = true) {
    transmitMuted = mute
    transmitMixer?.mute(mute)
}

// Mute persists across profile switches (CONTEXT.md decision)
fun switchProfile(profile: Profile) {
    // ... reconfigure pipeline ...
    transmitMixer?.mute(transmitMuted)  // Restore mute state
}
```

### Pattern 5: Audio Feedback with ToneSource

**What:** Use Phase 9 ToneSource for dial tone and busy signal.

**From CONTEXT.md:**
> Dial tone: Kotlin ToneSource (Phase 9) - pure Kotlin, low latency
> Busy signal: Yes, with visual too - audio busy tone + UI indication
> Dial tone stops: On first ring (STATUS_RINGING signal from remote)

**Python LXST reference (Telephony.py:118-119, 541-573):**

```python
DIAL_TONE_FREQUENCY   = 382
DIAL_TONE_EASE_MS     = 3.14159

def __enable_dial_tone(self):
    if not self.receive_mixer.should_run: self.receive_mixer.start()
    self.dial_tone.gain = 0.04
    if not self.dial_tone.running: self.dial_tone.start()

def __play_busy_tone(self):
    if self.busy_tone_seconds > 0:
        # ... 0.5s window, alternating on/off
        window = 0.5; started = time.time()
        while time.time()-started < self.busy_tone_seconds:
            elapsed = (time.time()-started)%window
            if elapsed > 0.25: self.__enable_dial_tone()
            else: self.__mute_dial_tone()
```

**Kotlin implementation:**

```kotlin
// In Telephone.kt
companion object {
    const val DIAL_TONE_FREQUENCY = 382f
    const val DIAL_TONE_EASE_MS = 3.14159f
    const val DIAL_TONE_GAIN = 0.04f
    const val BUSY_TONE_SECONDS = 4.25f
}

private var dialTone: ToneSource? = null

private fun enableDialTone() {
    if (dialTone == null) {
        dialTone = ToneSource(
            frequency = DIAL_TONE_FREQUENCY,
            targetGain = DIAL_TONE_GAIN,
            ease = true,
            easeTimeMs = DIAL_TONE_EASE_MS
        )
        dialTone?.sink = receiveMixer
    }
    dialTone?.setGain(DIAL_TONE_GAIN)
    dialTone?.start()
}

private suspend fun playBusyTone() {
    val started = System.currentTimeMillis()
    val windowMs = 500L

    while (System.currentTimeMillis() - started < BUSY_TONE_SECONDS * 1000) {
        val elapsed = (System.currentTimeMillis() - started) % windowMs
        if (elapsed > 250) enableDialTone()
        else muteDialTone()
        delay(5)
    }
}
```

### Anti-Patterns to Avoid

- **Don't couple to PyObject:** Telephone should use NetworkTransport interface, not PyObject directly
- **Don't duplicate profiles:** Profiles defined in Kotlin only, pushed to Python
- **Don't hand-roll state machine:** Mirror Python LXST exactly for interop
- **Don't forget mute persistence:** Mute state must persist across profile switches

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| State machine | Custom states/transitions | Mirror Python LXST exactly | Wire compatibility with LXST peers |
| Dial tone | Custom tone generator | ToneSource from Phase 9 | Already tested, has easing |
| Audio pipeline | Manual source/sink wiring | Pipeline from Phase 9 | Handles codec, lifecycle |
| Network packets | Custom packet format | NetworkPacketBridge from Phase 10 | Wire compatible with Python |
| Quality profiles | Ad-hoc codec selection | Profile sealed class | Single source of truth |
| Call UI state | Direct UI updates | CallBridge StateFlow | Thread-safe, UI observability |

**Key insight:** Phase 11 is integration, not implementation. Components exist from Phases 7-10; Telephone coordinates them.

## Common Pitfalls

### Pitfall 1: State Machine Divergence

**What goes wrong:** Kotlin state transitions differ from Python, causing call setup failures.

**Why it happens:** Implementing "logical" states instead of matching Python exactly.

**How to avoid:**
- Copy state constants verbatim from Python Signalling class
- Implement state transitions by reading Python Telephone code
- Test with Python LXST peer to verify interop

**Warning signs:**
- Calls fail to connect with Python LXST desktop
- "Invalid state transition" errors in logs

### Pitfall 2: Profile ID Mismatch

**What goes wrong:** Profile switch signals not understood by remote peer.

**Why it happens:** Using different hex values than Python.

**How to avoid:**
- Profile IDs must match Python exactly:
  - ULBW = 0x10, VLBW = 0x20, LBW = 0x30
  - MQ = 0x40, HQ = 0x50, SHQ = 0x60
  - LL = 0x70, ULL = 0x80
- Profile signal = PREFERRED_PROFILE (0xFF) + profile_id

**Warning signs:**
- "Unknown profile" errors on remote
- Codec mismatch after profile switch

### Pitfall 3: Dial Tone Not Stopping

**What goes wrong:** Dial tone continues playing after call established.

**Why it happens:** Missing STATUS_RINGING signal handler.

**How to avoid:**
- Stop dial tone on STATUS_RINGING (CONTEXT.md decision)
- Also stop on STATUS_ESTABLISHED as fallback

```kotlin
fun onSignalReceived(signal: Int) {
    when (signal) {
        Signalling.STATUS_RINGING -> {
            disableDialTone()
            callState = CallState.Ringing
        }
        // ...
    }
}
```

**Warning signs:**
- Users hear dial tone during active call
- Audio feedback complaints

### Pitfall 4: Mute State Lost on Profile Switch

**What goes wrong:** User mutes, switches profile, becomes unmuted.

**Why it happens:** Pipeline reconfiguration creates new Mixer without mute.

**How to avoid:**
- Store mute state in Telephone, not just Mixer
- Restore mute after pipeline reconfiguration

```kotlin
private var transmitMuted = false

fun switchProfile(profile: Profile) {
    val wasMuted = transmitMuted  // Remember
    // ... reconfigure transmit pipeline ...
    transmitMixer?.mute(wasMuted)  // Restore
}
```

**Warning signs:**
- Users complain about "phantom unmute"
- Mute indicator and actual state disagree

### Pitfall 5: Blocking Network Calls on Audio Thread

**What goes wrong:** Audio stutters during call setup/teardown.

**Why it happens:** Python network calls on same thread as audio processing.

**How to avoid:**
- NetworkTransport methods are suspend functions or use callbacks
- Never call Python synchronously from audio coroutine
- Use dedicated bridge scope (Dispatchers.IO)

**Warning signs:**
- Audio gaps during call state changes
- ANR during call setup

### Pitfall 6: Missing Ringtone Support

**What goes wrong:** Incoming calls don't play ringtone.

**Why it happens:** Only implementing ToneSource, forgetting system/custom ringtone option.

**How to avoid (from CONTEXT.md):**
> Ringtone: User setting - can choose system ringtone OR custom ToneSource tone

```kotlin
fun activateRingTone() {
    if (useSystemRingtone) {
        // Play system ringtone via Android RingtoneManager
        systemRingtone?.play()
    } else if (customRingtonePath != null) {
        // Future: OpusFileSource for custom tone
    } else {
        // Fallback: ToneSource ring pattern
        playToneRingPattern()
    }
}
```

**Warning signs:**
- Users can't hear incoming calls
- Silent phone on incoming call

## Code Examples

### Telephone Class Structure

```kotlin
// Source: Telephone.kt (recommended structure)
class Telephone(
    private val networkTransport: NetworkTransport,
    private val audioBridge: KotlinAudioBridge,
    private val callBridge: CallBridge,
    ringTime: Long = RING_TIME_MS,
    waitTime: Long = WAIT_TIME_MS
) {
    companion object {
        private const val TAG = "Columba:Telephone"
        const val RING_TIME_MS = 60_000L
        const val WAIT_TIME_MS = 70_000L
        const val CONNECT_TIME_MS = 5_000L
        const val DIAL_TONE_FREQUENCY = 382f
        const val DIAL_TONE_EASE_MS = 3.14159f
    }

    // State
    private var callStatus = Signalling.STATUS_AVAILABLE
    private var activeProfile: Profile = Profile.DEFAULT
    private var transmitMuted = false
    private var receiveMuted = false

    // Audio pipeline components
    private var receiveMixer: Mixer? = null
    private var transmitMixer: Mixer? = null
    private var receivePipeline: Pipeline? = null
    private var transmitPipeline: Pipeline? = null
    private var audioInput: LineSource? = null
    private var audioOutput: LineSink? = null
    private var dialTone: ToneSource? = null

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ===== Public API =====

    /** Initiate outgoing call */
    suspend fun call(destinationHash: ByteArray, profile: Profile = Profile.DEFAULT)

    /** Answer incoming call */
    fun answer()

    /** Hang up current call */
    fun hangup()

    /** Switch quality profile mid-call */
    fun switchProfile(profile: Profile)

    /** Mute transmit (microphone) */
    fun muteTransmit(mute: Boolean = true)

    /** Mute receive (speaker) */
    fun muteReceive(mute: Boolean = true)

    // ===== Internal State Machine =====

    private fun setStatus(status: Int) {
        if (status in Signalling.AUTO_STATUS_CODES) {
            callStatus = status
        }
        // Notify Python via direct method call (CONTEXT.md decision)
        networkTransport.sendSignal(status)
    }
}
```

### Call Flow: Outgoing Call

Based on Python Telephony.py:648-673:

```kotlin
suspend fun call(destinationHash: ByteArray, profile: Profile = Profile.DEFAULT) {
    if (isCallActive()) {
        Log.w(TAG, "Already in call, ignoring")
        return
    }

    callStatus = Signalling.STATUS_CALLING
    activeProfile = profile
    callBridge.setConnecting(destinationHash.toHexString())

    // Request path and establish link (Python handles via Reticulum)
    val linkEstablished = withTimeoutOrNull(waitTime) {
        networkTransport.establishLink(destinationHash)
    }

    if (linkEstablished != true) {
        Log.w(TAG, "Link establishment timeout")
        hangup()
        return
    }

    // Prepare dialling pipelines
    prepareDiallingPipelines()

    // Start outgoing call timeout
    scope.launch {
        delay(waitTime)
        if (callStatus < Signalling.STATUS_ESTABLISHED) {
            Log.w(TAG, "Outgoing call timeout")
            hangup()
        }
    }
}
```

### Profile Switch

Based on Python Telephony.py:482-491:

```kotlin
fun switchProfile(profile: Profile) {
    if (activeProfile == profile) return
    if (callStatus != Signalling.STATUS_ESTABLISHED) return

    activeProfile = profile

    // Notify remote of profile change
    val profileSignal = Signalling.PREFERRED_PROFILE + profile.id
    networkTransport.sendSignal(profileSignal)

    // Reconfigure transmit pipeline with new codec
    reconfigureTransmitPipeline()
}

private fun reconfigureTransmitPipeline() {
    val wasMuted = transmitMuted

    // Stop current pipeline
    audioInput?.stop()
    transmitMixer?.stop()
    transmitPipeline?.stop()

    // Create new mixer and pipeline with new profile
    transmitMixer = Mixer(
        targetFrameMs = activeProfile.frameTimeMs,
        codec = activeProfile.createCodec()
    )

    audioInput = LineSource(
        bridge = audioBridge,
        codec = activeProfile.createCodec(),
        targetFrameMs = activeProfile.frameTimeMs
    ).apply { sink = transmitMixer }

    transmitPipeline = Pipeline(
        source = transmitMixer!!,
        codec = activeProfile.createCodec(),
        sink = networkTransport.packetSink  // RemoteSink from NetworkTransport
    )

    // Restore mute state (CONTEXT.md: mute persists across switches)
    transmitMixer?.mute(wasMuted)

    // Start new pipeline
    transmitMixer?.start()
    audioInput?.start()
    transmitPipeline?.start()
}
```

### Signal Handling

Based on Python Telephony.py:683-729:

```kotlin
private fun onSignalReceived(signal: Int) {
    when {
        signal == Signalling.STATUS_BUSY -> {
            Log.d(TAG, "Remote is busy")
            scope.launch {
                playBusyTone()
                disableDialTone()
                hangup(reason = Signalling.STATUS_BUSY)
            }
        }
        signal == Signalling.STATUS_REJECTED -> {
            Log.d(TAG, "Remote rejected call")
            scope.launch {
                playBusyTone()
                disableDialTone()
                hangup(reason = Signalling.STATUS_REJECTED)
            }
        }
        signal == Signalling.STATUS_AVAILABLE -> {
            Log.d(TAG, "Line available, identification sent")
            callStatus = signal
        }
        signal == Signalling.STATUS_RINGING -> {
            Log.d(TAG, "Remote is ringing")
            callStatus = signal
            prepareDiallingPipelines()
            sendProfilePreference()
            activateDialTone()  // Start dial tone pattern
        }
        signal == Signalling.STATUS_CONNECTING -> {
            Log.d(TAG, "Call answered, remote setting up")
            callStatus = signal
            resetDiallingPipelines()
            openPipelines()
        }
        signal == Signalling.STATUS_ESTABLISHED -> {
            Log.d(TAG, "Call established")
            disableDialTone()  // Stop dial tone (CONTEXT.md)
            startPipelines()
            callStatus = signal
            callBridge.onCallEstablished(currentRemoteIdentity)
        }
        signal >= Signalling.PREFERRED_PROFILE -> {
            // Profile change from remote
            val profileId = signal - Signalling.PREFERRED_PROFILE
            Profile.fromId(profileId)?.let { profile ->
                if (callStatus == Signalling.STATUS_ESTABLISHED) {
                    switchProfile(profile)
                } else {
                    selectCallProfile(profile)
                }
            }
        }
    }
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Python owns call state | Kotlin owns call state | Phase 11 | Kotlin is authority, Python is transport |
| Python audio pipeline | Kotlin audio pipeline | Phase 7-10 | <5ms latency vs Python overhead |
| Profiles in Python | Profiles in Kotlin only | Phase 11 | Single source of truth |
| PyObject in Telephone | NetworkTransport interface | Phase 11 | Future-proofed for pure Kotlin RNS |

**Deprecated/outdated:**
- Python call_manager as state owner (now just network transport)
- Direct PyObject references in call code

## Open Questions

1. **System Ringtone Integration**
   - What we know: CONTEXT.md says "user setting - system or custom"
   - What's unclear: Exact Android API for playing system ringtone in background service
   - Recommendation: Use Android RingtoneManager, requires VIBRATE permission and foreground service

2. **NetworkTransport Initialization**
   - What we know: Interface abstracts Python bridge
   - What's unclear: When to initialize (app start vs first call)
   - Recommendation: Lazy initialization on first call, matches Python LXST pattern

3. **Profile Persistence**
   - What we know: User can select profile
   - What's unclear: Where to persist preferred profile (SharedPreferences? call_manager?)
   - Recommendation: SharedPreferences in Kotlin, push to Python on call start

4. **Remote Profile Mismatch**
   - What we know: Either side can request profile change
   - What's unclear: Behavior when codecs don't match (e.g., remote has Codec2, local wants Opus)
   - Recommendation: Accept remote profile, local UI shows warning

## Sources

### Primary (HIGH confidence)

- Python LXST Telephony.py - Complete reference implementation
  - `/home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Primitives/Telephony.py`
  - Lines 19-101: Profiles class (profiles, codecs, frame times)
  - Lines 102-113: Signalling class (state constants)
  - Lines 114-732: Telephone class (call lifecycle, pipelines, signals)

- Existing Kotlin components (Phases 7-10)
  - `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/codec/Opus.kt`
  - `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/ToneSource.kt`
  - `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/Mixer.kt`
  - `reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/bridge/NetworkPacketBridge.kt`
  - `reticulum/src/main/java/com/lxmf/messenger/reticulum/call/bridge/CallBridge.kt`

- Python call_manager.py - Existing Python-Kotlin integration
  - `/home/tyler/repos/public/columba/python/lxst_modules/call_manager.py`
  - Lines 223-265: call(), answer(), hangup() methods
  - Lines 421-484: Callback handlers

- Phase 10 Research - Network bridge patterns
  - `/home/tyler/repos/public/columba/.planning/phases/10-network-bridge/10-RESEARCH.md`

### Secondary (MEDIUM confidence)

- Phase 11 CONTEXT.md - User decisions
  - `/home/tyler/repos/public/columba/.planning/phases/11-telephony-integration/11-CONTEXT.md`

- Phase 7 Research - Codec patterns and profiles
  - `/home/tyler/repos/public/columba/.planning/phases/07-codec-foundation/07-RESEARCH.md`

## Metadata

**Confidence breakdown:**
- Profile architecture: HIGH - Python code is clear, sealed class pattern well-suited
- State machine: HIGH - Direct port of Python, verbatim state constants
- Mute location: HIGH - Mixer.mute() already implemented, matches Python
- NetworkTransport interface: MEDIUM - New abstraction, untested with Python bridge
- Audio feedback: HIGH - ToneSource exists, busy tone pattern clear from Python

**Research date:** 2026-02-04
**Valid until:** 60 days (stable domain, Python LXST Telephony.py unlikely to change)

**Key files for planning:**
1. Telephony.py (lines 114-732) - Primary reference for Telephone class
2. ToneSource.kt - Ready for dial tone integration
3. Mixer.kt (lines 167-168) - mute() method for transmit mute
4. NetworkPacketBridge.kt - Foundation for NetworkTransport
5. CallBridge.kt - UI state synchronization pattern
