# Phase 8: Audio Sources & Sinks - Research

**Researched:** 2026-02-04
**Domain:** Android audio capture/playback, LXST audio pipeline architecture
**Confidence:** HIGH

## Summary

Phase 8 implements LineSource (microphone capture) and LineSink (speaker playback) in Kotlin by wrapping the existing KotlinAudioBridge. The Python LXST reference implementation provides clear patterns: LineSource runs a dedicated thread that captures audio frames, applies filters, and pushes to a codec; LineSink runs a dedicated thread that pulls decoded frames from a queue and plays them with underrun handling.

The architecture is **push-based**: LineSource captures → filters → encodes → pushes to sink. LineSink accepts decoded frames into a queue → digest thread pulls and plays. Codec is external to both source and sink; pipeline orchestration (Phase 9) wires them together.

KotlinAudioBridge already implements the hard parts: AudioRecord/AudioTrack management, ring buffer queuing, background recording loop, and low-latency mode support. KotlinAudioFilters already provides <1ms filter chain (HighPass, LowPass, AGC). Phase 8 wraps these with LXST-compatible LineSource/LineSink interfaces.

**Primary recommendation:** Match Python LXST Sources.py/Sinks.py class structure exactly (this is a port, not a redesign). Wrap KotlinAudioBridge internals, expose LXST-compatible public API (start/stop, handle_frame, can_receive).

## Standard Stack

### Core Android Audio APIs

| API | Purpose | Why Standard |
|-----|---------|--------------|
| AudioRecord | Microphone capture | Android native audio input - only option for mic access |
| AudioTrack | Speaker playback | Android native audio output - only option for speaker access |
| AudioManager | Audio routing (speaker/earpiece) | System audio configuration and device enumeration |
| MediaRecorder.AudioSource.MIC | Audio source type | Standard mic input for voice apps |
| AudioAttributes.USAGE_VOICE_COMMUNICATION | Playback usage | Enables AEC reference and voice optimizations |

### Existing Columba Components (Phase 7)

| Component | Version | Purpose | Status |
|-----------|---------|---------|--------|
| KotlinAudioBridge | Current | AudioRecord/AudioTrack wrapper | ✓ Implemented, working |
| KotlinAudioFilters | Current | HighPass/LowPass/AGC filters | ✓ Verified <1ms latency |
| Codec (base class) | Current | encode(FloatArray) → ByteArray interface | ✓ Defined, needs wiring |
| Opus codec | Phase 7 | 9 profiles, wire-compatible | ⚠️ Code exists, untested |
| Codec2 codec | Phase 7 | 7 modes, wire-compatible | ⚠️ Code exists, untested |

### Dependencies

Already in build.gradle.kts:
```kotlin
// Coroutines for background threads
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

**Installation:** No new dependencies required — everything needed is already present.

## Architecture Patterns

### Python LXST Reference Structure

Python LXST defines three key classes in Sources.py and Sinks.py:

```
Sources.py:
  - Source (base class)
  - LocalSource (subclass for local audio)
  - LineSource(LocalSource) — microphone capture
  - OpusFileSource(LocalSource) — file playback (out of scope)

Sinks.py:
  - Sink (base interface)
  - LocalSink (subclass for local audio)
  - LineSink(LocalSink) — speaker playback
```

### Recommended Kotlin Package Structure

```
reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/
├── bridge/
│   ├── KotlinAudioBridge.kt       # Existing — AudioRecord/AudioTrack wrapper
│   └── KotlinAudioFilters.kt      # Existing — filter chain
├── codec/
│   ├── Codec.kt                   # Existing — base class
│   ├── Opus.kt                    # Existing — 9 profiles
│   └── Codec2.kt                  # Existing — 7 modes
└── lxst/                          # NEW in Phase 8
    ├── Source.kt                  # Base classes: Source, LocalSource
    ├── LineSource.kt              # Microphone capture implementation
    ├── Sink.kt                    # Base classes: Sink, LocalSink
    └── LineSink.kt                # Speaker playback implementation
```

### Pattern 1: LineSource - Push-Based Capture

**What:** Dedicated thread captures mic audio, applies filters, encodes, pushes frames to sink.

**When to use:** This is the only capture pattern — all audio sources push frames.

**Python LXST implementation (Sources.py:159-265):**
```python
class LineSource(LocalSource):
    def __init__(self, preferred_device=None, target_frame_ms=80,
                 codec=None, sink=None, filters=None, gain=0.0):
        self.codec = codec
        self.sink = sink
        self.filters = filters  # List of filter objects
        self.ingest_thread = None
        self.should_run = False
        # ... backend initialization

    def start(self):
        self.should_run = True
        self.ingest_thread = threading.Thread(target=self.__ingest_job, daemon=True)
        self.ingest_thread.start()

    def __ingest_job(self):
        with self.backend.get_recorder(samples_per_frame=self.samples_per_frame) as recorder:
            while self.should_run:
                frame_samples = recorder.record(numframes=self.samples_per_frame)

                # Apply filters (each filter has handle_frame method)
                if self.filters != None:
                    for f in self.filters:
                        frame_samples = f.handle_frame(frame_samples, self.samplerate)

                # Apply gain
                if self.__gain != 1.0:
                    frame_samples *= self.__gain

                # Encode and push to sink
                if self.codec:
                    frame = self.codec.encode(frame_samples)
                    if self.sink and self.sink.can_receive(from_source=self):
                        self.sink.handle_frame(frame, self)
