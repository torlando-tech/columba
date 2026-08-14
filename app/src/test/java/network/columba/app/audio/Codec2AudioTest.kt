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
import java.io.File
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
    fun `codec2 recorder writes raw concatenated frames and reports duration`() {
        val capturedFrames = CountDownLatch(2)
        val capture = FakePcmCapture(capturedFrames)
        val backend =
            Codec2VoiceRecorderBackend(
                mode = Codec2.CODEC2_3200,
                captureFactory = { capture },
                sessionFactory = { FakeCodec2Session() },
            )
        val output = File(context.cacheDir, "codec2-recorder-test-${System.nanoTime()}.c2")

        backend.start(output)
        assertTrue(capturedFrames.await(2, TimeUnit.SECONDS))
        val recording = backend.stop()

        assertArrayEquals(byteArrayOf(1, 2, 2, 3), output.readBytes())
        assertEquals(40L, recording.durationMillis)
        assertTrue(backend.state.value is RecorderState.Completed)
        assertFalse(capture.active.get())
        output.delete()
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
            if (frames.count == 0L) {
                Thread.sleep(5)
                return 0
            }
            repeat(size) { buffer[offset + it] = nextSample }
            nextSample = (nextSample + 1).toShort()
            frames.countDown()
            return size
        }

        override fun stop() {
            active.set(false)
        }

        override fun close() {
            active.set(false)
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
