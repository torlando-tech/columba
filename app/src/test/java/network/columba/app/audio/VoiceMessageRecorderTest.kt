package network.columba.app.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.torlando.lxst.recording.RecordedAudio
import tech.torlando.lxst.recording.RecorderState
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceMessageRecorderTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `start rejects unsupported devices`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir, isSupported = false)
        val controller = createController(this, backend)

        assertFalse(controller.isSupported)
        controller.close()
    }

    @Test
    fun `start and stop select finalized recording`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)

        val output = controller.start()
        assertEquals(output, controller.state.value.activeRecordingFile)
        assertTrue(controller.state.value.recorderState is RecorderState.Recording)

        val recording = controller.stop()

        assertEquals(recording, controller.state.value.selectedRecording)
        assertTrue(recording.file.isFile)
        assertNull(controller.state.value.activeRecordingFile)
        controller.close()
    }

    @Test
    fun `cancel discards active recording and resets state`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)
        controller.start()

        controller.cancel()

        assertTrue(backend.cancelled)
        assertEquals(VoiceMessageRecordingState(), controller.state.value)
        controller.close()
    }

    @Test
    fun `remove selected deletes finalized recording`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)
        controller.start()
        val recording = controller.stop()
        assertTrue(recording.file.exists())

        controller.removeSelected()

        assertFalse(recording.file.exists())
        assertNull(controller.state.value.selectedRecording)
        controller.close()
    }

    @Test
    fun `failed replacement start preserves finalized recording`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)
        controller.start()
        val original = controller.stop()
        backend.failNextStart = true

        runCatching { controller.start() }

        assertEquals(original, controller.state.value.selectedRecording)
        assertTrue(original.file.exists())
        assertFalse(checkNotNull(backend.lastStartOutput).exists())
        controller.close()
    }

    @Test
    fun `identity cleanup cannot remove a replacement recording`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)
        controller.start()
        val original = controller.stop()
        controller.start()
        val replacement = controller.stop()

        assertFalse(controller.removeSelected(original))
        assertEquals(replacement, controller.state.value.selectedRecording)
        assertTrue(replacement.file.exists())
        controller.close()
    }

    @Test
    fun `duration limit finalizes active recording`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)
        controller.start(maxDurationMillis = 1_000L)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, backend.stopCount)
        assertTrue(controller.state.value.selectedRecording?.file?.isFile == true)
        controller.close()
    }

    @Test
    fun `repeated stop preserves one finalized recording`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)
        controller.start()

        val first = controller.stop()
        val second = controller.stop()

        assertEquals(first.file, second.file)
        assertEquals(1, backend.stopCount)
        assertEquals(first.file, controller.state.value.selectedRecording?.file)
        controller.close()
    }

    @Test
    fun `close deletes finalized unsent recording`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)
        controller.start()
        val finalized = controller.stop()
        assertTrue(finalized.file.exists())

        controller.close()

        assertFalse(finalized.file.exists())
        assertNull(controller.state.value.selectedRecording)
    }

    @Test
    fun `failed stop clears active recording and cancels backend`() = runTest {
        val backend = FakeRecorderBackend(context.cacheDir)
        val controller = createController(this, backend)
        val output = controller.start()
        backend.failNextStop = true

        val result = runCatching { controller.stop() }

        assertTrue(result.isFailure)
        assertNull(controller.state.value.activeRecordingFile)
        assertNull(controller.state.value.selectedRecording)
        assertFalse(output.exists())
        assertTrue(backend.cancelled)
        controller.close()
    }

    private fun createController(scope: TestScope, backend: FakeRecorderBackend): VoiceMessageRecorder =
        VoiceMessageRecorder(
            context = context,
            scope = scope,
            recorderFactory = { backend },
        )
}

private class FakeRecorderBackend(
    private val cacheDir: File,
    override val isSupported: Boolean = true,
) : VoiceRecorderBackend {
    override val state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    var cancelled = false
    var stopCount = 0
    var failNextStart = false
    var failNextStop = false
    var lastStartOutput: File? = null
    private var outputFile: File? = null

    override fun start(outputFile: File) {
        lastStartOutput = outputFile
        if (failNextStart) {
            failNextStart = false
            outputFile.parentFile?.mkdirs()
            outputFile.writeText("partial")
            error("simulated start failure")
        }
        this.outputFile = outputFile
        state.value = RecorderState.Recording(0L)
    }

    override fun stop(): RecordedAudio {
        stopCount += 1
        if (failNextStop) {
            failNextStop = false
            error("simulated stop failure")
        }
        val file = outputFile ?: File.createTempFile("voice_test", ".ogg", cacheDir)
        file.parentFile?.mkdirs()
        file.writeBytes("OggS".encodeToByteArray())
        val recording = RecordedAudio(file, 1_000L, file.length())
        state.value = RecorderState.Completed(recording)
        return recording
    }

    override fun cancel() {
        cancelled = true
        state.value = RecorderState.Idle
    }

    override fun close() = cancel()
}