```

**Key observations:**
1. **Filter placement:** Filters are applied INSIDE the source, before encoding
2. **Float32 throughout:** Backend provides float32, filters process float32, codec converts to int16 internally
3. **Push model:** Source calls `sink.handle_frame()` — sink doesn't pull
4. **Backpressure:** Checks `sink.can_receive()` before pushing (prevents overflow)
5. **Thread safety:** Single background thread owns capture loop

**Kotlin equivalent pattern:**
```kotlin
class LineSource(
    private val bridge: KotlinAudioBridge,
    private val codec: Codec,
    private val targetFrameMs: Int = 80,
    private val filters: List<AudioFilter>? = null,
    private val gain: Float = 1.0f
) : LocalSource() {
    var sink: Sink? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    fun start() {
        if (!isRunning.getAndSet(true)) {
            bridge.startRecording(sampleRate, channels, samplesPerFrame)
            scope.launch { ingestJob() }
        }
    }

    private suspend fun ingestJob() {
        while (isRunning.get()) {
            val frameBytes = bridge.readAudio(samplesPerFrame) ?: continue

            // Convert int16 bytes to float32
            val frameSamples = bytesToFloat32(frameBytes)

            // Apply filters (Kotlin filters already in bridge, or external here)
            val filtered = applyFilters(frameSamples)

            // Apply gain
            val gained = if (gain != 1.0f) filtered.map { it * gain } else filtered

            // Encode
            val encoded = codec.encode(gained.toFloatArray())

            // Push to sink with backpressure
            val currentSink = sink
            if (currentSink != null && currentSink.canReceive(this)) {
                currentSink.handleFrame(encoded, this)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        bridge.stopRecording()
    }
}
```

### Pattern 2: LineSink - Queue-Based Playback

**What:** Accepts decoded frames into queue, digest thread pulls and plays with underrun handling.

**When to use:** This is the only playback pattern — all sinks accept frames and manage playback timing.

**Python LXST implementation (Sinks.py:118-219):**
```python
class LineSink(LocalSink):
    MAX_FRAMES = 6  # Queue depth
    AUTOSTART_MIN = 1  # Start playback when 1 frame ready
    FRAME_TIMEOUT = 8  # Stop after 8 frame times of silence

    def __init__(self, preferred_device=None, autodigest=True, low_latency=False):
        self.frame_deque = deque(maxlen=self.MAX_FRAMES)
        self.autodigest = autodigest
        self.buffer_max_height = self.MAX_FRAMES - 3
        self.digest_thread = None
        self.should_run = False
        # ... backend initialization

    def can_receive(self, from_source=None):
        with self.insert_lock:
            if len(self.frame_deque) < self.buffer_max_height:
                return True
            else:
                return False

    def handle_frame(self, frame, source=None):
        with self.insert_lock:
            self.frame_deque.append(frame)

        # Auto-start playback when buffer has minimum frames
        if self.autodigest and not self.should_run:
            if len(self.frame_deque) >= self.autostart_min:
                self.start()

    def start(self):
        if not self.should_run:
            self.should_run = True
            self.digest_thread = threading.Thread(target=self.__digest_job, daemon=True)
            self.digest_thread.start()

    def __digest_job(self):
        with self.backend.get_player(samples_per_frame=self.samples_per_frame,
                                      low_latency=self.low_latency) as player:
            while self.should_run:
                frames_ready = len(self.frame_deque)
                if frames_ready:
                    with self.insert_lock:
                        frame = self.frame_deque.popleft()

                    # Trim channels if needed
                    if frame.shape[1] > self.channels:
                        frame = frame[:, 0:self.channels]

                    player.play(frame)

                    # Drop oldest if lag
                    if len(self.frame_deque) > self.buffer_max_height:
                        self.frame_deque.popleft()
                else:
                    # Underrun handling
                    if self.underrun_at == None:
                        self.underrun_at = time.time()
                    else:
                        if time.time() > self.underrun_at + (self.frame_time * self.frame_timeout):
                            self.should_run = False  # Stop playback
                        else:
                            time.sleep(self.frame_time * 0.1)
```

**Key observations:**
1. **Queue-based:** Incoming frames go to deque, digest thread pulls from deque
2. **Autostart:** Playback begins automatically when buffer has minimum frames
3. **Backpressure:** `can_receive()` returns false when queue near full
4. **Underrun handling:** Stops playback after timeout, doesn't insert silence
5. **Buffer lag handling:** Drops oldest frame if queue exceeds max height
6. **Thread safety:** insert_lock protects queue, digest_lock protects playback thread

**Kotlin equivalent pattern:**
```kotlin
class LineSink(
    private val bridge: KotlinAudioBridge,
    private val autodigest: Boolean = true,
    private val lowLatency: Boolean = false
) : LocalSink() {
    companion object {
        const val MAX_FRAMES = 6
        const val AUTOSTART_MIN = 1
        const val FRAME_TIMEOUT_FRAMES = 8
    }

    private val frameQueue = LinkedBlockingQueue<FloatArray>(MAX_FRAMES)
    private val bufferMaxHeight = MAX_FRAMES - 3
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun canReceive(fromSource: Source? = null): Boolean {
        return frameQueue.size < bufferMaxHeight
    }

    fun handleFrame(frame: FloatArray, source: Source? = null) {
        // Non-blocking offer, drops oldest if full
        if (!frameQueue.offer(frame)) {
            frameQueue.poll()  // Remove oldest
            frameQueue.offer(frame)
        }

        // Auto-start when buffer has minimum frames
        if (autodigest && !isRunning.get() && frameQueue.size >= AUTOSTART_MIN) {
            start()
        }
    }

    fun start() {
        if (!isRunning.getAndSet(true)) {
            bridge.startPlayback(sampleRate, channels, lowLatency)
            scope.launch { digestJob() }
        }
    }

    private suspend fun digestJob() {
        var underrunStartTime: Long? = null

        while (isRunning.get()) {
            val frame = frameQueue.poll(10, TimeUnit.MILLISECONDS)

            if (frame != null) {
                underrunStartTime = null

                // Convert float32 to int16 bytes
                val frameBytes = float32ToBytes(frame)
                bridge.writeAudio(frameBytes)

                // Drop oldest if lagging
                if (frameQueue.size > bufferMaxHeight) {
                    frameQueue.poll()
                }
            } else {
                // Underrun handling
                if (underrunStartTime == null) {
                    underrunStartTime = System.currentTimeMillis()
                } else {
                    val underrunDuration = System.currentTimeMillis() - underrunStartTime
                    val timeoutMs = frameTimeMs * FRAME_TIMEOUT_FRAMES
                    if (underrunDuration > timeoutMs) {
                        isRunning.set(false)  // Stop playback
                    }
                }
            }
        }

        bridge.stopPlayback()
    }

    fun stop() {
        isRunning.set(false)
        frameQueue.clear()
    }
}
```

### Pattern 3: Codec Integration (External)

**What:** Codec is separate from source/sink; pipeline wires them together.

**Python LXST pattern (Sources.py:196-208):**
```python
# LineSource has codec as constructor parameter
def __init__(self, codec=None, sink=None, filters=None):
    self._codec = None
    self.codec = codec  # Uses @codec.setter
    self.sink = sink

@codec.setter
def codec(self, codec):
    if codec == None:
        self._codec = None
    elif not issubclass(type(codec), Codec):
        raise CodecError(f"Invalid codec specified for {self}")
    else:
        self._codec = codec
        # Adjust frame size based on codec constraints
        if self.codec.frame_quanta_ms:
            if self.target_frame_ms % self.codec.frame_quanta_ms != 0:
                self.target_frame_ms = math.ceil(...)
```

**Key insight:** Source doesn't instantiate codec — it's injected. Source queries codec properties (preferred_samplerate, frame_quanta_ms, frame_max_ms) to adjust capture parameters.

**Kotlin pattern:**
```kotlin
abstract class Codec {
    var preferredSamplerate: Int? = null
    var frameQuantaMs: Float? = null
    var frameMaxMs: Float? = null
    var validFrameMs: List<Float>? = null

    abstract fun encode(frame: FloatArray): ByteArray
    abstract fun decode(frameBytes: ByteArray): FloatArray
}

class LineSource(
    private val bridge: KotlinAudioBridge,
    codec: Codec,
    targetFrameMs: Int = 80
) {
    private var _codec: Codec = codec
    private var adjustedFrameMs: Int = targetFrameMs

    init {
        // Adjust frame size based on codec constraints
        adjustedFrameMs = adjustFrameSize(targetFrameMs, codec)
    }

    private fun adjustFrameSize(targetMs: Int, codec: Codec): Int {
        var adjusted = targetMs

        // Quantize to codec frame quanta
        codec.frameQuantaMs?.let { quanta ->
            if (adjusted % quanta.toInt() != 0) {
                adjusted = ceil(adjusted / quanta) * quanta.toInt()
            }
        }

        // Clamp to codec max frame size
        codec.frameMaxMs?.let { maxMs ->
            if (adjusted > maxMs) adjusted = maxMs.toInt()
        }

        // Snap to valid frame sizes
        codec.validFrameMs?.let { valid ->
            adjusted = valid.minByOrNull { abs(it - adjusted) }?.toInt() ?: adjusted
        }

        return adjusted
    }
}
```

### Anti-Patterns to Avoid

- **Don't make LineSource/LineSink singletons:** Allow multiple instances for multi-call scenarios (future)
- **Don't hardcode frame sizes:** Let codec constraints dictate actual frame size
- **Don't insert silence on underrun:** Python LXST stops playback instead (cleaner, avoids phantom audio)
- **Don't expose buffer metrics:** Keep buffer fill level, underrun count internal (simpler API)
- **Don't apply filters in sink:** Filters are capture-side only (Python LXST pattern)

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Audio capture/playback | Custom AudioRecord/AudioTrack wrapper | KotlinAudioBridge | Already working, tested, handles edge cases (Samsung routing, etc.) |
| Audio filters | Python/CFFI filters or new DSP code | KotlinAudioFilters | Already optimized <1ms, proven correct |
| Ring buffer for recording | Custom queue with manual indexing | LinkedBlockingQueue | Thread-safe, well-tested, handles blocking/timeout |
| Background threading | Manual Thread() management | Kotlin Coroutines (already in use) | Structured concurrency, cancellation, better error handling |
| Float32 ↔ int16 conversion | Custom bit manipulation | Codec.bytesToSamples / samplesToBytes | Already implemented, handles endianness correctly |
| Sample rate conversion | Custom interpolation | Codec.resample() | Already implemented (Phase 7) |

**Key insight:** KotlinAudioBridge already does the hard parts. LineSource/LineSink are thin wrappers that add LXST semantics (can_receive, handle_frame, codec integration) on top of bridge primitives.

## Common Pitfalls

### Pitfall 1: Filter Chain Ownership Confusion

**What goes wrong:** Unclear whether filters live in LineSource or KotlinAudioBridge, leading to double-filtering or no filtering.

**Why it happens:** KotlinAudioBridge already has KotlinAudioFilters baked into recording loop (lines 474-487 in KotlinAudioBridge.kt). Python LXST has filters as LineSource parameters.

**How to avoid:**
1. **Option A (Python-compatible):** Disable KotlinAudioBridge's internal filters (`setFiltersEnabled(false)`), apply filters in LineSource
2. **Option B (Performance):** Let bridge filters run (already <1ms), don't add external filters

**Recommendation:** Use Option B — bridge filters are already proven fast. Make LineSource's `filters` parameter a no-op for now (Phase 9 can make it configurable if needed).

**Warning signs:** Audio sounds heavily processed or distorted → filters applied twice.

### Pitfall 2: Data Format Confusion (Float32 vs Int16)

**What goes wrong:** LineSource tries to encode int16 data thinking it's float32, causing codec errors or distorted audio.

**Why it happens:** KotlinAudioBridge returns int16 bytes from `readAudio()`, but Codec.encode() expects float32.

**How to avoid:**
```kotlin
// WRONG: Pass bytes directly to codec
val frameBytes = bridge.readAudio(samplesPerFrame)
val encoded = codec.encode(frameBytes)  // ERROR: codec expects FloatArray

// RIGHT: Convert first
val frameBytes = bridge.readAudio(samplesPerFrame) ?: return
val frameSamples = bytesToFloat32(frameBytes)  // int16 → float32
val encoded = codec.encode(frameSamples)
```

**Helper functions needed:**
```kotlin
// In LineSource companion object
fun bytesToFloat32(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 2) { buffer.short / 32768f }
}

// In LineSink companion object
fun float32ToBytes(samples: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { sample ->
        val int16 = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        buffer.putShort(int16)
    }
    return buffer.array()
}
```

**Warning signs:** Codec crashes with "invalid frame size" or audio is pure noise.

### Pitfall 3: Deadlock Between Source and Sink

**What goes wrong:** LineSource blocks waiting for sink, sink blocks waiting for lock, both threads freeze.

**Why it happens:** `can_receive()` holds lock while checking queue, `handle_frame()` waits for same lock, ingest thread waits on `handle_frame()`.

**How to avoid:**
- Use `LinkedBlockingQueue` instead of manual locking (offer/poll are atomic)
- Make `can_receive()` lock-free: just check `queue.size < threshold`
- Make `handle_frame()` non-blocking: use `offer()` with drop-oldest fallback

**Python LXST uses explicit locks (Sources.py:172, Sinks.py:128), but Kotlin has better options:**
```kotlin
// Python-style (requires manual locks)
private val insertLock = Mutex()
fun canReceive(): Boolean = runBlocking {
    insertLock.withLock { frameQueue.size < bufferMaxHeight }
}

// Better Kotlin style (lock-free)
private val frameQueue = LinkedBlockingQueue<FloatArray>(MAX_FRAMES)
fun canReceive(): Boolean {
    return frameQueue.size < bufferMaxHeight  // Thread-safe read, no lock
}
```

**Warning signs:** App freezes during audio call, ANR dialog appears.

### Pitfall 4: Underrun Handling Creates Feedback Loop

**What goes wrong:** Sink stops on underrun, source keeps capturing and encoding, sink never restarts, audio call is dead.

**Why it happens:** Python LXST sink stops playback after FRAME_TIMEOUT (Sinks.py:205-207), but doesn't signal source to stop or restart.

**How to avoid:**
1. **Match Python behavior:** Sink stops playback, source keeps running (Phase 9 call manager detects silence and ends call)
2. **Don't auto-restart sink:** Let higher-level orchestrator decide when to restart
3. **Log underrun events:** Make debugging easier

**Python LXST pattern:**
```python
if time.time() > self.underrun_at + (self.frame_time * self.frame_timeout):
    RNS.log(f"No frames available on {self}, stopping playback", RNS.LOG_DEBUG)
    self.should_run = False  # Stop digest thread, don't restart
```

**Warning signs:** Audio plays for a few seconds then stops permanently, call seems "stuck."

### Pitfall 5: Sample Rate Mismatch Between Capture and Codec

**What goes wrong:** AudioRecord captures at 48kHz, codec expects 8kHz, frame size is wrong, codec crashes.

**Why it happens:** Codec has `preferredSamplerate` (e.g., 8000 for Codec2), but LineSource doesn't query it.

**How to avoid:**
```kotlin
class LineSource(
    private val bridge: KotlinAudioBridge,
    private val codec: Codec,
    targetFrameMs: Int = 80
) {
    private val sampleRate: Int
    private val samplesPerFrame: Int

    init {
        // Use codec's preferred sample rate if specified
        sampleRate = codec.preferredSamplerate ?: 48000

        // Calculate samples per frame
        samplesPerFrame = ((targetFrameMs / 1000f) * sampleRate).toInt()

        // Start recording at codec's preferred rate
        bridge.startRecording(sampleRate, channels = 1, samplesPerFrame)
    }
}
```

**Python LXST does this in codec setter (Sources.py:202-204):**
```python
if self.codec.preferred_samplerate:
    self.preferred_samplerate = self.codec.preferred_samplerate
else:
    self.preferred_samplerate = Backend.SAMPLERATE
```

**Warning signs:** Codec crashes with "frame size mismatch" or "invalid sample count."

## Code Examples

### Example 1: LineSource Initialization and Startup

```kotlin
// Source: Analysis of Python LXST Sources.py:159-232 and KotlinAudioBridge.kt

class LineSource(
    private val bridge: KotlinAudioBridge,
    codec: Codec,
    targetFrameMs: Int = 80,
    private val gain: Float = 1.0f
) : LocalSource() {
    var sink: Sink? = null

    private val codec: Codec = codec
    private val sampleRate: Int
    private val samplesPerFrame: Int
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    init {
        // Query codec for preferred sample rate
        sampleRate = codec.preferredSamplerate ?: DEFAULT_SAMPLE_RATE

        // Adjust frame time based on codec constraints
        val adjustedFrameMs = adjustFrameTime(targetFrameMs, codec)

        // Calculate samples per frame
        samplesPerFrame = ((adjustedFrameMs / 1000f) * sampleRate).toInt()

        Log.d(TAG, "LineSource initialized: rate=$sampleRate, frameMs=$adjustedFrameMs, samples=$samplesPerFrame")
    }

    fun start() {
        if (!isRunning.getAndSet(true)) {
            Log.i(TAG, "Starting LineSource")
            bridge.startRecording(
                sampleRate = sampleRate,
                channels = 1,  // Mono for voice
                samplesPerFrame = samplesPerFrame
            )
            scope.launch { ingestJob() }
        }
    }

    fun stop() {
        if (isRunning.getAndSet(false)) {
            Log.i(TAG, "Stopping LineSource")
            bridge.stopRecording()
            scope.cancel()
        }
    }

    private fun adjustFrameTime(targetMs: Int, codec: Codec): Int {
        var adjusted = targetMs

        // Quantize to codec frame quanta (e.g., Opus requires 2.5ms multiples)
        codec.frameQuantaMs?.let { quanta ->
            if (adjusted % quanta.toInt() != 0) {
                adjusted = ((adjusted + quanta.toInt() - 1) / quanta.toInt()) * quanta.toInt()
                Log.d(TAG, "Frame time quantized to ${adjusted}ms for codec")
            }
        }

        // Clamp to codec max frame size (e.g., Opus max 120ms)
        codec.frameMaxMs?.let { maxMs ->
            if (adjusted > maxMs.toInt()) {
                adjusted = maxMs.toInt()
                Log.d(TAG, "Frame time clamped to ${adjusted}ms for codec")
            }
        }

        return adjusted
    }

    companion object {
        private const val TAG = "Columba:LineSource"
        private const val DEFAULT_SAMPLE_RATE = 48000
    }
}
```

### Example 2: LineSource Capture and Encode Loop

```kotlin
// Source: Python LXST Sources.py:235-265

private suspend fun ingestJob() {
    Log.d(TAG, "Ingest job started")
    var frameCount = 0L

    while (isRunning.get()) {
        // Read raw audio from bridge (int16 bytes)
        val frameBytes = bridge.readAudio(samplesPerFrame)
        if (frameBytes == null) {
            delay(10)  // Brief pause if no data
            continue
        }

        frameCount++

        // Convert int16 bytes to float32 samples
        val frameSamples = bytesToFloat32(frameBytes)

        // Apply gain if not unity
        if (gain != 1.0f) {
            for (i in frameSamples.indices) {
                frameSamples[i] *= gain
            }
        }

        // Encode with codec
        val encoded = try {
            codec.encode(frameSamples)
        } catch (e: Exception) {
            Log.e(TAG, "Codec encode error on frame $frameCount", e)
            continue
        }

        // Push to sink with backpressure check
        val currentSink = sink
        if (currentSink != null && currentSink.canReceive(this)) {
            currentSink.handleFrame(encoded, this)
        } else {
            // Sink can't receive — drop frame
            if (frameCount % 50L == 0L) {
                Log.w(TAG, "Sink backpressure, dropping frames")
            }
        }
    }

    Log.d(TAG, "Ingest job ended, captured $frameCount frames")
}

private fun bytesToFloat32(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 2) { buffer.short / 32768f }
}
```

### Example 3: LineSink Frame Handling and Autostart

```kotlin
// Source: Python LXST Sinks.py:149-165

