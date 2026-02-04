# Phase 10: Network Bridge - Research

**Researched:** 2026-02-04
**Domain:** Python-Kotlin bridge for encoded audio packets, Chaquopy threading, LXST Network.py port
**Confidence:** HIGH

## Summary

Phase 10 implements the network bridge that connects the Kotlin audio pipeline (Phase 7-9) to Python Reticulum. This is NOT about implementing network transport (Python LXST already handles that via RNS). Instead, it's about the **handoff mechanism**: how encoded packets cross the Python-Kotlin boundary bidirectionally.

The bridge has three components that match Python LXST Network.py exactly:
- **Packetizer** (RemoteSink): Receives encoded frames from Kotlin Mixer, sends to Python Reticulum via Chaquopy
- **LinkSource** (RemoteSource): Receives encoded frames from Python Reticulum, pushes to Kotlin Mixer
- **SignallingReceiver**: Handles inband signalling (call status, mute state, profile changes)

The critical constraint is **latency**: packet transfer must be <5ms. The existing Chaquopy bridge (KotlinAudioBridge, CallBridge) demonstrates that direct method calls between Python and Kotlin achieve sub-millisecond latency when done correctly.

**Primary recommendation:** Use existing Chaquopy callback pattern from KotlinAudioBridge. Packetizer calls Python directly (synchronous). LinkSource receives from Python via callback to Kotlin method (event-driven). Raw ByteArray (no Base64). Dedicated bridge thread for outbound, queue handoff for inbound.

## Standard Stack

### Core Components (Already Present)

| Component | Location | Purpose | Status |
|-----------|----------|---------|--------|
| Chaquopy | build.gradle.kts | Python-Kotlin interop, handles JNI | Configured |
| KotlinAudioBridge | reticulum/audio/bridge/ | Existing Python-Kotlin bridge pattern | Reference impl |
| CallBridge | reticulum/call/bridge/ | Existing callback pattern | Reference impl |
| Source/Sink | reticulum/audio/lxst/ | Phase 8 base classes | Implemented |
| Mixer | reticulum/audio/lxst/ | Phase 9 multi-source mixer | Implemented |
| Codec | reticulum/audio/codec/ | Phase 7 Opus/Codec2 | Implemented |

### Python LXST Components (Reference)

| Component | File | Purpose | Lines |
|-----------|------|---------|-------|
| Packetizer | Network.py | RemoteSink, sends to RNS.Link | 49-89 |
| LinkSource | Network.py | RemoteSource, receives from RNS.Link | 98-145 |
| SignallingReceiver | Network.py | Inband signal handling | 13-47 |

**Installation:** No new dependencies. Everything uses existing Chaquopy and LXST infrastructure.

## Architecture Patterns

### Recommended Kotlin Package Structure

```
reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/
├── Source.kt           # Existing base classes
├── Sink.kt             # Existing base classes
├── Mixer.kt            # Phase 9 mixer
├── Packetizer.kt       # NEW - RemoteSink, sends to Python
├── LinkSource.kt       # NEW - RemoteSource, receives from Python
├── SignallingReceiver.kt # NEW - Inband signal handling
└── NetworkBridge.kt    # NEW - Coordination layer

reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/bridge/
├── KotlinAudioBridge.kt      # Existing audio bridge
├── NetworkPacketBridge.kt    # NEW - Low-level packet handoff
└── KotlinAudioFilters.kt     # Existing filters
```

### Pattern 1: Packetizer - Kotlin to Python

**What:** Receives encoded audio frames from Kotlin Pipeline, sends to Python Reticulum via Chaquopy.

**Python LXST reference (Network.py:49-89):**

