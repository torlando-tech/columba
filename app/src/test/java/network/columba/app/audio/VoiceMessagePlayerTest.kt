package network.columba.app.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import network.columba.app.ui.model.AudioAttachmentMode
import network.columba.app.ui.model.AudioAttachmentUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceMessagePlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val attachment = AudioAttachmentUi(mode = AudioAttachmentMode.AM_OPUS_OGG, isPlayable = true)

    @Test
    fun `play prepares and starts loaded audio`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 2_500)
        val player = createPlayer(this, engine)

        player.play("message-1", attachment)
        advanceUntilIdle()
        engine.completePreparation()

        assertTrue(engine.started)
        assertEquals(
            VoiceMessagePlayerState(
                playing = true,
                durationMs = 2_500,
                messageKey = "message-1",
            ),
            player.state.value,
        )
        player.close()
    }

    @Test
    fun `codec2 attachment is decoded to wave before playback`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 20)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val codec2 =
            Codec2RawAudioCodec {
                object : Codec2Session {
                    override val samplesPerFrame = 160
                    override val bytesPerFrame = 2

                    override fun encode(pcm: ShortArray, output: ByteArray): Int = error("unused")

                    override fun decode(encoded: ByteArray, output: ShortArray): Int {
                        output.fill(1_000)
                        return output.size
                    }

                    override fun close() = Unit
                }
            }
        val codec2Attachment = AudioAttachmentUi(mode = AudioAttachmentMode.AM_CODEC2_1200, isPlayable = true)
        val player =
            VoiceMessagePlayer(
                context = context,
                scope = this,
                loadBytes = { byteArrayOf(1, 2) },
                playerFactory = PlaybackEngineFactory { engine },
                codec2Codec = codec2,
                ioDispatcher = dispatcher,
            )

        player.play("codec2", codec2Attachment)
        advanceUntilIdle()

        val wave = requireNotNull(engine.sourceFile).readBytes()
        assertEquals("RIFF", wave.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wave.copyOfRange(8, 12).decodeToString())
        engine.completePreparation()
        assertTrue(engine.started)
        player.close()
    }

    @Test
    fun `toggle pauses and resumes same message without seeking to start`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 2_500)
        val player = createPlayer(this, engine)
        player.play("message-1", attachment)
        advanceUntilIdle()
        engine.completePreparation()
        engine.currentPositionMs = 700

        player.toggle("message-1", attachment)
        player.toggle("message-1", attachment)

        assertTrue(engine.paused)
        assertEquals(emptyList<Int>(), engine.seekPositions)
        assertEquals(2, engine.startCount)
        assertTrue(player.state.value.playing)
        player.close()
    }

    @Test
    fun `toggle after completion restarts from beginning`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 2_500)
        val player = createPlayer(this, engine)
        player.play("message-1", attachment)
        advanceUntilIdle()
        engine.completePreparation()
        engine.completePlayback()

        player.toggle("message-1", attachment)

        assertEquals(listOf(0), engine.seekPositions)
        assertEquals(2, engine.startCount)
        assertTrue(player.state.value.playing)
        player.close()
    }

    @Test
    fun `playing another message releases previous engine and deletes temp file`() = runTest {
        val first = FakePlaybackEngine(durationMs = 1_000)
        val second = FakePlaybackEngine(durationMs = 1_000)
        val engines = ArrayDeque(listOf(first, second))
        val player = createPlayer(this) { engines.removeFirst() }

        player.play("message-1", attachment)
        advanceUntilIdle()
        first.completePreparation()
        val firstFile = first.sourceFile!!
        assertTrue(firstFile.exists())

        player.play("message-2", attachment)
        advanceUntilIdle()

        assertTrue(first.released)
        assertFalse(firstFile.exists())
        assertEquals("message-2", player.state.value.messageKey)
        player.close()
    }

    @Test
    fun `close releases engine deletes temp file and clears state`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 1_000)
        val player = createPlayer(this, engine)
        player.play("message-1", attachment)
        advanceUntilIdle()
        val file = engine.sourceFile!!

        player.close()

        assertTrue(engine.released)
        assertFalse(file.exists())
        assertEquals(VoiceMessagePlayerState(), player.state.value)
    }

    @Test
    fun `toggleFile previews selected recording without deleting it`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 1_500)
        val player = createPlayer(this, engine)
        val recording = java.io.File.createTempFile("voice_preview", ".ogg", context.cacheDir).apply { writeText("OggS") }

        player.toggleFile("preview", recording)
        engine.completePreparation()

        assertTrue(engine.started)
        assertEquals(recording, engine.sourceFile)
        assertEquals("preview", player.state.value.messageKey)
        player.close()
        assertTrue(recording.exists())
        recording.delete()
    }

    @Test
    fun `missing payload produces unavailable state without creating engine`() = runTest {
        var factoryCalls = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val player =
            VoiceMessagePlayer(
                context = context,
                scope = this,
                loadBytes = { null },
                playerFactory = PlaybackEngineFactory {
                    factoryCalls += 1
                    FakePlaybackEngine()
                },
                ioDispatcher = dispatcher,
            )

        player.play("message-1", attachment)
        advanceUntilIdle()

        assertEquals(0, factoryCalls)
        assertEquals("unavailable", player.state.value.error)
        assertEquals("message-1", player.state.value.messageKey)
    }

    @Test
    fun `concurrent same-key metadata requests start only one load`() = runTest {
        val loadCalls = AtomicInteger()
        val player =
            createMetadataPlayer(this) {
                loadCalls.incrementAndGet()
                null
            }
        val start = CountDownLatch(1)
        val complete = CountDownLatch(8)
        val executor = Executors.newFixedThreadPool(8)

        repeat(8) {
            executor.execute {
                start.await()
                player.prepareMetadata("same", attachment)
                complete.countDown()
            }
        }
        start.countDown()
        assertTrue(complete.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()

        advanceUntilIdle()

        assertEquals(1, loadCalls.get())
    }

    @Test
    fun `cancelMetadata cancels an off-screen metadata load`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val player =
            createMetadataPlayer(this) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }

        player.prepareMetadata("message", attachment)
        runCurrent()
        started.await()

        player.cancelMetadata("message")
        runCurrent()

        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun `metadata semaphore permits only one active load`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val loadCalls = AtomicInteger()
        val player =
            createMetadataPlayer(this) {
                loadCalls.incrementAndGet()
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    gate.await()
                    null
                } finally {
                    active.decrementAndGet()
                }
            }

        player.prepareMetadata("first", attachment)
        player.prepareMetadata("second", attachment)
        runCurrent()

        assertEquals(1, loadCalls.get())
        assertEquals(1, maximumActive.get())

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, loadCalls.get())
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun `waveform failure does not block playback`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 1_000)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bytes = validOggOpusBytes()
        val player =
            VoiceMessagePlayer(
                context = context,
                scope = this,
                loadBytes = { bytes },
                playerFactory = PlaybackEngineFactory { engine },
                waveformReader = AudioWaveformReader { _, _ -> error("decoder failure") },
                ioDispatcher = dispatcher,
            )

        player.prepareMetadata("message-1", attachment)
        advanceUntilIdle()
        assertTrue(player.metadata.value.isEmpty())

        player.play("message-1", attachment)
        advanceUntilIdle()
        engine.completePreparation()

        assertTrue(engine.started)
        assertTrue(player.state.value.playing)
        player.close()
    }

    private fun validOggOpusBytes(): ByteArray {
        val opusHead =
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".encodeToByteArray())
                .put(1).put(1).putShort(312.toShort()).putInt(48_000).putShort(0.toShort()).put(0).array()
        return oggPage(0, 0, opusHead) + oggPage(1, 48_000, ByteArray(16) { 1 })
    }

    private fun oggPage(sequence: Int, granule: Long, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write("OggS".encodeToByteArray())
            write(0)
            write(if (sequence == 0) 2 else 0)
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(granule).array())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sequence).array())
            write(ByteArray(4))
            write(1)
            write(payload.size)
            write(payload)
        }.toByteArray()

    private fun createMetadataPlayer(
        scope: TestScope,
        loadBytes: suspend (AudioAttachmentUi) -> ByteArray?,
    ): VoiceMessagePlayer {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return VoiceMessagePlayer(
            context = context,
            scope = scope,
            loadBytes = loadBytes,
            playerFactory = PlaybackEngineFactory { FakePlaybackEngine() },
            ioDispatcher = dispatcher,
        )
    }

    private fun createPlayer(scope: TestScope, engine: FakePlaybackEngine): VoiceMessagePlayer =
        createPlayer(scope) { engine }

    private fun createPlayer(
        scope: TestScope,
        factory: () -> VoicePlaybackEngine,
    ): VoiceMessagePlayer {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return VoiceMessagePlayer(
            context = context,
            scope = scope,
            loadBytes = { byteArrayOf(0x4f, 0x67, 0x67, 0x53) },
            playerFactory = PlaybackEngineFactory(factory),
            ioDispatcher = dispatcher,
        )
    }
}

