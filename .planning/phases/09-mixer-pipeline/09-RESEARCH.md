# Phase 9: Mixer & Pipeline - Research

**Researched:** 2026-02-04
**Domain:** Python LXST audio mixing and pipeline orchestration, Kotlin port requirements
**Confidence:** HIGH

## Summary

Phase 9 implements Mixer (combines multiple audio sources with gain control) and Pipeline (orchestrates source→codec→sink flow) by porting Python LXST's Mixer.py, Pipeline.py, and Generators.py (ToneSource). The Python reference provides crystal-clear implementation patterns that must be matched exactly.

**Mixer architecture:** Push-based mixing where sources call `mixer.handleFrame()`. Mixer runs a background thread that pulls from per-source queues, applies per-source and global gain, adds frames together, clips to [-1.0, 1.0], encodes, and pushes to sink. Mute is implemented as gain=0 multiplier. Maximum 2 sources (local mic + remote stream) keeps complexity bounded.

**Pipeline architecture:** Thin wrapper that wires source.sink = sink, source.codec = codec, and provides unified start/stop lifecycle. Pipeline delegates all work to its components — it's pure coordination, no processing.

**ToneSource architecture:** Generate sine waves at configurable frequencies (382Hz dial tone per Python), with smooth fade in/out (20ms ease time). Used for dial tones and busy signals. Implements Source interface so it integrates naturally with mixer/pipeline.

**Primary recommendation:** This is a direct port. Match Python LXST structure method-by-method. Every class (Mixer, Pipeline, ToneSource) has clear Kotlin equivalent. Use coroutines instead of threads, ArrayDeque instead of Python deque, but preserve exact behavior.

## Standard Stack

### Core Libraries (Already Present)

| Library | Version | Purpose | Status |
|---------|---------|---------|--------|
| Kotlin coroutines | 1.7.3 | Background threads for mixing, tone generation | ✓ In build.gradle.kts |
| Kotlin stdlib | 1.9.x | ArrayDeque for frame queues, AtomicBoolean for flags | ✓ Standard library |
| Android SDK | API 24+ | Math.sin for tone generation, Math.ceil for calculations | ✓ Standard library |

### Existing Columba Components (Phases 7-8)

| Component | Version | Purpose | Status |
|-----------|---------|---------|--------|
| Source.kt | Phase 8 | Base source interface with sampleRate, channels, start/stop | ✓ Implemented |
| Sink.kt | Phase 8 | Base sink interface with canReceive, handleFrame | ✓ Implemented |
| LineSource.kt | Phase 8 | Microphone capture, already uses push model | ✓ Implemented |
| LineSink.kt | Phase 8 | Speaker playback, already handles backpressure | ✓ Implemented |
| Codec.kt | Phase 7 | Base codec with encode/decode, frame constraints | ✓ Implemented |
| Opus.kt | Phase 7 | Wire-compatible codec with 9 profiles | ✓ Implemented |

**Installation:** No new dependencies required. Everything needed exists in current codebase.

## Architecture Patterns

### Python LXST Reference Structure

```
LXST/
├── Mixer.py          # Mixer class (14→177) — multi-source audio mixer
├── Pipeline.py       # Pipeline class (10→58) — component orchestration
└── Generators.py     # ToneSource class (11→134) — sine wave generator
```

### Recommended Kotlin Package Structure

```
reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/
├── Source.kt         # Existing base classes
├── Sink.kt           # Existing base classes
├── LineSource.kt     # Existing microphone capture
├── LineSink.kt       # Existing speaker playback
├── Mixer.kt          # NEW — implements LocalSource + LocalSink
├── Pipeline.kt       # NEW — orchestration wrapper
└── ToneSource.kt     # NEW — implements LocalSource
```

### Pattern 1: Mixer - Push-Based Multi-Source Combining

**What:** Mixer accepts frames from multiple sources (via `handleFrame()`), stores in per-source queues, combines with gain control, and pushes mixed output to sink.

**When to use:** Anytime multiple audio sources must be combined (local mic + remote stream, or multiple remote streams).

**Python LXST implementation (Mixer.py:14-177):**