```python
class Packetizer(RemoteSink):
    def __init__(self, destination, failure_callback=None):
        self.destination = destination
        self.should_run = False
        self.source = None
        self.transmit_failure = False
        self.__failure_calback = failure_callback

    def handle_frame(self, frame, source=None):
        if type(self.destination) == RNS.Link and not self.destination.status == RNS.Link.ACTIVE:
            return

        # Add codec header byte (identifies codec type for receiver)
        frame = codec_header_byte(type(self.source.codec))+frame
        packet_data = {FIELD_FRAMES:frame}
        frame_packet = RNS.Packet(self.destination, mp.packb(packet_data), create_receipt=False)
        if frame_packet.send() == False:
            self.transmit_failure = True
            if callable(self.__failure_calback): self.__failure_calback()
```

**Key observations:**

1. **Codec header:** Single byte prepended identifies codec type (Opus, Codec2, Null)
2. **Packet format:** msgpack dict with FIELD_FRAMES (0x01) key
3. **Packet size:** Typical 20-60 bytes, max <100 bytes (per context)
4. **No ACK needed:** create_receipt=False for low-latency fire-and-forget
5. **Failure callback:** Notifies on transmission failure

**Kotlin implementation pattern:**

```kotlin
class Packetizer(
    private val pythonBridge: NetworkPacketBridge,
    private val failureCallback: (() -> Unit)? = null
) : RemoteSink() {

    private val shouldRun = AtomicBoolean(false)
    var source: Source? = null  // Set by Pipeline

    override fun canReceive(fromSource: Source?): Boolean {
        // Always accept - Python handles backpressure
        return shouldRun.get()
    }

    override fun handleFrame(frame: FloatArray, source: Source?) {
        if (!shouldRun.get()) return

        // Frame arrives as float32 from Mixer
        // Encode with codec from Pipeline
        val codec = source?.let { (it as? LineSource)?.codec }
        val encodedFrame = codec?.encode(frame) ?: return

        // Send to Python via bridge (non-blocking)
        try {
            pythonBridge.sendPacket(encodedFrame)
        } catch (e: Exception) {
            failureCallback?.invoke()
        }
    }

    override fun start() { shouldRun.set(true) }
    override fun stop() { shouldRun.set(false) }
    override fun isRunning(): Boolean = shouldRun.get()
}
```

### Pattern 2: LinkSource - Python to Kotlin

**What:** Receives encoded frames from Python Reticulum, decodes, pushes to Kotlin Mixer.

**Python LXST reference (Network.py:98-145):**

```python
class LinkSource(RemoteSource, SignallingReceiver):
    def __init__(self, link, signalling_receiver, sink=None):
        self.should_run   = False
        self.link         = link
        self.sink         = sink
        self.codec        = Null()
        self.pipeline     = None
        self.proxy        = signalling_receiver
        self.receive_lock = threading.Lock()
        self.link.set_packet_callback(self._packet)

    def _packet(self, data, packet):
        with self.receive_lock:
            try:
                unpacked = mp.unpackb(data)
                if type(unpacked) == dict:
                    if FIELD_FRAMES in unpacked:
                        frames = unpacked[FIELD_FRAMES]
                        if type(frames) != list: frames = [frames]
                        for frame in frames:
                            frame_codec = codec_type(frame[0])  # First byte = codec type
                            if self.codec and self.sink:
                                if type(self.codec) != frame_codec:
                                    RNS.log(f"Remote switched codec to {frame_codec}", RNS.LOG_DEBUG)
                                    if self.pipeline: self.pipeline.codec = frame_codec()
                                    else: self.codec = frame_codec(); self.codec.sink = self.sink
                                    decoded_frame = self.codec.decode(frame[1:])
                                else:
                                    decoded_frame = self.codec.decode(frame[1:])

                                if self.pipeline: self.sink.handle_frame(decoded_frame, self)
                                else:             self.sink.handle_frame(decoded_frame, self, decoded=True)

                    if FIELD_SIGNALLING in unpacked:
                        super()._packet(data=None, packet=packet, unpacked=unpacked)

            except Exception as e:
                RNS.log(f"{self} could not process incoming packet: {e}", RNS.LOG_ERROR)
```

**Key observations:**

