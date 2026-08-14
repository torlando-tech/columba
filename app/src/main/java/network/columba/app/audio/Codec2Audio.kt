package network.columba.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.columba.app.ui.model.AudioAttachmentMode
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.NativeCodec2
import tech.torlando.lxst.recording.RecordedAudio
import tech.torlando.lxst.recording.RecorderState
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal interface Codec2Session : AutoCloseable {
    val samplesPerFrame: Int
    val bytesPerFrame: Int
    fun encode(pcm: ShortArray, output: ByteArray): Int
    fun decode(encoded: ByteArray, output: ShortArray): Int
}

internal fun interface Codec2SessionFactory {
    fun create(mode: Int): Codec2Session
}

private object NativeCodec2SessionFactory : Codec2SessionFactory {
    override fun create(mode: Int): Codec2Session = NativeCodec2Session(mode)
}

private class NativeCodec2Session(mode: Int) : Codec2Session {
    private val handle = NativeCodec2.create(mode.toNativeCodec2Mode())

    init {
        check(handle != 0L) { "Unable to initialize Codec2 mode $mode" }
    }

    override val samplesPerFrame: Int = NativeCodec2.getSamplesPerFrame(handle)
    override val bytesPerFrame: Int = NativeCodec2.getFrameBytes(handle)
    override fun encode(pcm: ShortArray, output: ByteArray): Int = NativeCodec2.encode(handle, pcm, output)
    override fun decode(encoded: ByteArray, output: ShortArray): Int = NativeCodec2.decode(handle, encoded, output)
    override fun close() = NativeCodec2.destroy(handle)
}

private fun Int.toNativeCodec2Mode(): Int =
    when (this) {
        Codec2.CODEC2_700C -> NativeCodec2.MODE_700C
        Codec2.CODEC2_1200 -> NativeCodec2.MODE_1200
        Codec2.CODEC2_1300 -> NativeCodec2.MODE_1300
        Codec2.CODEC2_1400 -> NativeCodec2.MODE_1400
        Codec2.CODEC2_1600 -> NativeCodec2.MODE_1600
        Codec2.CODEC2_2400 -> NativeCodec2.MODE_2400
        Codec2.CODEC2_3200 -> NativeCodec2.MODE_3200
        else -> error("Unsupported Codec2 mode: $this")
    }

internal fun AudioAttachmentMode.codec2Bitrate(): Int? =
    when (this) {
        AudioAttachmentMode.AM_CODEC2_700C -> Codec2.CODEC2_700C
        AudioAttachmentMode.AM_CODEC2_1200 -> Codec2.CODEC2_1200
        AudioAttachmentMode.AM_CODEC2_1300 -> Codec2.CODEC2_1300
        AudioAttachmentMode.AM_CODEC2_1400 -> Codec2.CODEC2_1400
        AudioAttachmentMode.AM_CODEC2_1600 -> Codec2.CODEC2_1600
        AudioAttachmentMode.AM_CODEC2_2400 -> Codec2.CODEC2_2400
        AudioAttachmentMode.AM_CODEC2_3200 -> Codec2.CODEC2_3200
        else -> null
    }

internal data class DecodedCodec2Audio(
    val samples: ShortArray,
    val sampleRateHz: Int = Codec2.INPUT_RATE,
) {
    val durationMillis: Int
        get() = ((samples.size.toLong() * 1_000L) / sampleRateHz).toInt()
}

