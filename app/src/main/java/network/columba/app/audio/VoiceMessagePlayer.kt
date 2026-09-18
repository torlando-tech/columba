package network.columba.app.audio

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import network.columba.app.ui.model.AudioAttachmentLoader
import network.columba.app.ui.model.AudioAttachmentUi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class VoiceMessagePlayerState(
    val loading: Boolean = false,
    val playing: Boolean = false,
    val progressMs: Int = 0,
    val durationMs: Int = 0,
    val error: String? = null,
    val messageKey: String? = null,
)

data class VoiceMessageMetadata(
    val durationMs: Int,
    val waveformLevels: List<Float>,
)

internal interface VoicePlaybackEngine {
    val durationMs: Int
    val currentPositionMs: Int
    val isPlaying: Boolean

    fun setDataSource(file: File)
    fun setOnPreparedListener(listener: (VoicePlaybackEngine) -> Unit)
    fun setOnCompletionListener(listener: (VoicePlaybackEngine) -> Unit)
    fun setOnErrorListener(listener: () -> Unit)
    fun prepareAsync()
    fun start()
    fun pause()
    fun seekTo(positionMs: Int)
    fun release()
}

internal fun interface PlaybackEngineFactory {
    fun create(): VoicePlaybackEngine
}

private class AndroidVoicePlaybackEngine(
    private val player: MediaPlayer = MediaPlayer(),
) : VoicePlaybackEngine {
    override val durationMs: Int get() = player.duration.coerceAtLeast(0)
    override val currentPositionMs: Int get() = player.currentPosition.coerceAtLeast(0)
    override val isPlaying: Boolean get() = player.isPlaying

    override fun setDataSource(file: File) = player.setDataSource(file.absolutePath)

    override fun setOnPreparedListener(listener: (VoicePlaybackEngine) -> Unit) {
        player.setOnPreparedListener { listener(this) }
    }

    override fun setOnCompletionListener(listener: (VoicePlaybackEngine) -> Unit) {
        player.setOnCompletionListener { listener(this) }
    }

    override fun setOnErrorListener(listener: () -> Unit) {
        player.setOnErrorListener { _, _, _ ->
            listener()
            true
        }
    }

    override fun prepareAsync() = player.prepareAsync()
    override fun start() = player.start()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Int) = player.seekTo(positionMs)
    override fun release() = player.release()
}