```python
class Mixer(LocalSource, LocalSink):
    MAX_FRAMES = 8  # Queue depth per source

    def __init__(self, target_frame_ms=40, samplerate=None, codec=None, sink=None, gain=0.0):
        self.incoming_frames = {}  # Dict[source] = deque(maxlen=MAX_FRAMES)
        self.target_frame_ms = target_frame_ms
        self.should_run = False
        self.muted = False
        self.gain = gain  # Global gain in dB
        self.mixer_thread = None

    def start(self):
        self.should_run = True
        self.mixer_thread = threading.Thread(target=self._mixer_job, daemon=True)
        self.mixer_thread.start()

    def can_receive(self, from_source):
        # Backpressure: reject if queue full
        if not from_source in self.incoming_frames: return True
        elif len(self.incoming_frames[from_source]) < self.MAX_FRAMES: return True
        else: return False

    def handle_frame(self, frame, source, decoded=False):
        # Source pushes frames here (PUSH MODEL)
        with self.insert_lock:
            if not source in self.incoming_frames:
                self.incoming_frames[source] = deque(maxlen=self.MAX_FRAMES)
                # Auto-detect sample rate from first source
                if not self.samplerate:
                    self.samplerate = source.samplerate

            # Decode frame if needed
            if not decoded: frame_samples = source.codec.decode(frame)
            else: frame_samples = frame

            # Add to queue (oldest dropped if full)
            self.incoming_frames[source].append(frame_samples)

    @property
    def _mixing_gain(self):
        # Convert dB gain to linear multiplier, handle mute
        if self.muted: return 0.0
        elif self.gain == 0.0: return 1.0
        else: return 10**(self.gain/10)  # dB to linear

    def _mixer_job(self):
        # Background thread pulls from queues and mixes
        with self.mixer_lock:
            while self.should_run:
                if self.sink and self.sink.can_receive():
                    source_count = 0
                    mixed_frame = None

                    # Pull one frame from each source, add together
                    for source in self.incoming_frames.copy():
                        if len(self.incoming_frames[source]) > 0:
                            next_frame = self.incoming_frames[source].popleft()
                            if source_count == 0: mixed_frame = next_frame * self._mixing_gain
                            else: mixed_frame = mixed_frame + next_frame * self._mixing_gain
                            source_count += 1

                    if source_count > 0:
                        # Clip to prevent overflow
                        mixed_frame = np.clip(mixed_frame, -1.0, 1.0)

                        # Encode and push to sink
                        if self.codec: self.sink.handle_frame(self.codec.encode(mixed_frame), self)
                        else: self.sink.handle_frame(mixed_frame, self)
                    else:
                        time.sleep(self.frame_time * 0.1)  # No frames available
                else:
                    time.sleep(self.frame_time * 0.1)  # Sink can't receive

    def set_gain(self, gain=None):
        if gain == None: self.gain = 0.0
        else: self.gain = float(gain)

    def mute(self, mute=True):
        self.muted = mute
```

**Key observations:**

1. **Dual identity:** Mixer implements BOTH LocalSource and LocalSink
   - As Sink: accepts frames from multiple sources via `handleFrame()`
   - As Source: outputs mixed frames to downstream sink via background thread

2. **Per-source queues:** Each source gets its own deque (max 8 frames)
   - Auto-created on first frame from source
   - Sample rate detected from first source

3. **Mixing algorithm:**
   - Pull one frame from each source's queue
   - Multiply each by global gain (converted from dB to linear: `10^(gain/10)`)
   - Add frames together element-wise (numpy addition)
   - Clip to [-1.0, 1.0] to prevent overflow
   - Encode with mixer's codec and push to sink

4. **Mute implementation:** Set gain multiplier to 0.0 (soft mute, not bypass)

5. **Backpressure:** Check `sink.can_receive()` before pushing, `can_receive(from_source)` for incoming frames

**Kotlin equivalent structure:**

```kotlin
class Mixer(
    private val targetFrameMs: Int = 40,
    private var codec: Codec? = null,
    var sink: Sink? = null,
    private val globalGain: Float = 0.0f
) : LocalSource(), LocalSink() {

    companion object {
        const val MAX_FRAMES = 8
    }

    // Per-source frame queues
    private val incomingFrames = mutableMapOf<Source, ArrayDeque<FloatArray>>()
    private val insertLock = Any()

    // Mixer thread state
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shouldRun = AtomicBoolean(false)
    private var muted = AtomicBoolean(false)

    override var sampleRate: Int = 0
    override var channels: Int = 1

    override fun canReceive(fromSource: Source?): Boolean {
        fromSource ?: return true
        synchronized(insertLock) {
            val queue = incomingFrames[fromSource] ?: return true
            return queue.size < MAX_FRAMES
        }
    }

    override fun handleFrame(frame: FloatArray, source: Source?) {
        source ?: return
        synchronized(insertLock) {
            // Create queue if first frame from this source
            if (!incomingFrames.containsKey(source)) {
                incomingFrames[source] = ArrayDeque(MAX_FRAMES)
                // Auto-detect sample rate from first source
                if (sampleRate == 0) {
                    sampleRate = source.sampleRate
                    channels = source.channels
                }
            }

            val queue = incomingFrames[source]!!
            // Add to queue (drop oldest if full)
            if (queue.size >= MAX_FRAMES) {
                queue.removeFirst()
            }
            queue.addLast(frame)
        }
    }

    private val mixingGain: Float
        get() = if (muted.get()) 0.0f
                else if (globalGain == 0.0f) 1.0f
                else Math.pow(10.0, (globalGain / 10.0)).toFloat()  // dB to linear

    private suspend fun mixerJob() {
        while (shouldRun.get()) {
            val currentSink = sink
            if (currentSink != null && currentSink.canReceive(this)) {
                var sourceCount = 0
                var mixedFrame: FloatArray? = null

                // Pull one frame from each source, add together
                synchronized(insertLock) {
                    for ((source, queue) in incomingFrames) {
                        if (queue.isNotEmpty()) {
                            val nextFrame = queue.removeFirst()
                            mixedFrame = if (sourceCount == 0) {
                                nextFrame.map { it * mixingGain }.toFloatArray()
                            } else {
                                mixedFrame!!.zip(nextFrame) { a, b -> a + b * mixingGain }.toFloatArray()
                            }
                            sourceCount++
                        }
                    }
                }

                if (sourceCount > 0 && mixedFrame != null) {
                    // Clip to prevent overflow
                    val clipped = mixedFrame!!.map { it.coerceIn(-1.0f, 1.0f) }.toFloatArray()

                    // Push to sink
                    currentSink.handleFrame(clipped, this)
                } else {
                    delay((targetFrameMs / 10).toLong())  // No frames available
                }
            } else {
                delay((targetFrameMs / 10).toLong())  // Sink can't receive
            }
        }
    }

    fun setGain(gain: Float) {
        this.globalGain = gain
    }

    fun mute(mute: Boolean = true) {
        this.muted.set(mute)
    }
}
```