1. **Callback-driven:** Python receives packet, calls LinkSource._packet()
2. **Codec header parsing:** First byte identifies codec, remaining bytes are frame data
3. **Dynamic codec switching:** Remote can change codec mid-call
4. **Signalling mixed in:** Same packet can contain audio frames AND signalling
5. **Thread-safe:** receive_lock protects packet processing

**Kotlin implementation pattern:**

```kotlin
class LinkSource(
    private val pythonBridge: NetworkPacketBridge,
    private val signallingReceiver: SignallingReceiver,
    var sink: Sink? = null
) : RemoteSource() {

    override var sampleRate: Int = 48000
    override var channels: Int = 1

    private val shouldRun = AtomicBoolean(false)
    private var codec: Codec? = null
    private val receiveLock = Any()

    // Queue for thread-safe handoff from Python callback to audio thread
    private val packetQueue = ArrayDeque<ByteArray>(MAX_PACKETS)

    companion object {
        const val MAX_PACKETS = 8
        const val FIELD_FRAMES = 0x01
        const val FIELD_SIGNALLING = 0x00
    }

    /**
     * Called by Python via Chaquopy when packet arrives.
     * Runs on Python's thread - must be fast!
     */
    fun onPacketReceived(packetData: ByteArray) {
        if (!shouldRun.get()) return

        // Queue packet for processing (non-blocking)
        synchronized(receiveLock) {
            if (packetQueue.size >= MAX_PACKETS) {
                packetQueue.removeFirst()  // Drop oldest
            }
            packetQueue.addLast(packetData)
        }

        // Wake up processing coroutine
        // (Alternative: process inline if latency allows)
    }

    private fun processPacket(data: ByteArray) {
        // Unpack msgpack - handled in Python, we receive raw frame bytes
        // Parse codec header byte
        val codecType = data[0].toInt()
        val frameData = data.copyOfRange(1, data.size)

        // Decode with appropriate codec
        val decoded = getCodec(codecType).decode(frameData)

        // Push to sink (Mixer)
        sink?.handleFrame(decoded, this)
    }
}
```

### Pattern 3: SignallingReceiver - Bidirectional State

**What:** Handles inband signalling for call state, mute changes, profile switches.

**Python LXST reference (Network.py:13-47):**

```python
class SignallingReceiver():
    def __init__(self, proxy=None):
        self.outgoing_signals = deque()
        self.proxy = proxy

    def handle_signalling_from(self, source):
        source.set_packet_callback(self._packet)

    def signalling_received(self, signals, source):
        if self.proxy: self.proxy.signalling_received(signals, source)

    def signal(self, signal, destination, immediate=True):
        signalling_data = {FIELD_SIGNALLING:[signal]}
        if immediate:
            signalling_packet = RNS.Packet(destination, mp.packb(signalling_data), create_receipt=False)
            signalling_packet.send()
```

**Signalling values from Telephony.py:**

```python
class Signalling():
    STATUS_BUSY           = 0x00
    STATUS_REJECTED       = 0x01
    STATUS_CALLING        = 0x02
    STATUS_AVAILABLE      = 0x03
    STATUS_RINGING        = 0x04
    STATUS_CONNECTING     = 0x05
    STATUS_ESTABLISHED    = 0x06
    PREFERRED_PROFILE     = 0xFF  # 0xFF + profile_byte = profile change signal
```

**Kotlin implementation pattern:**

