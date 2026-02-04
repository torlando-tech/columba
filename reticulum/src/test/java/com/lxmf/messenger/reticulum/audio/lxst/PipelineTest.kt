package com.lxmf.messenger.reticulum.audio.lxst

import com.lxmf.messenger.reticulum.audio.codec.Codec
import com.lxmf.messenger.reticulum.audio.codec.Null
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Pipeline wiring and lifecycle.
 *
 * Tests source-sink wiring and lifecycle delegation
 * without requiring Android (no JNI/native code).
 *
 * Note: Pipeline is pure coordination - it delegates all work to source.
 */
class PipelineTest {

    // ===== Wiring tests =====

    @Test
    fun `pipeline wires sink to ToneSource`() {
        val mockSink = mockk<Sink>(relaxed = true)
        val codec = Null()
        val source = ToneSource()

        val pipeline = Pipeline(source, codec, mockSink)

        // Verify sink is wired
        assertEquals(mockSink, source.sink)

        source.release()
    }

    @Test
    fun `pipeline wires sink to Mixer`() {
        val mockSink = mockk<Sink>(relaxed = true)
        val codec = Null()
        val source = Mixer()

        val pipeline = Pipeline(source, codec, mockSink)

        // Verify sink is wired
        assertEquals(mockSink, source.sink)
    }

    @Test
    fun `pipeline wires codec to Mixer`() {
        val mockSink = mockk<Sink>(relaxed = true)
        val codec = Null()
        val source = Mixer()

        val pipeline = Pipeline(source, codec, mockSink)

        // Verify codec is wired to Mixer
        assertEquals(codec, source.codec)
    }

    // ===== Lifecycle delegation tests =====

    @Test
    fun `pipeline start delegates to source`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val codec = Null()
        val source = ToneSource(ease = false)

        val pipeline = Pipeline(source, codec, mockSink)

        assertFalse(source.isRunning())

        pipeline.start()

        assertTrue(source.isRunning())

        pipeline.stop()
        source.release()
    }

    @Test
    fun `pipeline stop delegates to source`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val codec = Null()
        val source = ToneSource(ease = false)

        val pipeline = Pipeline(source, codec, mockSink)

        pipeline.start()
        assertTrue(source.isRunning())

        pipeline.stop()
        assertFalse(source.isRunning())

        source.release()
    }

    @Test
    fun `pipeline running reflects source state`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val codec = Null()
        val source = ToneSource(ease = false)

        val pipeline = Pipeline(source, codec, mockSink)

        // Initially not running
        assertFalse(pipeline.running)

        // Start
        pipeline.start()
        assertTrue(pipeline.running)

        // Stop
        pipeline.stop()
        assertFalse(pipeline.running)

        source.release()
    }

    @Test
    fun `pipeline start is idempotent`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val codec = Null()
        val source = ToneSource(ease = false)

        val pipeline = Pipeline(source, codec, mockSink)

        pipeline.start()
        pipeline.start() // Second call should be no-op

        assertTrue(pipeline.running)

        pipeline.stop()
        source.release()
    }

    @Test
    fun `pipeline stop is idempotent`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val codec = Null()
        val source = ToneSource(ease = false)

        val pipeline = Pipeline(source, codec, mockSink)

        pipeline.start()
        pipeline.stop()
        pipeline.stop() // Second call should be no-op

        assertFalse(pipeline.running)

        source.release()
    }

    // ===== Codec setter tests =====

    @Test
    fun `pipeline codec setter updates Mixer codec`() {
        val mockSink = mockk<Sink>(relaxed = true)
        val codec1 = Null()
        val codec2 = Null()
        val source = Mixer()

        val pipeline = Pipeline(source, codec1, mockSink)

        assertEquals(codec1, source.codec)

        // Change codec
        pipeline.codec = codec2

        assertEquals(codec2, source.codec)
    }

    @Test
    fun `pipeline codec getter returns current codec`() {
        val mockSink = mockk<Sink>(relaxed = true)
        val codec = Null()
        val source = Mixer()

        val pipeline = Pipeline(source, codec, mockSink)

        assertEquals(codec, pipeline.codec)
    }

    // ===== Source property tests =====

    @Test
    fun `pipeline exposes source property`() {
        val mockSink = mockk<Sink>(relaxed = true)
        val codec = Null()
        val source = ToneSource()

        val pipeline = Pipeline(source, codec, mockSink)

        assertEquals(source, pipeline.source)

        source.release()
    }

    @Test
    fun `pipeline exposes sink property`() {
        val mockSink = mockk<Sink>(relaxed = true)
        val codec = Null()
        val source = ToneSource()

        val pipeline = Pipeline(source, codec, mockSink)

        assertEquals(mockSink, pipeline.sink)

        source.release()
    }

    // ===== Mixer as source tests =====

    @Test
    fun `pipeline with Mixer source starts and stops`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val codec = Null()
        val source = Mixer()

        val pipeline = Pipeline(source, codec, mockSink)

        assertFalse(pipeline.running)

        pipeline.start()
        assertTrue(pipeline.running)

        pipeline.stop()
        assertFalse(pipeline.running)
    }

    // ===== Edge cases =====

    @Test
    fun `start on non-running pipeline works`() {
        val mockSink = mockk<Sink>(relaxed = true)
        every { mockSink.canReceive(any()) } returns true

        val codec = Null()
        val source = ToneSource(ease = false)

        val pipeline = Pipeline(source, codec, mockSink)

        // Should not throw
        pipeline.start()
        assertTrue(pipeline.running)

        pipeline.stop()
        source.release()
    }

    @Test
    fun `stop on non-running pipeline is safe`() {
        val mockSink = mockk<Sink>(relaxed = true)
        val codec = Null()
        val source = ToneSource()

        val pipeline = Pipeline(source, codec, mockSink)

        // Should not throw
        pipeline.stop()
        assertFalse(pipeline.running)

        source.release()
    }
}