### Pattern 2: Pipeline - Component Orchestration

**What:** Pipeline wires source → codec → sink and provides unified start/stop lifecycle.

**When to use:** Always use Pipeline to connect components. Never wire source/codec/sink manually.

**Python LXST implementation (Pipeline.py:10-58):**

```python
class Pipeline():
    def __init__(self, source, codec, sink, processor=None):
        # Validate types
        if not issubclass(type(source), Source): raise PipelineError(...)
        if not issubclass(type(sink), Sink): raise PipelineError(...)
        if not issubclass(type(codec), Codec): raise PipelineError(...)

        # Wire components (source is central coordinator)
        self.source = source
        self.source.pipeline = self  # Backref
        self.source.sink = sink       # Source pushes to sink
        self.codec = codec            # Triggers source.codec setter

    @property
    def codec(self):
        return self.source.codec

    @codec.setter
    def codec(self, codec):
        if not self._codec == codec:
            self._codec = codec
            self.source.codec = codec           # Set on source
            self.source.codec.sink = self.sink  # Set on codec
            self.source.codec.source = self.source

    @property
    def sink(self):
        return self.source.sink

    @property
    def running(self):
        return self.source.should_run

    def start(self):
        if not self.running:
            self.source.start()  # Only start source (source drives pipeline)

    def stop(self):
        if self.running:
            self.source.stop()
```

**Key observations:**

1. **Source-driven:** Pipeline delegates everything to source. Starting pipeline = starting source.

2. **Wiring pattern:**
   - `source.sink = sink` (source knows where to push)
   - `source.codec = codec` (source uses codec for encoding)
   - `codec.sink = sink` and `codec.source = source` (codec has bidirectional refs)

3. **Property delegation:** `pipeline.codec`, `pipeline.sink`, `pipeline.running` all delegate to source

4. **No processing:** Pipeline does NO audio processing, just coordination

**Kotlin equivalent structure:**

```kotlin
class Pipeline(
    val source: Source,
    codec: Codec,
    val sink: Sink
) {
    private var _codec: Codec? = null

    init {
        // Validate types (Kotlin type system handles this at compile time)
        // Wire components
        when (source) {
            is LineSource -> source.sink = sink
            is Mixer -> source.sink = sink
            is ToneSource -> source.sink = sink
        }
        this.codec = codec
    }

    var codec: Codec
        get() = _codec ?: throw IllegalStateException("Codec not set")
        set(value) {
            if (_codec != value) {
                _codec = value
                // Set codec on source (implementation varies by source type)
                when (source) {
                    is LineSource -> {
                        // LineSource codec set in constructor, immutable
                        // For dynamic reconfiguration, would need to add setter
                    }
                    is ToneSource -> source.codec = value
                }
            }
        }

    val running: Boolean
        get() = source.isRunning()

    fun start() {
        if (!running) {
            source.start()
        }
    }

    fun stop() {
        if (running) {
            source.stop()
        }
    }
}
```

### Pattern 3: ToneSource - Sine Wave Generation with Easing

**What:** Generate continuous sine wave at specified frequency, with smooth fade in/out (easing).

**When to use:** Dial tones (382Hz per Python), busy signals (alternating on/off), ringback tones.

**Python LXST implementation (Generators.py:11-134):**

```python
class ToneSource(LocalSource):
    DEFAULT_FRAME_MS = 80
    DEFAULT_SAMPLERATE = 48000
    DEFAULT_FREQUENCY = 400
    EASE_TIME_MS = 20  # Fade duration

    def __init__(self, frequency=DEFAULT_FREQUENCY, gain=0.1, ease=True,
                 ease_time_ms=EASE_TIME_MS, target_frame_ms=DEFAULT_FRAME_MS,
                 codec=None, sink=None, channels=1):
        self.frequency = frequency
        self._gain = gain
        self.gain = self._gain
        self.ease = ease
        self.theta = 0  # Phase accumulator
        self.ease_gain = 0  # Fade multiplier (0.0 to 1.0)
        self.ease_time_ms = ease_time_ms
        self.ease_step = 0  # Calculated per-sample increment
        self.gain_step = 0  # Calculated per-sample gain change
        self.easing_out = False
        self.codec = codec
        self.sink = sink

    @codec.setter
    def codec(self, codec):
        self._codec = codec
        if codec:
            # Adjust sample rate, frame time based on codec constraints
            if self.codec.preferred_samplerate:
                self.samplerate = self.codec.preferred_samplerate
            # ... quantization logic (same as LineSource)

            self.samples_per_frame = math.ceil((self.target_frame_ms/1000)*self.samplerate)
            self.frame_time = self.samples_per_frame/self.samplerate

            # Calculate fade step size (linear ramp over ease_time_ms)
            self.ease_step = 1/(self.samplerate*(self.ease_time_ms/1000))
            self.gain_step = 0.02/(self.samplerate*(self.ease_time_ms/1000))

    def start(self):
        self.ease_gain = 0 if self.ease else 1
        self.should_run = True
        self.generate_thread = threading.Thread(target=self.__generate_job, daemon=True)
        self.generate_thread.start()

    def stop(self):
        if not self.ease:
            self.should_run = False
        else:
            self.easing_out = True  # Trigger fade out, stop when done

    def __generate(self):
        # Generate one frame of sine wave
        frame_samples = np.zeros((self.samples_per_frame, self.channels), dtype="float32")
        step = (self.frequency * 2 * math.pi) / self.samplerate

        for n in range(0, self.samples_per_frame):
            self.theta += step
            amplitude = math.sin(self.theta) * self._gain * self.ease_gain
            for c in range(0, self.channels):
                frame_samples[n, c] = amplitude

            # Fade in/out (linear ramp)
            if self.ease:
                if self.ease_gain < 1.0 and not self.easing_out:
                    self.ease_gain += self.ease_step
                    if self.ease_gain > 1.0: self.ease_gain = 1.0
                elif self.easing_out and self.ease_gain > 0.0:
                    self.ease_gain -= self.ease_step
                    if self.ease_gain <= 0.0:
                        self.ease_gain = 0.0
                        self.easing_out = False
                        self.should_run = False  # Stop after fade out

        return frame_samples

    def __generate_job(self):
        with self.generate_lock:
            while self.should_run:
                if self.codec and self.sink and self.sink.can_receive(from_source=self):
                    frame_samples = self.__generate()
                    frame = self.codec.encode(frame_samples)
                    self.sink.handle_frame(frame, self)
                time.sleep(self.frame_time*0.1)
```

