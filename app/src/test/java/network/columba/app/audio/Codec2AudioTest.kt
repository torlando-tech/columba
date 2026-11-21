package network.columba.app.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.recording.RecorderState
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class Codec2AudioTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `raw decoder consumes complete codec2 frames without an LXST mode header`() {
        val codec = Codec2RawAudioCodec { FakeCodec2Session() }

        val decoded = codec.decode(byteArrayOf(1, 2, 3, 4), Codec2.CODEC2_1200)

        assertEquals(320, decoded.samples.size)
        assertEquals(40, decoded.durationMillis)
        assertEquals(1, decoded.samples[0].toInt())
        assertEquals(3, decoded.samples[160].toInt())
    }

    @Test
    fun `raw decoder rejects incomplete and over-duration payloads before allocation`() {
        val codec = Codec2RawAudioCodec { FakeCodec2Session() }

        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(byteArrayOf(1, 2, 3), Codec2.CODEC2_1200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(ByteArray(30_002), Codec2.CODEC2_1200)
        }
    }

    @Test
    fun `decoded codec2 exports a mono 8kHz PCM WAV directly to a stream`() {
        val codec = Codec2RawAudioCodec { FakeCodec2Session() }
        val decoded = codec.decode(byteArrayOf(1, 2, 3, 4), Codec2.CODEC2_1200)
        val output = ByteArrayOutputStream()

        codec.writeWave(decoded, output)

        val wave = output.toByteArray()
        val header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(684, wave.size)
        assertArrayEquals("RIFF".encodeToByteArray(), wave.copyOfRange(0, 4))
        assertEquals(676, header.getInt(4))
        assertArrayEquals("WAVEfmt ".encodeToByteArray(), wave.copyOfRange(8, 16))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(8_000, header.getInt(24))
        assertEquals(16_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertArrayEquals("data".encodeToByteArray(), wave.copyOfRange(36, 40))
        assertEquals(640, header.getInt(40))
        repeat(160) { index ->
            assertEquals(1, header.getShort(44 + index * 2).toInt())
            assertEquals(3, header.getShort(364 + index * 2).toInt())
        }
    }

    @Test
    fun `WAV samples retain signed 16 bit little endian values`() {
        val codec = Codec2RawAudioCodec { FakeCodec2Session() }
        val decoded = DecodedCodec2Audio(shortArrayOf(Short.MIN_VALUE, -257, -1, 0, 1, 0x1234, Short.MAX_VALUE))
        val output = ByteArrayOutputStream()

        codec.writeWave(decoded, output)

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x80.toByte(),
                0xff.toByte(), 0xfe.toByte(),
                0xff.toByte(), 0xff.toByte(),
                0x00, 0x00,
                0x01, 0x00,
                0x34, 0x12,
                0xff.toByte(), 0x7f,
            ),
            output.toByteArray().copyOfRange(44, 58),
        )
    }

    @Test
    fun `WAV writer leaves flushing and closing to the caller`() {
        val codec = Codec2RawAudioCodec { FakeCodec2Session() }
        val output =
            object : ByteArrayOutputStream() {
                var closed = false
                var flushed = false

                override fun close() {
                    closed = true
                    super.close()
                }

                override fun flush() {
                    flushed = true
                    super.flush()
                }
            }

        output.use {
            codec.writeWave(DecodedCodec2Audio(shortArrayOf(7)), it)
            assertFalse(output.closed)
            assertFalse(output.flushed)
            output.write(0x7f)
            assertEquals(47, output.size())
            output.flush()
            assertTrue(output.flushed)
        }
        assertTrue(output.closed)
    }

    @Test
    fun `codec2 recorder writes raw concatenated frames and reports duration`() {
        val processedFrames = CountDownLatch(2)
        val capture = FakePcmCapture(processedFrames)
        val backend =
            Codec2VoiceRecorderBackend(
                mode = Codec2.CODEC2_3200,
                captureFactory = { capture },
                sessionFactory = { FakeCodec2Session() },
            )
        val output = File(context.cacheDir, "codec2-recorder-test-${System.nanoTime()}.c2")

        backend.start(output)
        assertTrue(processedFrames.await(2, TimeUnit.SECONDS))
        val recording = backend.stop()

        assertArrayEquals(byteArrayOf(1, 2, 2, 3), output.readBytes())
        assertEquals(40L, recording.durationMillis)
        assertTrue(backend.state.value is RecorderState.Completed)
        assertFalse(capture.active.get())
        output.delete()
        backend.close()
    }

    @Test
    fun `worker failure overlapping stop rejects and deletes partial recording`() {
        val session = StopRaceFailingCodec2Session()
        val capture = StopRacePcmCapture(session::releaseFailure)
        val backend =
            Codec2VoiceRecorderBackend(
                mode = Codec2.CODEC2_1200,
                captureFactory = { capture },
                sessionFactory = { session },
            )
        val output = File(context.cacheDir, "codec2-recorder-stop-race-${System.nanoTime()}.c2")

        backend.start(output)
        assertTrue(session.failureStarted.await(2, TimeUnit.SECONDS))

        val failure = assertThrows(IllegalStateException::class.java) { backend.stop() }

        assertTrue(failure.message?.contains("Codec2 recording failed") == true)
        assertTrue(backend.state.value is RecorderState.Failed)
        assertFalse(output.exists())
        assertFalse(capture.active.get())
        assertTrue(capture.closed.get())
        assertTrue(session.closed.get())
        backend.close()
    }

    @Test
    fun `capture failure releases microphone codec and partial output`() {
        val capture = FailingPcmCapture()
        val session = FakeCodec2Session()
        val backend =
            Codec2VoiceRecorderBackend(
                mode = Codec2.CODEC2_1200,
                captureFactory = { capture },
                sessionFactory = { session },
            )
        val output = File(context.cacheDir, "codec2-recorder-failure-${System.nanoTime()}.c2")

        backend.start(output)
        assertTrue(capture.readCalled.await(2, TimeUnit.SECONDS))
        repeat(100) {
            if (backend.state.value !is RecorderState.Failed) Thread.sleep(5)
        }

        assertTrue(backend.state.value is RecorderState.Failed)
        assertFalse(capture.active.get())
        assertTrue(capture.closed.get())
        assertTrue(session.closed.get())
        assertFalse(output.exists())
        backend.close()
    }

    private class FakeCodec2Session : Codec2Session {
        override val samplesPerFrame: Int = 160
        override val bytesPerFrame: Int = 2
        private var encodedFrame = 0
        val closed = AtomicBoolean(false)

        override fun encode(pcm: ShortArray, output: ByteArray): Int {
            encodedFrame += 1
            output[0] = encodedFrame.toByte()
            output[1] = (encodedFrame + 1).toByte()
            return output.size
        }

        override fun decode(encoded: ByteArray, output: ShortArray): Int {
            output.fill(encoded[0].toShort())
            return output.size
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class FakePcmCapture(
        private val frames: CountDownLatch,
    ) : PcmCapture {
        override val isSupported: Boolean = true
        val active = AtomicBoolean(false)
        private var nextSample: Short = 1

        override fun start() {
            active.set(true)
        }

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            if (!active.get()) return 0
            // A new read starts only after the preceding frame was encoded and buffered.
            if (nextSample > 1) frames.countDown()
            if (frames.count == 0L) {
                Thread.sleep(5)
                return 0
            }
            repeat(size) { buffer[offset + it] = nextSample }
            nextSample = (nextSample + 1).toShort()
            return size
        }

        override fun stop() {
            active.set(false)
        }

        override fun close() {
            active.set(false)
        }
    }

    private class StopRacePcmCapture(
        private val onStop: () -> Unit,
    ) : PcmCapture {
        override val isSupported = true
        val active = AtomicBoolean(false)
        val closed = AtomicBoolean(false)

        override fun start() {
            active.set(true)
        }

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            if (!active.get()) return 0
            repeat(size) { buffer[offset + it] = 1 }
            return size
        }

        override fun stop() {
            active.set(false)
            onStop()
        }

        override fun close() {
            active.set(false)
            closed.set(true)
            onStop()
        }
    }

    private class StopRaceFailingCodec2Session : Codec2Session {
        override val samplesPerFrame = 160
        override val bytesPerFrame = 2
        val closed = AtomicBoolean(false)
        val failureStarted = CountDownLatch(1)
        private val releaseFailure = CountDownLatch(1)
        private var encodes = 0

        override fun encode(pcm: ShortArray, output: ByteArray): Int {
            encodes += 1
            if (encodes == 1) {
                output.fill(1)
                return output.size
            }
            failureStarted.countDown()
            check(releaseFailure.await(2, TimeUnit.SECONDS))
            return 0
        }

        fun releaseFailure() {
            releaseFailure.countDown()
        }

        override fun decode(encoded: ByteArray, output: ShortArray): Int = output.size

        override fun close() {
            closed.set(true)
            releaseFailure.countDown()
        }
    }

    private class FailingPcmCapture : PcmCapture {
        override val isSupported = true
        val active = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val readCalled = CountDownLatch(1)

        override fun start() {
            active.set(true)
        }

        override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
            readCalled.countDown()
            return -3
        }

        override fun stop() {
            active.set(false)
        }

        override fun close() {
            active.set(false)
            closed.set(true)
        }
    }
}