internal class Codec2RawAudioCodec(
    private val sessionFactory: Codec2SessionFactory = NativeCodec2SessionFactory,
) {
    fun decode(bytes: ByteArray, mode: Int): DecodedCodec2Audio =
        sessionFactory.create(mode).use { session ->
            require(bytes.size % session.bytesPerFrame == 0) { "Codec2 payload ends with an incomplete frame" }
            val frameCount = bytes.size / session.bytesPerFrame
            require(frameCount > 0) { "Codec2 payload contains no complete frames" }
            val sampleCount = frameCount.toLong() * session.samplesPerFrame
            require(sampleCount <= MAX_DECODED_SAMPLES) { "Codec2 payload exceeds the five-minute playback limit" }
            val samples = ShortArray(sampleCount.toInt())
            val encoded = ByteArray(session.bytesPerFrame)
            val decoded = ShortArray(session.samplesPerFrame)
            repeat(frameCount) { frameIndex ->
                bytes.copyInto(
                    destination = encoded,
                    startIndex = frameIndex * session.bytesPerFrame,
                    endIndex = (frameIndex + 1) * session.bytesPerFrame,
                )
                check(session.decode(encoded, decoded) > 0) { "Codec2 decoding failed" }
                decoded.copyInto(samples, frameIndex * session.samplesPerFrame)
            }
            DecodedCodec2Audio(samples)
        }

    fun writeWave(decoded: DecodedCodec2Audio, output: File) {
        val pcmBytes = decoded.samples.size * Short.SIZE_BYTES
        output.outputStream().buffered().use { wave ->
            wave.write("RIFF".encodeToByteArray())
            wave.writeIntLe(36 + pcmBytes)
            wave.write("WAVEfmt ".encodeToByteArray())
            wave.writeIntLe(16)
            wave.writeShortLe(1)
            wave.writeShortLe(1)
            wave.writeIntLe(decoded.sampleRateHz)
            wave.writeIntLe(decoded.sampleRateHz * Short.SIZE_BYTES)
            wave.writeShortLe(Short.SIZE_BYTES)
            wave.writeShortLe(16)
            wave.write("data".encodeToByteArray())
            wave.writeIntLe(pcmBytes)
            val buffer = ByteArray(PCM_WRITE_BUFFER_BYTES)
            var sampleOffset = 0
            while (sampleOffset < decoded.samples.size) {
                val sampleCount = minOf(buffer.size / Short.SIZE_BYTES, decoded.samples.size - sampleOffset)
                repeat(sampleCount) { index ->
                    val sample = decoded.samples[sampleOffset + index].toInt()
                    buffer[index * 2] = sample.toByte()
                    buffer[index * 2 + 1] = (sample ushr 8).toByte()
                }
                wave.write(buffer, 0, sampleCount * Short.SIZE_BYTES)
                sampleOffset += sampleCount
            }
        }
    }

    private companion object {
        const val MAX_DECODED_SAMPLES = Codec2.INPUT_RATE * 60 * 5
        const val PCM_WRITE_BUFFER_BYTES = 8 * 1024
    }
}

private fun OutputStream.writeIntLe(value: Int) {
    write(value)
    write(value ushr 8)
    write(value ushr 16)
    write(value ushr 24)
}

private fun OutputStream.writeShortLe(value: Int) {
    write(value)
    write(value ushr 8)
}

internal interface PcmCapture : AutoCloseable {
    val isSupported: Boolean
    fun start()
    fun read(buffer: ShortArray, offset: Int, size: Int): Int
    fun stop()
}

private class AndroidPcmCapture : PcmCapture {
    private val minBufferSize =
        AudioRecord.getMinBufferSize(
            Codec2.INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    private val recorder by lazy {
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Codec2.INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize.coerceAtLeast(Codec2.INPUT_RATE),
        )
    }

    override val isSupported: Boolean get() = minBufferSize > 0
    override fun start() = recorder.startRecording()
    override fun read(buffer: ShortArray, offset: Int, size: Int): Int =
        recorder.read(buffer, offset, size, AudioRecord.READ_BLOCKING)
    override fun stop() {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
    }
    override fun close() = recorder.release()
}