class LineSink(
    private val bridge: KotlinAudioBridge,
    private val autodigest: Boolean = true,
    private val lowLatency: Boolean = false
) : LocalSink() {
    private val frameQueue = LinkedBlockingQueue<FloatArray>(MAX_FRAMES)
    private val bufferMaxHeight = MAX_FRAMES - 3
    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sampleRate: Int = 0
    private var channels: Int = 0

    override fun canReceive(fromSource: Source?): Boolean {
        return frameQueue.size < bufferMaxHeight
    }

    override fun handleFrame(frame: FloatArray, source: Source?) {
        // Detect sample rate and channels from first frame
        if (sampleRate == 0) {
            // For now, assume 48kHz mono (Phase 9 will get from source)
            sampleRate = source?.sampleRate ?: 48000
            channels = 1
            Log.i(TAG, "LineSink starting: rate=$sampleRate, channels=$channels")
        }

        // Non-blocking offer, drop oldest if full
        if (!frameQueue.offer(frame)) {
            frameQueue.poll()  // Remove oldest frame
            frameQueue.offer(frame)
            Log.w(TAG, "Buffer overflow, dropped oldest frame")
        }

        // Auto-start playback when buffer has minimum frames
        if (autodigest && !isRunning.get() && frameQueue.size >= AUTOSTART_MIN) {
            start()
        }
    }

    fun start() {
        if (!isRunning.getAndSet(true)) {
            Log.i(TAG, "Starting LineSink playback")
            bridge.startPlayback(
                sampleRate = sampleRate,
                channels = channels,
                lowLatency = lowLatency
            )
            scope.launch { digestJob() }
        }
    }

    fun stop() {
        if (isRunning.getAndSet(false)) {
            Log.i(TAG, "Stopping LineSink playback")
            frameQueue.clear()
        }
    }

    companion object {
        private const val TAG = "Columba:LineSink"
        private const val MAX_FRAMES = 6
        private const val AUTOSTART_MIN = 1
        private const val FRAME_TIMEOUT_FRAMES = 8
    }
}
```

### Example 4: LineSink Digest Loop with Underrun Handling

```kotlin
// Source: Python LXST Sinks.py:178-217