**Key observations:**

1. **Phase accumulator:** `theta` tracks sine wave phase across frames (continuous tone)
   - Step size: `(frequency * 2π) / sampleRate` radians per sample
   - Wraps naturally due to `math.sin()` periodicity

2. **Ease in/out:** Linear fade prevents clicks/pops
   - Fade duration: `ease_time_ms` (20ms default, 3.14159ms for dial tone per Telephony.py:119)
   - Step size: `1 / (sampleRate * easeTime)` per sample
   - On start: `ease_gain` ramps 0.0 → 1.0
   - On stop: `ease_gain` ramps 1.0 → 0.0, then actually stops

3. **Gain control:** Two gain levels
   - `_gain`: Internal gain (adjusted smoothly with `gain_step`)
   - `gain`: External gain (set by user, `_gain` converges to this)

4. **Generation loop:** Same pattern as LineSource
   - Generate frame → encode → check backpressure → push to sink

**Kotlin equivalent structure:**

```kotlin
class ToneSource(
    private val frequency: Float = 400f,
    private var targetGain: Float = 0.1f,
    private val ease: Boolean = true,
    private val easeTimeMs: Float = 20f,
    private val targetFrameMs: Int = 80,
    var codec: Codec? = null,
    var sink: Sink? = null,
    override val channels: Int = 1
) : LocalSource() {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48000
    }

    override var sampleRate: Int = DEFAULT_SAMPLE_RATE

    private var theta = 0.0  // Phase accumulator
    private var easeGain = 0f  // Fade multiplier
    private var currentGain = 0f  // Internal gain
    private var easeStep = 0f  // Per-sample fade increment
    private var gainStep = 0f  // Per-sample gain change
    private var easingOut = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shouldRun = AtomicBoolean(false)

    private var samplesPerFrame = 0
    private var frameTimeMs = 0L

    init {
        calculateParameters()
    }

    private fun calculateParameters() {
        codec?.let { c ->
            sampleRate = c.preferredSamplerate ?: DEFAULT_SAMPLE_RATE
            samplesPerFrame = ((targetFrameMs / 1000f) * sampleRate).toInt()
            frameTimeMs = ((samplesPerFrame.toFloat() / sampleRate) * 1000).toLong()

            easeStep = 1.0f / (sampleRate * (easeTimeMs / 1000f))
            gainStep = 0.02f / (sampleRate * (easeTimeMs / 1000f))
        }
    }

    override fun start() {
        if (shouldRun.getAndSet(true)) return

        easeGain = if (ease) 0f else 1f
        currentGain = 0f
        scope.launch { generateJob() }
    }

    override fun stop() {
        if (!ease) {
            shouldRun.set(false)
        } else {
            easingOut = true  // Trigger fade out
        }
    }

    override fun isRunning(): Boolean = shouldRun.get() && !easingOut

    private fun generateFrame(): FloatArray {
        val frame = FloatArray(samplesPerFrame * channels)
        val step = (frequency * 2 * Math.PI) / sampleRate

        for (n in 0 until samplesPerFrame) {
            theta += step
            val amplitude = (Math.sin(theta) * currentGain * easeGain).toFloat()

            for (c in 0 until channels) {
                frame[n * channels + c] = amplitude
            }

            // Adjust gain smoothly
            when {
                targetGain > currentGain -> {
                    currentGain += gainStep
                    if (currentGain > targetGain) currentGain = targetGain
                }
                targetGain < currentGain -> {
                    currentGain -= gainStep
                    if (currentGain < targetGain) currentGain = targetGain
                }
            }

            // Fade in/out
            if (ease) {
                when {
                    easeGain < 1.0f && !easingOut -> {
                        easeGain += easeStep
                        if (easeGain > 1.0f) easeGain = 1.0f
                    }
                    easingOut && easeGain > 0.0f -> {
                        easeGain -= easeStep
                        if (easeGain <= 0.0f) {
                            easeGain = 0.0f
                            easingOut = false
                            shouldRun.set(false)
                        }
                    }
                }
            }
        }

        return frame
    }

    private suspend fun generateJob() {
        while (shouldRun.get()) {
            val currentCodec = codec
            val currentSink = sink

            if (currentCodec != null && currentSink != null && currentSink.canReceive(this)) {
                val frameSamples = generateFrame()
                val encoded = currentCodec.encode(frameSamples)
                currentSink.handleFrame(encoded, this)
            }

            delay(frameTimeMs / 10)
        }
    }

    fun setGain(gain: Float) {
        this.targetGain = gain
    }
}
```

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Audio mixing | Custom buffer management, manual gain | Python LXST Mixer.py pattern exactly | Edge cases: sample rate mismatches, queue overflow, clipping detection |
| Sine wave generation | Direct phase calculation without easing | Python LXST Generators.py ToneSource | Edge cases: phase continuity, click-free fades, gain transitions |
| Pipeline wiring | Manual component connections | Python LXST Pipeline.py pattern | Edge cases: codec reconfiguration, lifecycle management, backref consistency |
| Frame clipping | Manual bounds checking | `FloatArray.map { it.coerceIn(-1.0f, 1.0f) }` | Kotlin stdlib handles edge cases |
| dB to linear gain | Custom math | `Math.pow(10.0, gain/10.0)` | Standard formula, no reinvention |