```kotlin
class SignallingReceiver(
    private val pythonBridge: NetworkPacketBridge,
    private val onSignalReceived: (Int, Source?) -> Unit
) {
    companion object {
        const val FIELD_SIGNALLING = 0x00

        // Match Python Telephony.Signalling exactly
        const val STATUS_BUSY = 0x00
        const val STATUS_REJECTED = 0x01
        const val STATUS_CALLING = 0x02
        const val STATUS_AVAILABLE = 0x03
        const val STATUS_RINGING = 0x04
        const val STATUS_CONNECTING = 0x05
        const val STATUS_ESTABLISHED = 0x06
        const val PREFERRED_PROFILE = 0xFF
    }

    /**
     * Send signal to remote peer.
     * Fire-and-forget, non-blocking.
     */
    fun signal(signal: Int, immediate: Boolean = true) {
        if (immediate) {
            pythonBridge.sendSignal(signal)
        } else {
            // Queue for later (inband with next audio packet)
            // Context says fire-and-forget, so immediate=true is typical
        }
    }

    /**
     * Called by LinkSource when signalling data received.
     */
    fun handleSignalling(signals: List<Int>, source: Source?) {
        signals.forEach { signal ->
            when {
                signal >= PREFERRED_PROFILE -> {
                    // Profile change: signal = 0xFF + profile_byte
                    val profile = signal - PREFERRED_PROFILE
                    onSignalReceived(signal, source)
                }
                else -> {
                    // Status change
                    onSignalReceived(signal, source)
                }
            }
        }
    }
}
```

### Pattern 4: NetworkPacketBridge - Low-Level Handoff

**What:** Coordinates packet transfer between Kotlin and Python. Single point of Chaquopy interaction.

**Based on existing KotlinAudioBridge pattern:**

```kotlin
class NetworkPacketBridge(private val context: Context) {

    companion object {
        private const val TAG = "Columba:NetBridge"

        @Volatile
        private var instance: NetworkPacketBridge? = null

        fun getInstance(context: Context): NetworkPacketBridge {
            return instance ?: synchronized(this) {
                instance ?: NetworkPacketBridge(context.applicationContext).also { instance = it }
            }
        }
    }

    // Python call_manager reference (set by PythonWrapperManager)
    @Volatile
    private var pythonNetworkHandler: PyObject? = null

    // Kotlin callback for incoming packets (set by LinkSource)
    @Volatile
    private var onPacketReceived: ((ByteArray) -> Unit)? = null

    // Dedicated bridge thread (avoids GIL contention)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Send encoded packet to Python Reticulum.
     * Called by Packetizer (Kotlin -> Python).
     *
     * Must be non-blocking for audio thread.
     */
    fun sendPacket(encodedFrame: ByteArray) {
        scope.launch {
            try {
                pythonNetworkHandler?.callAttr("send_packet", encodedFrame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send packet", e)
            }
        }
    }

    /**
     * Receive encoded packet from Python Reticulum.
     * Called by Python via Chaquopy callback (Python -> Kotlin).
     *
     * Must be fast - Python GIL is held during this call.
     */
    fun onPythonPacketReceived(packetData: ByteArray) {
        onPacketReceived?.invoke(packetData)
    }

    /**
     * Send signalling to Python.
     */
    fun sendSignal(signal: Int) {
        scope.launch {
            try {
                pythonNetworkHandler?.callAttr("send_signal", signal)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send signal", e)
            }
        }
    }

    fun setPythonNetworkHandler(handler: PyObject) {
        pythonNetworkHandler = handler
    }

    fun setPacketCallback(callback: (ByteArray) -> Unit) {
        onPacketReceived = callback
    }
}
```

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Packet serialization | Custom binary format | Python LXST msgpack (umsgpack) | Wire compatibility with LXST desktop |
| Codec identification | Custom header scheme | LXST codec_header_byte() | Single byte, standard format |
| Thread-safe queue | Synchronous processing | ArrayDeque with lock (match Python deque) | Backpressure, no blocking |
| Callback registration | Complex event system | Direct Chaquopy method calls | KotlinAudioBridge pattern works |
| GIL handling | Manual threading | Chaquopy auto-releases GIL on Java calls | Trust the framework |

**Key insight:** Python LXST handles ALL network complexity (RNS links, packets, retransmit). Kotlin just needs to hand off bytes quickly.

## Common Pitfalls

### Pitfall 1: Blocking on Python Callback

**What goes wrong:** Calling Python from Kotlin audio thread, causing jitter.

**Why it happens:** Chaquopy call acquires GIL, may block if Python is busy.