internal class VoiceMessagePlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val loadBytes: suspend (AudioAttachmentUi) -> ByteArray? =
        AudioAttachmentLoader(context.applicationContext)::loadBytes,
    private val playerFactory: PlaybackEngineFactory = PlaybackEngineFactory { AndroidVoicePlaybackEngine() },
    private val waveformReader: AudioWaveformReader = AndroidPcmWaveformReader(context.applicationContext),
    private val codec2Codec: Codec2RawAudioCodec = Codec2RawAudioCodec(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(VoiceMessagePlayerState())
    val state: StateFlow<VoiceMessagePlayerState> = _state.asStateFlow()
    private val _metadata = MutableStateFlow<Map<String, VoiceMessageMetadata>>(emptyMap())
    val metadata: StateFlow<Map<String, VoiceMessageMetadata>> = _metadata.asStateFlow()

    private var player: VoicePlaybackEngine? = null
    private var tempFile: File? = null
    private var loadJob: Job? = null
    private var progressJob: Job? = null
    private val metadataJobs = ConcurrentHashMap<String, Job>()
    private val metadataSemaphore = Semaphore(permits = 1)

    fun prepareMetadata(messageKey: String, attachment: AudioAttachmentUi) {
        if (_metadata.value.containsKey(messageKey)) return
        val candidate =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result =
                        metadataSemaphore.withPermit {
                            withContext(ioDispatcher) {
                                loadBytes(attachment)?.let { analyzeMetadata(it, attachment) }
                            }
                        } ?: return@launch
                    cacheMetadata(messageKey, result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Waveform metadata is decorative. Playback remains available and
                    // the UI renders its neutral fallback if decoding fails.
                } finally {
                    currentCoroutineContext()[Job]?.let { completed ->
                        metadataJobs.remove(messageKey, completed)
                    }
                }
            }
        val existing = metadataJobs.putIfAbsent(messageKey, candidate)
        if (existing == null) {
            candidate.start()
        } else {
            candidate.cancel()
        }
    }

    fun cancelMetadata(messageKey: String) {
        metadataJobs.remove(messageKey)?.cancel()
    }

    fun toggle(messageKey: String, attachment: AudioAttachmentUi) {
        val current = _state.value
        if (current.messageKey != messageKey || player == null) {
            play(messageKey, attachment)
            return
        }
        if (current.playing) {
            pause()
        } else {
            resume()
        }
    }

    fun toggleFile(
        messageKey: String,
        file: File,
        format: VoiceMessageFormat = VoiceMessageFormat.DEFAULT,
    ) {
        val current = _state.value
        if (current.messageKey != messageKey || player == null) {
            if (format.codec2Mode == null) {
                releaseActive(clearState = false)
                _state.value = VoiceMessagePlayerState(loading = true, messageKey = messageKey)
                loadJob = scope.launch(ioDispatcher) { playFile(messageKey, file, releaseFirst = false) }
            } else {
                playCodec2File(messageKey, file, format.codec2Mode)
            }
            return
        }
        if (current.playing) pause() else resume()
    }

    private fun playCodec2File(
        messageKey: String,
        source: File,
        mode: Int,
    ) {
        releaseActive(clearState = false)
        _state.value = VoiceMessagePlayerState(loading = true, messageKey = messageKey)
        loadJob =
            scope.launch {
                var unownedFile: File? = null
                try {
                    val wave =
                        withContext(ioDispatcher) {
                            val bytes = source.readBytes()
                            File.createTempFile("voice_preview_", ".wav", appContext.cacheDir).also {
                                unownedFile = it
                                codec2Codec.writeWave(codec2Codec.decode(bytes, mode), it)
                            }
                        }
                    currentCoroutineContext().ensureActive()
                    withContext(ioDispatcher) {
                        playFile(messageKey, wave, ownsFile = true, releaseFirst = false)
                    }
                    unownedFile = null
                } catch (error: Exception) {
                    if (currentCoroutineContext().isActive) failPlayback(messageKey, error.message ?: "error")
                } finally {
                    withContext(NonCancellable + ioDispatcher) { unownedFile?.delete() }
                }
            }
    }

    fun pause() {
        val active = player ?: return
        if (active.isPlaying) active.pause()
        progressJob?.cancel()
        progressJob = null
        _state.value =
            _state.value.copy(
                playing = false,
                progressMs = active.currentPositionMs.coerceAtMost(_state.value.durationMs),
            )
    }

    @Suppress("TooGenericExceptionCaught") // Release a newly created player before rethrowing any setup failure.
    fun play(messageKey: String, attachment: AudioAttachmentUi) {
        releaseActive(clearState = false)
        _state.value = VoiceMessagePlayerState(loading = true, messageKey = messageKey)
        loadJob =
            scope.launch {
                var unownedFile: File? = null
                try {
                    val loadedBytes = withContext(ioDispatcher) { loadBytes(attachment) }
                    if (loadedBytes == null || loadedBytes.isEmpty()) {
                        _state.value = VoiceMessagePlayerState(error = "unavailable", messageKey = messageKey)
                        return@launch
                    }
                    val bytes = loadedBytes
                    val isCodec2 = attachment.mode.isCodec2
                    val file =
                        withContext(ioDispatcher) {
                            File.createTempFile("voice_message_", if (isCodec2) ".wav" else ".ogg", appContext.cacheDir).also {
                                unownedFile = it
                                if (isCodec2) {
                                    val mode = checkNotNull(attachment.mode.codec2Bitrate())
                                    codec2Codec.writeWave(codec2Codec.decode(bytes, mode), it)
                                } else {
                                    it.writeBytes(bytes)
                                }
                            }
                        }
                    currentCoroutineContext().ensureActive()
                    tempFile = file
                    unownedFile = null

                    val engine = playerFactory.create()
                    try {
                        withContext(ioDispatcher) { engine.setDataSource(file) }
                        currentCoroutineContext().ensureActive()
                    } catch (error: Throwable) {
                        engine.release()
                        throw error
                    }
                    player = engine
                    engine.setOnPreparedListener { prepared ->
                        if (player !== prepared) return@setOnPreparedListener
                        prepared.start()
                        _state.value =
                            VoiceMessagePlayerState(
                                playing = true,
                                durationMs = prepared.durationMs,
                                messageKey = messageKey,
                            )
                        startProgressUpdates(prepared)
                    }
                    engine.setOnCompletionListener { completed ->
                        if (player !== completed) return@setOnCompletionListener
                        progressJob?.cancel()
                        progressJob = null
                        _state.value =
                            _state.value.copy(
                                playing = false,
                                progressMs = completed.durationMs,
                                durationMs = completed.durationMs,
                            )
                    }
                    engine.setOnErrorListener {
                        if (player === engine) failPlayback(messageKey, "error")
                    }
                    engine.prepareAsync()
                } catch (error: Exception) {
                    if (currentCoroutineContext().isActive) {
                        failPlayback(messageKey, error.message ?: "error")
                    }
                } finally {
                    withContext(NonCancellable + ioDispatcher) { unownedFile?.delete() }
                }
            }
    }

    private fun playFile(
        messageKey: String,
        file: File,
        ownsFile: Boolean = false,
        releaseFirst: Boolean = true,
    ) {
        if (releaseFirst) releaseActive(clearState = false)
        if (!file.isFile || file.length() <= 0L) {
            _state.value = VoiceMessagePlayerState(error = "unavailable", messageKey = messageKey)
            return
        }
        _state.value = VoiceMessagePlayerState(loading = true, messageKey = messageKey)
        try {
            if (ownsFile) tempFile = file
            val engine = playerFactory.create()
            player = engine
            engine.setDataSource(file)
            engine.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                prepared.start()
                _state.value =
                    VoiceMessagePlayerState(
                        playing = true,
                        durationMs = prepared.durationMs,
                        messageKey = messageKey,
                    )
                startProgressUpdates(prepared)
            }
            engine.setOnCompletionListener { completed ->
                if (player !== completed) return@setOnCompletionListener
                progressJob?.cancel()
                progressJob = null
                _state.value =
                    _state.value.copy(
                        playing = false,
                        progressMs = completed.durationMs,
                        durationMs = completed.durationMs,
                    )
            }
            engine.setOnErrorListener {
                if (player === engine) failPlayback(messageKey, "error")
            }
            engine.prepareAsync()
        } catch (error: Exception) {
            failPlayback(messageKey, error.message ?: "error")
        }
    }

    override fun close() {
        metadataJobs.values.toList().forEach(Job::cancel)
        metadataJobs.clear()
        _metadata.value = emptyMap()
        releaseActive(clearState = true)
    }

    private fun cacheMetadata(messageKey: String, result: VoiceMessageMetadata) {
        _metadata.update { current ->
            LinkedHashMap(current).apply {
                this[messageKey] = result
                while (size > MAX_METADATA_ENTRIES) remove(keys.first())
            }
        }
    }

    private suspend fun analyzeMetadata(
        bytes: ByteArray,
        attachment: AudioAttachmentUi,
    ): VoiceMessageMetadata? {
        val result =
            if (attachment.mode.isCodec2) {
                attachment.mode.codec2Bitrate()?.let { mode ->
                    val decoded = codec2Codec.decode(bytes, mode)
                    VoiceMessageMetadata(decoded.durationMillis, codec2Waveform(decoded.samples))
                }
            } else {
                OggOpusMetadataReader.read(bytes)?.let { container ->
                    val waveform = waveformReader.read(bytes, container.durationMs).orEmpty()
                    VoiceMessageMetadata(container.durationMs, waveform)
                }
            }
        return result
    }

    private fun codec2Waveform(samples: ShortArray): List<Float> {
        if (samples.isEmpty()) return emptyList()
        return List(WAVEFORM_BARS) { index ->
            val start = index * samples.size / WAVEFORM_BARS
            val end = ((index + 1) * samples.size / WAVEFORM_BARS).coerceAtLeast(start + 1).coerceAtMost(samples.size)
            if (start >= samples.size) {
                0f
            } else {
                var peak = 0
                for (sampleIndex in start until end) peak = maxOf(peak, kotlin.math.abs(samples[sampleIndex].toInt()))
                (peak / 32_768f).coerceIn(0f, 1f)
            }
        }
    }

    private fun resume() {
        val active = player ?: return
        val current = _state.value
        if (current.durationMs > 0 && current.progressMs >= current.durationMs) {
            active.seekTo(0)
            _state.value = current.copy(progressMs = 0)
        }
        active.start()
        _state.value = _state.value.copy(playing = true, error = null)
        startProgressUpdates(active)
    }

    private fun startProgressUpdates(active: VoicePlaybackEngine) {
        progressJob?.cancel()
        progressJob =
            scope.launch {
                while (isActive && player === active && active.isPlaying) {
                    delay(PROGRESS_INTERVAL_MILLIS)
                    if (player !== active) return@launch
                    _state.value =
                        _state.value.copy(
                            progressMs = active.currentPositionMs.coerceAtMost(active.durationMs),
                            durationMs = active.durationMs,
                        )
                }
            }
    }

    private fun failPlayback(messageKey: String, reason: String) {
        releaseActive(clearState = false)
        _state.value = VoiceMessagePlayerState(error = reason, messageKey = messageKey)
    }

    private fun releaseActive(clearState: Boolean) {
        loadJob?.cancel()
        loadJob = null
        progressJob?.cancel()
        progressJob = null
        player?.release()
        player = null
        tempFile?.delete()
        tempFile = null
        if (clearState) _state.value = VoiceMessagePlayerState()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 250L
        const val MAX_METADATA_ENTRIES = 128
        const val WAVEFORM_BARS = 40
    }
}