**Key insight:** Python LXST has run in production for years. Every pattern has been debugged. Match it exactly.

## Common Pitfalls

### Pitfall 1: Per-Source Gain Instead of Global Gain

**What goes wrong:** Implementing per-source gain as separate parameters instead of global gain applied to mixed output.

**Why it happens:** Context says "both per-source and global gain" which could be misread as needing separate knobs.

**How to avoid:** Python LXST Mixer only implements global gain (line 27: `self.gain`). The "per-source" gain is implicit in the mixing algorithm (each frame multiplied by `_mixing_gain` before addition). For true per-source control, would need separate gain values stored in `incoming_frames` dict keys.

**Warning signs:** More than one gain parameter in Mixer constructor.

### Pitfall 2: Forgetting Phase Continuity in ToneSource

**What goes wrong:** Resetting `theta = 0` at frame boundaries, causing discontinuities and audible clicks.

**Why it happens:** Natural instinct to reset loop variables at frame boundaries.

**How to avoid:** `theta` is an accumulator that persists across frames (Python line 28: `self.theta = 0` only in `__init__`, never reset). Phase must be continuous for smooth tone.

**Warning signs:** Clicking or popping in tone playback, especially at frame boundaries.

### Pitfall 3: Immediate Stop Instead of Eased Stop

**What goes wrong:** Calling `shouldRun.set(false)` directly in `stop()`, causing hard cutoff and click.

**Why it happens:** Normal stop pattern for other components is immediate.

**How to avoid:** ToneSource has special stop behavior when `ease=true` (Python lines 86-89):
- Set `easing_out = true` (triggers fade)
- Let generate loop ramp `ease_gain` down to 0.0
- Generate loop sets `should_run = false` when fade completes

**Warning signs:** Audible click when stopping tone, especially dial tone.

### Pitfall 4: Mixing Before Decoding

**What goes wrong:** Trying to mix encoded byte arrays instead of decoded float32 samples.

**Why it happens:** Confusion about where decode happens in the pipeline.

**How to avoid:** Mixer.handleFrame() decodes frames on arrival (Python lines 86-87):
```python
if not decoded: frame_samples = source.codec.decode(frame)
else:           frame_samples = frame
```
Only float32 samples are stored in queues and mixed. Encoding happens AFTER mixing (line 120).

**Warning signs:** Type errors mixing ByteArray, incorrect audio output.

### Pitfall 5: Not Handling Sample Rate Detection

**What goes wrong:** Hardcoding sample rate instead of detecting from first source.

**Why it happens:** Simpler to use constant value.

**How to avoid:** Mixer auto-detects sample rate from first source (Python lines 76-84):
```python
if not self.channels:
    self.channels = source.channels
if not self.samplerate:
    self.samplerate = source.samplerate
```
This ensures mixer matches its sources without manual configuration.

**Warning signs:** Sample rate mismatch warnings, distorted audio.

## Code Examples

Verified patterns from Python LXST with line references.

### Mixer: Push Model with Backpressure

```python
# Source: Mixer.py:66-93
def can_receive(self, from_source):
    if not from_source in self.incoming_frames:                    return True
    elif len(self.incoming_frames[from_source]) < self.MAX_FRAMES: return True
    else:                                                          return False

def handle_frame(self, frame, source, decoded=False):
    with self.insert_lock:
        if not source in self.incoming_frames:
            self.incoming_frames[source] = deque(maxlen=self.MAX_FRAMES)

            # Auto-detect configuration from first source
            if not self.channels:
                self.channels = source.channels
            if not self.samplerate:
                self.samplerate = source.samplerate
                self.samples_per_frame = math.ceil((self.target_frame_ms/1000)*self.samplerate)

        # Decode if needed (only float32 stored in queue)
        if not decoded: frame_samples = source.codec.decode(frame)
        else:           frame_samples = frame

        self.incoming_frames[source].append(frame_samples)
```