**How to avoid:** Use dedicated bridge thread for Kotlin -> Python calls:
```kotlin
// BAD: Blocks audio thread
fun handleFrame(frame: FloatArray, source: Source?) {
    pythonHandler.callAttr("send_packet", frame)  // BLOCKS!
}

// GOOD: Non-blocking handoff
private val bridgeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
fun handleFrame(frame: FloatArray, source: Source?) {
    bridgeScope.launch {
        pythonHandler.callAttr("send_packet", frame)
    }
}
```

**Warning signs:** Audio choppiness when network activity is high.

### Pitfall 2: Processing Packets on Python Thread

**What goes wrong:** Python callback does heavy work (decode, mix), holding GIL too long.

**Why it happens:** Natural to process where data arrives.

**How to avoid:** Queue packets, process on Kotlin audio thread:
```kotlin
// Called by Python - must be FAST
fun onPacketReceived(data: ByteArray) {
    packetQueue.offer(data)  // Non-blocking enqueue
}

// Process on audio thread
private suspend fun processLoop() {
    while (running) {
        val packet = packetQueue.poll() ?: continue
        val decoded = codec.decode(packet)
        sink.handleFrame(decoded, this)
    }
}
```

**Warning signs:** Python GIL contention errors, audio gaps.

### Pitfall 3: Logging in Packet Hot Path

**What goes wrong:** Log.d() in handleFrame() causes stuttering.

**Why it happens:** Logging looks harmless but is synchronous I/O.

**How to avoid:** Context specifies this explicitly:
- Use async logging or separate logging thread
- No synchronous Log.d() calls in packet hot path
- Sample-based logging (every 100th frame) for debugging

**Warning signs:** Audio choppiness correlating with logcat flood.

### Pitfall 4: Base64 Encoding Overhead

**What goes wrong:** Converting ByteArray to String for Chaquopy, adding latency.

**Why it happens:** Assumption that Chaquopy can't handle binary data.

**How to avoid:** Chaquopy handles ByteArray directly as Python bytes:
```kotlin
// BAD: String conversion overhead
val base64 = Base64.encodeToString(data, Base64.DEFAULT)
python.callAttr("receive", base64)

// GOOD: Direct ByteArray
python.callAttr("receive", data)  // Becomes Python bytes
```

**Warning signs:** CPU spikes on packet transfer, visible in profiler.

### Pitfall 5: Ignoring Codec Header Byte

**What goes wrong:** Sending raw encoded frame without codec identification.

**Why it happens:** Overlooking Python LXST packet structure.

**How to avoid:** Always prepend codec header byte:
```kotlin
// Python LXST expects: codec_header_byte + encoded_frame
val packet = ByteArray(1 + encodedFrame.size)
packet[0] = codecType.toByte()
encodedFrame.copyInto(packet, 1)
```

**Warning signs:** "Unknown codec type" errors from remote peer.

## Code Examples

### Chaquopy Callback Pattern (from KotlinAudioBridge)

```kotlin
// Source: KotlinAudioBridge.kt:619-663
// Existing pattern for Python -> Kotlin callbacks

fun readAudio(numSamples: Int): ByteArray? {
    if (!isRecording.get()) {
        return null
    }

    return try {
        // Wait up to 50ms for data (allows for ~20ms frames)
        val data = recordBuffer.poll(50, TimeUnit.MILLISECONDS)
        data
    } catch (e: InterruptedException) {
        null
    }
}

// Called by Python via Chaquopy - no explicit callback registration needed
// Python code: bridge.readAudio(960) returns ByteArray directly
```

### Python-Kotlin Bridge Setup (from PythonWrapperManager)

```kotlin
// Source: PythonWrapperManager.kt:368-373
// How bridges are registered with Python wrapper

wrapper.callAttr("set_audio_bridge", audioBridge)
wrapper.callAttr("set_call_bridge", callBridge)

// Python side receives Kotlin objects, can call methods directly:
// self.kotlin_audio_bridge.readAudio(960)
// self.kotlin_call_bridge.onCallEstablished(identity_hash)
```