private suspend fun digestJob() {
    Log.d(TAG, "Digest job started")
    var frameCount = 0L
    var underrunStartMs: Long? = null
    var frameTimeMs: Long = 20  // Default, calculated from first frame

    while (isRunning.get()) {
        val frame = frameQueue.poll(10, TimeUnit.MILLISECONDS)

        if (frame != null) {
            // Clear underrun state
            underrunStartMs = null
            frameCount++

            // Calculate frame time from first frame
            if (frameCount == 1L) {
                frameTimeMs = ((frame.size.toFloat() / sampleRate) * 1000).toLong()
                Log.d(TAG, "Frame time: ${frameTimeMs}ms")
            }

            // Convert float32 to int16 bytes
            val frameBytes = float32ToBytes(frame)

            // Write to AudioTrack
            bridge.writeAudio(frameBytes)

            // Drop oldest if buffer is lagging
            if (frameQueue.size > bufferMaxHeight) {
                frameQueue.poll()
                Log.w(TAG, "Buffer lag, dropped oldest frame (height=${frameQueue.size})")
            }
        } else {
            // Underrun: no frames available
            if (underrunStartMs == null) {
                underrunStartMs = System.currentTimeMillis()
                Log.d(TAG, "Buffer underrun started")
            } else {
                // Check timeout
                val underrunDurationMs = System.currentTimeMillis() - underrunStartMs
                val timeoutMs = frameTimeMs * FRAME_TIMEOUT_FRAMES

                if (underrunDurationMs > timeoutMs) {
                    Log.i(TAG, "No frames for ${underrunDurationMs}ms, stopping playback")
                    isRunning.set(false)
                } else {
                    // Brief sleep during underrun
                    delay(frameTimeMs / 10)
                }
            }
        }
    }

    Log.d(TAG, "Digest job ended, played $frameCount frames")
    bridge.stopPlayback()
}