**Kotlin equivalent:**
```kotlin
override fun canReceive(fromSource: Source?): Boolean {
    fromSource ?: return true
    synchronized(insertLock) {
        val queue = incomingFrames[fromSource] ?: return true
        return queue.size < MAX_FRAMES
    }
}

override fun handleFrame(frame: FloatArray, source: Source?) {
    source ?: return
    synchronized(insertLock) {
        if (!incomingFrames.containsKey(source)) {
            incomingFrames[source] = ArrayDeque(MAX_FRAMES)
            if (sampleRate == 0) {
                sampleRate = source.sampleRate
                channels = source.channels
            }
        }

        val queue = incomingFrames[source]!!
        if (queue.size >= MAX_FRAMES) {
            queue.removeFirst()
        }
        queue.addLast(frame)
    }
}
```

### Mixer: Multi-Source Combining with Clipping

```python
# Source: Mixer.py:101-122
def _mixer_job(self):
    with self.mixer_lock:
        while self.should_run:
            if self.sink and self.sink.can_receive():
                source_count = 0
                mixed_frame = None
                for source in self.incoming_frames.copy():
                    if len(self.incoming_frames[source]) > 0:
                        next_frame = self.incoming_frames[source].popleft()
                        if source_count == 0: mixed_frame = next_frame*self._mixing_gain
                        else: mixed_frame = mixed_frame + next_frame*self._mixing_gain
                        source_count += 1

                if source_count > 0:
                    mixed_frame = np.clip(mixed_frame, -1.0, 1.0)
                    if self.codec: self.sink.handle_frame(self.codec.encode(mixed_frame), self)
                    else:          self.sink.handle_frame(mixed_frame, self)
```

**Kotlin equivalent:**
```kotlin
private suspend fun mixerJob() {
    while (shouldRun.get()) {
        val currentSink = sink
        if (currentSink != null && currentSink.canReceive(this)) {
            var sourceCount = 0
            var mixedFrame: FloatArray? = null

            synchronized(insertLock) {
                for ((source, queue) in incomingFrames) {
                    if (queue.isNotEmpty()) {
                        val nextFrame = queue.removeFirst()
                        mixedFrame = if (sourceCount == 0) {
                            nextFrame.map { it * mixingGain }.toFloatArray()
                        } else {
                            mixedFrame!!.zip(nextFrame) { a, b -> a + b * mixingGain }.toFloatArray()
                        }
                        sourceCount++
                    }
                }
            }

            if (sourceCount > 0 && mixedFrame != null) {
                val clipped = mixedFrame!!.map { it.coerceIn(-1.0f, 1.0f) }.toFloatArray()
                codec?.let { c ->
                    currentSink.handleFrame(c.encode(clipped), this)
                } ?: currentSink.handleFrame(clipped, this)
            }
        }
    }
}
```

### ToneSource: Sine Wave with Linear Fade

```python
# Source: Generators.py:95-123
def __generate(self):
    frame_samples = np.zeros((self.samples_per_frame, self.channels), dtype="float32")
    step = (self.frequency * 2 * math.pi) / self.samplerate
    for n in range(0, self.samples_per_frame):
        self.theta += step
        amplitude = math.sin(self.theta)*self._gain*self.ease_gain
        for c in range(0, self.channels):
            frame_samples[n, c] = amplitude

        # Smooth gain transitions
        if self.gain > self._gain:
            self._gain += self.gain_step
            if self._gain > self.gain: self._gain = self.gain
        if self.gain < self._gain:
            self._gain -= self.gain_step
            if self._gain < self.gain: self._gain = self.gain

        # Linear fade in/out
        if self.ease:
            if self.ease_gain < 1.0 and not self.easing_out:
                self.ease_gain += self.ease_step
                if self.ease_gain > 1.0: self.ease_gain = 1.0
            elif self.easing_out and self.ease_gain > 0.0:
                self.ease_gain -= self.ease_step
                if self.ease_gain <= 0.0:
                    self.ease_gain = 0.0
                    self.easing_out = False
                    self.should_run = False
    return frame_samples
```

**Kotlin equivalent:**
```kotlin
private fun generateFrame(): FloatArray {
    val frame = FloatArray(samplesPerFrame * channels)
    val step = (frequency * 2 * Math.PI) / sampleRate

    for (n in 0 until samplesPerFrame) {
        theta += step
        val amplitude = (Math.sin(theta) * currentGain * easeGain).toFloat()

        for (c in 0 until channels) {
            frame[n * channels + c] = amplitude
        }

        // Smooth gain transitions
        when {
            targetGain > currentGain -> {
                currentGain += gainStep
                if (currentGain > targetGain) currentGain = targetGain
            }
            targetGain < currentGain -> {
                currentGain -= gainStep
                if (currentGain < targetGain) currentGain = targetGain
            }
        }

        // Linear fade in/out
        if (ease) {
            when {
                easeGain < 1.0f && !easingOut -> {
                    easeGain += easeStep
                    if (easeGain > 1.0f) easeGain = 1.0f
                }
                easingOut && easeGain > 0.0f -> {
                    easeGain -= easeStep
                    if (easeGain <= 0.0f) {
                        easeGain = 0.0f
                        easingOut = false
                        shouldRun.set(false)
                    }
                }
            }
        }
    }

    return frame
}
```

### Pipeline: Component Wiring