### CallBridge Callback Pattern (for reference)

```kotlin
// Source: CallBridge.kt:232-242
// Python calls this method when incoming call arrives

fun onIncomingCall(identityHash: String) {
    Log.i(TAG, "Incoming call from: ${identityHash.take(16)}...")
    scope.launch {
        _remoteIdentity.value = identityHash
        _callState.value = CallState.Incoming(identityHash)
    }
    // Notify listener for IPC broadcast to UI process
    incomingCallListener?.invoke(identityHash)
}

// Python code in call_manager.py:424-426:
// self._kotlin_call_bridge.onIncomingCall(identity_hash)
```

### Python Network Handler (call_manager.py integration)

```python
# Source: call_manager.py shows existing Python-Kotlin integration pattern

def __init__(self, identity):
    self.identity = identity
    self.telephone = None
    self._audio_bridge = None
    self._kotlin_call_bridge = None  # Set by Kotlin

def initialize(self, audio_bridge, kotlin_call_bridge=None):
    self._audio_bridge = audio_bridge
    self._kotlin_call_bridge = kotlin_call_bridge
    # Python can now call: self._kotlin_call_bridge.onCallEstablished(hash)
```

## Threading Model

Based on context decisions and existing patterns:

### Thread Allocation

| Thread | Purpose | Calls |
|--------|---------|-------|
| Audio thread (Kotlin) | Mixer, Pipeline, Sources/Sinks | handleFrame() processing |
| Bridge thread (Kotlin Dispatchers.IO) | Python calls | sendPacket(), sendSignal() |
| Python main/RNS thread | Reticulum network operations | Packet callbacks |

### Kotlin -> Python (Outbound)

```
Audio Thread          Bridge Thread         Python Thread
    |                      |                     |
handleFrame(frame)         |                     |
    |                      |                     |
queue.offer(frame)         |                     |
    |-------------------->.|                     |
    |              queue.poll()                  |
    |                      |                     |
    |          pythonHandler.callAttr()          |
    |                      |-------------------->|
    |                      |                RNS.Packet.send()
    |                      |<--------------------|
    |                      |                     |
```

### Python -> Kotlin (Inbound)

```
Python Thread         Kotlin Bridge          Audio Thread
    |                      |                     |
RNS packet callback        |                     |
    |                      |                     |
bridge.onPacketReceived()  |                     |
    |-------------------->.|                     |
    |              queue.offer(data)             |
    |<--------------------|                      |
    |                      |                     |
    | (GIL released)       |    queue.poll()     |
    |                      |-------------------->|
    |                      |              codec.decode()
    |                      |              sink.handleFrame()
```

### GIL Considerations