private class FakePlaybackEngine(
    override var durationMs: Int = 0,
) : VoicePlaybackEngine {
    override var currentPositionMs: Int = 0
    override var isPlaying: Boolean = false
    var sourceFile: java.io.File? = null
    var started = false
    var paused = false
    var released = false
    var startCount = 0
    val seekPositions = mutableListOf<Int>()
    private var preparedListener: ((VoicePlaybackEngine) -> Unit)? = null
    private var completionListener: ((VoicePlaybackEngine) -> Unit)? = null
    private var errorListener: (() -> Unit)? = null

    override fun setDataSource(file: java.io.File) {
        sourceFile = file
    }

    override fun setOnPreparedListener(listener: (VoicePlaybackEngine) -> Unit) {
        preparedListener = listener
    }

    override fun setOnCompletionListener(listener: (VoicePlaybackEngine) -> Unit) {
        completionListener = listener
    }

    override fun setOnErrorListener(listener: () -> Unit) {
        errorListener = listener
    }

    override fun prepareAsync() = Unit

    override fun start() {
        started = true
        startCount += 1
        isPlaying = true
    }

    override fun pause() {
        paused = true
        isPlaying = false
    }

    override fun seekTo(positionMs: Int) {
        seekPositions += positionMs
        currentPositionMs = positionMs
    }

    override fun release() {
        released = true
        isPlaying = false
    }

    fun completePreparation() = preparedListener!!.invoke(this)

    fun completePlayback() {
        currentPositionMs = durationMs
        isPlaying = false
        completionListener!!.invoke(this)
    }
}