```python
# Source: Pipeline.py:10-58
class Pipeline():
    def __init__(self, source, codec, sink, processor=None):
        if not issubclass(type(source), Source): raise PipelineError(...)
        if not issubclass(type(sink), Sink): raise PipelineError(...)
        if not issubclass(type(codec), Codec): raise PipelineError(...)

        self.source = source
        self.source.pipeline = self
        self.source.sink = sink
        self.codec = codec  # Triggers codec setter

    @codec.setter
    def codec(self, codec):
        if not self._codec == codec:
            self._codec = codec
            self.source.codec = self._codec
            self.source.codec.sink = self.sink
            self.source.codec.source = self.source

    def start(self):
        if not self.running:
            self.source.start()

    def stop(self):
        if self.running:
            self.source.stop()
```

**Kotlin equivalent:**
```kotlin
class Pipeline(
    val source: Source,
    codec: Codec,
    val sink: Sink
) {
    private var _codec: Codec? = null

    init {
        // Wire sink to source
        when (source) {
            is LineSource -> source.sink = sink
            is Mixer -> source.sink = sink
            is ToneSource -> source.sink = sink
        }
        this.codec = codec
    }

    var codec: Codec
        get() = _codec ?: throw IllegalStateException("Codec not set")
        set(value) {
            if (_codec != value) {
                _codec = value
                when (source) {
                    is ToneSource -> source.codec = value
                    is Mixer -> source.codec = value
                }
            }
        }

    fun start() {
        if (!source.isRunning()) {
            source.start()
        }
    }

    fun stop() {
        if (source.isRunning()) {
            source.stop()
        }
    }
}
```

### Telephony Integration: Dial Tone Usage

```python
# Source: Primitives/Telephony.py:169-171, 519-524
# Telephony class shows how mixer + pipeline + tone source are used together

class Telephone:
    DIAL_TONE_FREQUENCY = 382  # Hz
    DIAL_TONE_EASE_MS = 3.14159  # Very quick fade

    def __prepare_dialling_pipelines(self):
        if self.audio_output == None:
            self.audio_output = LineSink(preferred_device=self.speaker_device)
        if self.receive_mixer == None:
            self.receive_mixer = Mixer(target_frame_ms=self.target_frame_time_ms,
                                        gain=self.receive_gain)
        if self.dial_tone == None:
            self.dial_tone = ToneSource(
                frequency=self.dial_tone_frequency,  # 382 Hz
                gain=0.0,  # Start muted
                ease_time_ms=self.dial_tone_ease_ms,  # 3.14159ms fade
                target_frame_ms=self.target_frame_time_ms,
                codec=Null(),  # No compression for local playback
                sink=self.receive_mixer  # Tone feeds into mixer
            )
        if self.receive_pipeline == None:
            self.receive_pipeline = Pipeline(
                source=self.receive_mixer,  # Mixer outputs mixed audio
                codec=Null(),  # No compression
                sink=self.audio_output  # To speaker
            )
```

**Key insight:** Dial tone is a source that feeds into receive mixer alongside remote audio. Mixer combines them, pipeline pushes to speaker.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Pull-based mixing (sink requests frames) | Push-based mixing (sources call handleFrame) | LXST v1.0 | Simpler backpressure, no polling overhead |
| Hard stop (immediate) | Eased stop (fade out) | LXST v1.2 | Click-free tone transitions |
| Separate threading per component | Single mixer thread pulls from all queues | LXST v1.0 | Better synchronization, less thread overhead |
| Manual sample rate configuration | Auto-detect from first source | LXST v1.1 | Fewer configuration errors |

**Deprecated/outdated:**
- **Direct AudioRecord/AudioTrack use in mixer:** Phase 8 wrapped these in LineSource/LineSink. Mixer now only works with Source/Sink abstractions.
- **Global mute flag:** Python LXST Telephony has separate `receive_muted` and `transmit_muted` (lines 179-180). Context says "reset mute state each call" (unmuted by default).

## Integration Patterns

### Pattern: Mixer + Pipeline for Receive Path

**Scenario:** Mix remote audio + local dial tone, output to speaker.

```python
# Python LXST Telephony.py:519-524
receive_mixer = Mixer(target_frame_ms=60, gain=0.0)
dial_tone = ToneSource(frequency=382, gain=0.0, codec=Null(), sink=receive_mixer)
audio_output = LineSink()
receive_pipeline = Pipeline(source=receive_mixer, codec=Null(), sink=audio_output)

# Remote audio source pushes to mixer
remote_source.sink = receive_mixer

# Start everything
receive_mixer.start()
dial_tone.start()  # Will fade in when gain > 0
receive_pipeline.start()
```

**Kotlin equivalent:**
```kotlin
val receiveMixer = Mixer(targetFrameMs = 60, globalGain = 0.0f)
val dialTone = ToneSource(frequency = 382f, targetGain = 0.0f, codec = Null(), sink = receiveMixer)
val audioOutput = LineSink(bridge)
val receivePipeline = Pipeline(source = receiveMixer, codec = Null(), sink = audioOutput)

// Remote audio source pushes to mixer
remoteSource.sink = receiveMixer

// Start everything
receiveMixer.start()
dialTone.start()
receivePipeline.start()
```

### Pattern: Mixer for Transmit Path

**Scenario:** Mix local mic + future local audio source, encode and send.

```python
# Python LXST Telephony.py:614-619
transmit_mixer = Mixer(target_frame_ms=60, gain=0.0)
audio_input = LineSource(codec=Raw(), sink=transmit_mixer, filters=[BandPass(250, 8500), AGC()])
transmit_pipeline = Pipeline(source=transmit_mixer, codec=Opus(), sink=packetizer)

transmit_mixer.start()
audio_input.start()
transmit_pipeline.start()
```