From [Chaquopy documentation](https://chaquo.com/chaquopy/doc/current/cross.html):
- GIL is released when Python calls Java/Kotlin methods
- Multiple Python threads can exist, but only one executes at a time
- sys.setswitchinterval(0.001) can reduce GIL contention

**Recommendation:** Context says "dedicated bridge thread (research feasibility)". Based on research:
- Feasible: Use Dispatchers.IO coroutine scope for outbound calls
- Inbound: Python callback is fast (just queue), GIL held briefly
- No explicit GIL management needed - Chaquopy handles it

## Packet Format

### Audio Packet (FIELD_FRAMES = 0x01)

```
Python LXST format (msgpack dict):
{
    0x01: <codec_header_byte><encoded_frame_bytes>
}

Example for Opus frame:
{
    0x01: b'\x40' + <20-60 bytes opus data>  # 0x40 = Opus codec ID
}

Example for Codec2 frame:
{
    0x01: b'\x10' + <6-8 bytes codec2 data>  # 0x10 = Codec2 codec ID
}
```

### Signalling Packet (FIELD_SIGNALLING = 0x00)

```
Python LXST format (msgpack dict):
{
    0x00: [<signal_byte>, ...]  # List of signals
}

Example for STATUS_ESTABLISHED:
{
    0x00: [0x06]  # Single signal
}

Example for profile change:
{
    0x00: [0x40]  # 0xFF + PROFILE_MQ (0x40 - 0xFF = profile)
}
```

### Combined Packet (Audio + Signalling)

```
{
    0x00: [0x06],              # Signalling
    0x01: b'\x40<opus_data>'   # Audio frame
}
```

## Codec Header Bytes

From Python LXST Codecs.py codec_header_byte():

| Codec | Header Byte | Python Constant |
|-------|-------------|-----------------|
| Null | 0x00 | Null() |
| Raw | 0x01 | Raw() |
| Opus | 0x10-0x1F | Opus() (varies by profile) |
| Codec2 | 0x20-0x2F | Codec2() (varies by mode) |

Kotlin codec must use matching header bytes for wire compatibility.

## Open Questions

1. **Eager vs lazy initialization**
   - Context says "eager at app start (no latency on first call)"
   - Recommendation: Initialize NetworkPacketBridge in PythonWrapperManager alongside audio/call bridges

2. **Queue depth mismatch**
   - Context says "max 8 packets, drop oldest if full"
   - Python LXST LinkSource doesn't use explicit queue (processes inline)
   - Recommendation: Use queue for thread safety, 8 packet depth matches Mixer.MAX_FRAMES

3. **Profile change initiator**
   - Context: "Claude's Discretion - research Telephone.py behavior"
   - Finding: Either side can initiate profile change by sending PREFERRED_PROFILE + profile_byte
   - Telephone.py:482-491 shows switch_profile() sends signal
   - Recommendation: Support both directions, match Python behavior

4. **Error handling on bridge crash**
   - Context: "End call gracefully (signal call ended, cleanup resources)"
   - Recommendation: Wrap bridge calls in try-catch, call hangup() on failure

## Sources

### Primary (HIGH confidence)

- Python LXST Network.py - Complete reference implementation
  - `/home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Network.py`
  - Lines 13-145: SignallingReceiver, Packetizer, LinkSource

- Python LXST Telephony.py - Integration patterns and signalling
  - `/home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Primitives/Telephony.py`
  - Lines 102-113: Signalling constants
  - Lines 612-626: Packetizer usage

- Existing Kotlin bridges (KotlinAudioBridge, CallBridge)
  - `/home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/bridge/KotlinAudioBridge.kt`
  - `/home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/call/bridge/CallBridge.kt`

- Python call_manager.py - Existing Python-Kotlin integration
  - `/home/tyler/repos/public/columba/python/lxst_modules/call_manager.py`

- Phase 9 Research - Mixer, Pipeline patterns
  - `/home/tyler/repos/public/columba/.planning/phases/09-mixer-pipeline/09-RESEARCH.md`

### Secondary (MEDIUM confidence)

- [Chaquopy cross-language documentation](https://chaquo.com/chaquopy/doc/current/cross.html)
  - Threading/GIL behavior
  - Data type conversions

- [Chaquopy GitHub issues on callbacks](https://github.com/chaquo/chaquopy/issues/341)
  - Callback patterns from community

## Metadata

**Confidence breakdown:**
- Packetizer pattern: HIGH - Python code is clear, existing bridge patterns work
- LinkSource pattern: HIGH - Callback pattern from KotlinAudioBridge proven
- SignallingReceiver: HIGH - Simple byte protocol, well-documented
- Threading model: MEDIUM - Context recommends dedicated thread, feasibility confirmed
- Latency target (<5ms): MEDIUM - Existing bridges achieve sub-ms, but network adds variance

**Research date:** 2026-02-04
**Valid until:** 60 days (stable domain, Python LXST Network.py unlikely to change)

**Key files for planning:**
1. Network.py (lines 13-145) - Primary reference for all three components
2. Telephony.py (lines 102-113, 612-626) - Signalling constants and integration
3. KotlinAudioBridge.kt - Proven Chaquopy callback pattern
4. CallBridge.kt - State management pattern
5. Mixer.kt - Queue and backpressure pattern from Phase 9