private fun float32ToBytes(samples: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { sample ->
        val clamped = sample.coerceIn(-1f, 1f)
        val int16 = (clamped * 32767f).toInt().toShort()
        buffer.putShort(int16)
    }
    return buffer.array()
}
```

### Example 5: Base Class Hierarchy (Matching Python LXST)

```kotlin
// Source: Python LXST Sources.py:121-123, Sinks.py:111-116

// Base classes matching Python LXST structure
abstract class Source {
    abstract var sampleRate: Int
    abstract var channels: Int
}

abstract class LocalSource : Source()

abstract class Sink {
    abstract fun canReceive(fromSource: Source? = null): Boolean
    abstract fun handleFrame(frame: ByteArray, source: Source?)
}

abstract class LocalSink : Sink()

// Remote sources/sinks for future network audio (out of scope for Phase 8)
abstract class RemoteSource : Source()
abstract class RemoteSink : Sink()
```

## State of the Art

### Python LXST (Current Reference Implementation)

| Component | Implementation | Notes |
|-----------|----------------|-------|
| LineSource | Dedicated thread, push model | Sources.py:159-265 |
| LineSink | Queue + digest thread, autostart | Sinks.py:118-219 |
| Filters | Applied in source before encoding | Sources.py:252-253 |
| Backend abstraction | Platform-specific (Linux/Android/Darwin/Windows) | Sources.py:12-119, Sinks.py:10-109 |
| Threading | `threading.Thread(daemon=True)` | Manual thread management |
| Locks | `threading.Lock()` for queue protection | Sources.py:132, Sinks.py:127-128 |

### Kotlin/Android Modern Patterns

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Manual Thread() | Kotlin Coroutines | Kotlin 1.3+ (2018) | Structured concurrency, better cancellation |
| Manual locks | LinkedBlockingQueue | Java 5+ (2004) | Lock-free queue operations |
| Synchronized blocks | Atomic primitives | Java 5+ (2004) | Better performance for flags |
| AudioRecord.read() blocking | Non-blocking with timeout | Always available | Better responsiveness |

### Android Low-Latency Audio Evolution

| Feature | Introduced | Notes |
|---------|------------|-------|
| AudioTrack.PERFORMANCE_MODE_LOW_LATENCY | Android 8.0 (API 26) | Reduces output latency to ~10-20ms |
| AudioRecord low-latency path | Android 4.2+ (API 17) | Enabled by buffer size tuning |
| MODE_IN_COMMUNICATION | Always available | Enables AEC, AGC, NS automatically |

**Deprecated/outdated:**
- `setSpeakerphoneOn()`: Deprecated in Android 12 (API 31), replaced by `setCommunicationDevice()`
- Manual AEC/AGC/NS: Built into MODE_IN_COMMUNICATION, no manual filter needed

## Open Questions

### 1. Should filters be in LineSource or delegated to KotlinAudioBridge?

**What we know:**
- Python LXST: Filters are LineSource parameters, applied in capture loop (Sources.py:186-188, 252-253)
- KotlinAudioBridge: Already has KotlinAudioFilters integrated in recording loop (KotlinAudioBridge.kt:474-487)
- Performance: Bridge filters already proven <1ms per frame

**What's unclear:**
- Whether Python filters are always used or sometimes disabled
- Whether "no filters" is a valid LXST configuration
- Whether runtime filter chain changes are needed

**Recommendation:**
- Phase 8: Use bridge's internal filters (already working), make LineSource `filters` parameter optional/no-op
- Phase 9: Make filters configurable via profile if needed

### 2. How does sample rate conversion interact with codec requirements?

**What we know:**
- Opus prefers 48kHz (can also do 24kHz, 16kHz, 8kHz) - Opus.kt:37-49
- Codec2 requires 8kHz (fixed) - Codec2.kt:25
- KotlinAudioBridge defaults to 48kHz
- Codec.resample() exists for sample rate conversion (Codec.kt:81-159)

**What's unclear:**
- Who does resampling: LineSource before encode, or codec internally?
- Python LXST backend creates recorder at codec's preferred rate (Sources.py:220)
- But Android AudioRecord might not support 8kHz on all devices

**Recommendation:**
- Phase 8: LineSource captures at codec's preferredSamplerate if available
- If device doesn't support it, capture at 48kHz and resample before encode
- Let KotlinAudioBridge.startRecording() fail gracefully, fallback to 48kHz + resample

### 3. What about multi-channel audio (stereo)?

**What we know:**
- Voice calls typically use mono (channels=1)
- Python LXST supports multi-channel (Sources.py:223, Sinks.py:140)
- Some Opus profiles use stereo (Opus.PROFILE_AUDIO_* series)

**What's unclear:**
- Does Columba need stereo for any use case?
- Phase requirements specify "voice communication" (mono)

**Recommendation:**
- Phase 8: Hardcode mono (channels=1) for simplicity
- Phase 9: Add stereo support if needed for music playback profiles

## Sources

### PRIMARY (HIGH confidence)

**Python LXST Reference Implementation:**
- /home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Sources.py (361 lines)
  - LineSource class (lines 159-265): capture loop, filter integration, codec handoff
  - Backend abstraction pattern (lines 12-119): platform-specific device management

- /home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Sinks.py (348 lines)
  - LineSink class (lines 118-219): queue management, digest thread, underrun handling
  - Backend abstraction pattern (lines 10-109): platform-specific playback device management

**Existing Kotlin Implementation:**
- /home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/bridge/KotlinAudioBridge.kt (957 lines)
  - AudioRecord/AudioTrack wrapper (working, tested)
  - Recording loop with ring buffer (lines 517-604)
  - Playback with writeAudio (lines 257-312)
  - Filter integration (lines 474-496)

- /home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/bridge/KotlinAudioFilters.kt (304 lines)
  - VoiceFilterChain (HighPass, LowPass, AGC)
  - Verified <1ms latency per frame

- /home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/codec/Codec.kt (198 lines)
  - Base Codec interface with encode/decode
  - Helper functions: resample, bytesToSamples, samplesToBytes

**Phase 7 Status:**
- /home/tyler/repos/public/columba/.planning/phases/07-codec-foundation/07-VERIFICATION.md
  - Codec base class verified working
  - Opus/Codec2 code exists but untested (encode/decode methods not verified)
  - Null codec working with round-trip tests

### SECONDARY (MEDIUM confidence)

**Android Audio APIs:**
- Official Android docs for AudioRecord, AudioTrack, AudioManager
- AudioTrack.PERFORMANCE_MODE_LOW_LATENCY introduced API 26
- MODE_IN_COMMUNICATION enables AEC/AGC/NS automatically

**Kotlin Coroutines:**
- Standard library docs for CoroutineScope, Dispatchers.IO, SupervisorJob
- LinkedBlockingQueue from java.util.concurrent

## Metadata

**Confidence breakdown:**
- Python LXST structure: HIGH - Direct analysis of Sources.py and Sinks.py
- Kotlin implementation approach: HIGH - Existing KotlinAudioBridge provides all primitives
- Filter placement: MEDIUM - Python LXST uses filters in source, but KotlinAudioBridge already has them
- Sample rate conversion: MEDIUM - Unclear whether source or codec does resampling
- Multi-channel support: LOW - Not specified in requirements, unclear if needed

**Research date:** 2026-02-04
**Valid until:** 60 days (stable Python LXST reference, stable Android APIs)

**Key dependencies:**
- Phase 7 (Codec Foundation) must complete encode/decode verification before Phase 8 integration
- KotlinAudioBridge must remain stable (don't refactor during Phase 8)
- Python LXST reference implementation remains authoritative for class structure