**Kotlin equivalent:**
```kotlin
val transmitMixer = Mixer(targetFrameMs = 60, globalGain = 0.0f, codec = Opus())
val audioInput = LineSource(bridge, codec = Null(), sink = transmitMixer)
val transmitPipeline = Pipeline(source = transmitMixer, codec = Opus(), sink = packetizer)

transmitMixer.start()
audioInput.start()
transmitPipeline.start()
```

## Tone Frequencies

Based on Python LXST Telephony.py and context requirements:

| Tone Type | Frequency | Python Reference | Context Requirement |
|-----------|-----------|------------------|---------------------|
| Dial tone | 382 Hz | Telephony.py:118 `DIAL_TONE_FREQUENCY = 382` | User requested "match ITU-T standard frequencies — 440Hz dial" |
| Busy tone | Alternating on/off | Telephony.py:541-551 (toggles dial tone 0.25s on, 0.25s off) | User requested "480/620Hz busy" (ITU-T) |

**IMPORTANT DISCREPANCY:** User context says "440Hz dial, 480/620Hz busy" (ITU-T standards), but Python LXST uses 382Hz for dial tone.

**Resolution:** Python LXST Telephony.py:118 hardcodes 382Hz. User said "Match Python LXST implementation exactly" as guiding principle. **Use 382Hz to match Python exactly**, but note discrepancy in research. Planner can decide whether to override.

**Busy tone implementation:** Python creates busy tone by alternating dial tone on/off in 0.5s window (line 546-550):
```python
window = 0.5; started = time.time()
while time.time()-started < self.busy_tone_seconds:  # 4.25s default
    elapsed = (time.time()-started)%window
    if elapsed > 0.25: self.__enable_dial_tone()  # 0.25s on
    else: self.__mute_dial_tone()  # 0.25s off
```

For true ITU-T 480/620Hz busy tone, would need dual-frequency ToneSource, but Python LXST doesn't implement this.

## Open Questions

1. **Tone frequency mismatch**
   - What we know: User context says "440Hz dial, 480/620Hz busy" (ITU-T), Python LXST uses 382Hz
   - What's unclear: Should we override Python to match ITU-T standards?
   - Recommendation: Start with 382Hz (match Python exactly), make frequency configurable, let user decide in testing

2. **Per-source gain implementation**
   - What we know: Python Mixer only has global gain, context says "both per-source and global gain"
   - What's unclear: Does "per-source" mean individual sources can have different gain values?
   - Recommendation: Implement global gain only (match Python), add per-source gain as future enhancement if needed

3. **Sample rate mismatch handling**
   - What we know: Python Mixer has commented-out resampling code (Mixer.py:89-91)
   - What's unclear: What should happen when sources have different sample rates?
   - Recommendation: Follow Python pattern — auto-detect from first source, assume all sources match. Add resampling in future phase if needed.

4. **Mixer codec usage**
   - What we know: Mixer can have a codec (Mixer.py:129-139), used to encode mixed output before pushing to sink
   - What's unclear: When is mixer codec vs pipeline codec used?
   - Recommendation: Telephony uses codec=Null() for mixers (lines 522, 524), real encoding happens in pipeline. Mixer codec is for internal processing only.

## Sources

### Primary (HIGH confidence)

- Python LXST Mixer.py — Complete implementation reference
  - `/home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Mixer.py`
  - Lines analyzed: 14-177 (entire Mixer class)

- Python LXST Pipeline.py — Complete implementation reference
  - `/home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Pipeline.py`
  - Lines analyzed: 10-58 (entire Pipeline class)

- Python LXST Generators.py — ToneSource implementation
  - `/home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Generators.py`
  - Lines analyzed: 11-134 (entire ToneSource class)

- Python LXST Primitives/Telephony.py — Integration patterns
  - `/home/tyler/repos/public/columba/app/build/python/pip/noSentryDebug/common/LXST/Primitives/Telephony.py`
  - Lines analyzed: 114-732 (entire Telephone class)
  - Key sections: lines 169-172 (tone config), 519-524 (pipeline setup), 541-577 (tone control), 614-619 (transmit mixer)

- Existing Kotlin implementations (Phase 7-8)
  - `/home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/Source.kt`
  - `/home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/Sink.kt`
  - `/home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/LineSource.kt`
  - `/home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/lxst/LineSink.kt`
  - `/home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/codec/Codec.kt`
  - `/home/tyler/repos/public/columba/reticulum/src/main/java/com/lxmf/messenger/reticulum/audio/codec/Opus.kt`

## Metadata

**Confidence breakdown:**
- Mixer architecture: HIGH — Python code is complete and clear, direct port feasible
- Pipeline architecture: HIGH — Simple wrapper pattern, well-defined in Python
- ToneSource: HIGH — Mathematical generation well-documented in Python
- Integration patterns: HIGH — Telephony.py provides real-world usage examples
- Tone frequencies: MEDIUM — Discrepancy between context (440Hz) and Python (382Hz)

**Research date:** 2026-02-04
**Valid until:** 60 days (stable domain, Python LXST unlikely to change)

**Key files for planning:**
1. Mixer.py (lines 14-177) — Primary reference for Mixer implementation
2. Pipeline.py (lines 10-58) — Primary reference for Pipeline implementation
3. Generators.py (lines 11-134) — Primary reference for ToneSource implementation
4. Telephony.py (lines 169-732) — Integration patterns and usage examples