internal class Codec2VoiceRecorderBackend(
    private val mode: Int,
    private val captureFactory: () -> PcmCapture = { AndroidPcmCapture() },
    private val sessionFactory: Codec2SessionFactory = NativeCodec2SessionFactory,
) : VoiceRecorderBackend {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    override val state: StateFlow<RecorderState> = _state.asStateFlow()
    private var capture: PcmCapture? = null
    private var session: Codec2Session? = null
    private var worker: Thread? = null
    private var outputFile: File? = null
    private var workerFailure: Throwable? = null
    private var encodedSamples = 0L
    private val running = AtomicBoolean(false)

    override val isSupported: Boolean
        get() = runCatching { captureFactory().use { it.isSupported } }.getOrDefault(false)

    override fun start(outputFile: File) {
        check(!running.get()) { "Recording already active" }
        val newCapture = captureFactory()
        var newSession: Codec2Session? = null
        try {
            check(newCapture.isSupported) { "Codec2 recording is unsupported on this device" }
            val activeSession = sessionFactory.create(mode)
            newSession = activeSession
            outputFile.parentFile?.mkdirs()
            outputFile.delete()
            this.outputFile = outputFile
            capture = newCapture
            session = activeSession
            workerFailure = null
            encodedSamples = 0L
            newCapture.start()
            running.set(true)
            _state.value = RecorderState.Recording(SystemClock.elapsedRealtime())
            worker =
                Thread({ captureLoop(newCapture, activeSession, outputFile) }, "codec2-voice-recorder").apply {
                    start()
                }
        } catch (error: Throwable) {
            running.set(false)
            runCatching { newCapture.close() }
            runCatching { newSession?.close() }
            outputFile.delete()
            capture = null
            session = null
            _state.value = RecorderState.Failed(error)
            throw error
        }
    }

    private fun captureLoop(activeCapture: PcmCapture, activeSession: Codec2Session, output: File) {
        try {
            output.outputStream().buffered().use { sink ->
                val frame = ShortArray(activeSession.samplesPerFrame)
                while (running.get()) {
                    var filled = 0
                    while (running.get() && filled < frame.size) {
                        val read = activeCapture.read(frame, filled, frame.size - filled)
                        if (!running.get()) break
                        if (read < 0) error("AudioRecord read failed: $read")
                        if (read == 0) continue
                        filled += read
                    }
                    if (filled != frame.size) break
                    val encoded = ByteArray(activeSession.bytesPerFrame)
                    check(activeSession.encode(frame, encoded) == encoded.size) { "Codec2 encoding failed" }
                    sink.write(encoded)
                    encodedSamples += frame.size
                }
            }
        } catch (error: Throwable) {
            if (running.compareAndSet(true, false)) {
                workerFailure = error
                runCatching { activeCapture.stop() }
                runCatching { activeCapture.close() }
                runCatching { activeSession.close() }
                output.delete()
                capture = null
                session = null
                _state.value = RecorderState.Failed(error)
            }
        }
    }

    override fun stop(): RecordedAudio {
        check(running.getAndSet(false)) { "No recording is active" }
        _state.value = RecorderState.Finalizing
        runCatching { capture?.stop() }
        worker?.join(WORKER_JOIN_TIMEOUT_MILLIS)
        check(worker?.isAlive != true) { "Codec2 recorder did not stop" }
        workerFailure?.let { throw IllegalStateException("Codec2 recording failed", it) }
        val file = checkNotNull(outputFile)
        check(file.isFile && file.length() > 0L) { "Codec2 recording produced no audio" }
        val recording =
            RecordedAudio(
                file = file,
                durationMillis = encodedSamples * 1_000L / Codec2.INPUT_RATE,
                sizeBytes = file.length(),
            )
        _state.value = RecorderState.Completed(recording)
        releaseRuntime()
        return recording
    }

    override fun cancel() {
        val wasActive = running.getAndSet(false)
        runCatching { capture?.stop() }
        worker?.join(WORKER_JOIN_TIMEOUT_MILLIS)
        if (wasActive) outputFile?.delete()
        releaseRuntime()
        _state.value = RecorderState.Idle
    }

    override fun close() {
        if (running.get()) cancel() else releaseRuntime()
    }

    private fun releaseRuntime() {
        runCatching { capture?.close() }
        runCatching { session?.close() }
        capture = null
        session = null
        worker = null
    }

    private companion object {
        const val WORKER_JOIN_TIMEOUT_MILLIS = 3_000L
    }
}
