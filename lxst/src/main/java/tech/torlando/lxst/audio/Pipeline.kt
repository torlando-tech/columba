package tech.torlando.lxst.audio

import android.util.Log
import tech.torlando.lxst.codec.Codec

/**
 * Pipeline - Orchestrates audio component wiring and lifecycle.
 *
 * Matches Python LXST Pipeline.py structure. Pipeline is a thin wrapper
 * that wires source -> sink and provides unified start/stop.
 *
 * Pipeline delegates all work to source - it's pure coordination, no processing.
 *
 * @param source Audio source (LineSource, ToneSource, or Mixer)
 * @param codec Codec for encoding (optional, set on source if source supports it)
 * @param sink Audio sink (LineSink or Mixer)
 */
class Pipeline(
    val source: Source,
    codec: Codec,
    val sink: Sink
) {

    companion object {
        private const val TAG = "Columba:Pipeline"
    }

    private var _codec: Codec? = null

    /**
     * Codec property for pipeline.
     *
     * Setting codec wires it to the source if the source supports runtime codec changes.
     * Note: LineSource codec is immutable (set in constructor) - this matches Python behavior.
     */
    var codec: Codec
        get() = _codec ?: throw IllegalStateException("Codec not set")
        set(value) {
            if (_codec != value) {
                _codec = value
                // Set codec on source if source supports it
                wireCodecToSource(source, value)
            }
        }

    init {
        // Wire source to sink (source pushes frames to sink)
        wireSourceToSink(source, sink)

        // Set codec (triggers codec setter)
        this.codec = codec
    }

    /**
     * Wire source to sink based on source type.
     *
     * Different source types have different sink properties.
     */
    private fun wireSourceToSink(source: Source, sink: Sink) {
        when (source) {
            is LineSource -> source.sink = sink
            is Mixer -> source.sink = sink
            is ToneSource -> source.sink = sink
            else -> {
                // Unknown source type - log warning
                Log.w(TAG, "Unknown source type: ${source::class.simpleName}, cannot wire sink")
            }
        }
    }

    /**
     * Wire codec to source based on source type.
     *
     * LineSource codec is immutable (private val, set in constructor).
     * ToneSource and Mixer support runtime codec changes.
     */
    private fun wireCodecToSource(source: Source, codec: Codec) {
        when (source) {
            is LineSource -> {
                // LineSource codec is immutable (set in constructor)
                // No runtime codec change supported - this is by design
                // Python behavior: LineSource.codec is set at creation time
            }
            is ToneSource -> {
                // ToneSource doesn't currently use codec for encoding
                // (local playback path pushes float32 directly to sink)
                // Keep for future network transmit path
            }
            is Mixer -> source.codec = codec
            else -> Log.w(TAG, "Unknown source type: ${source::class.simpleName}, cannot set codec")
        }
    }

    /**
     * Check if pipeline is currently running.
     *
     * Reflects source running state.
     */
    val running: Boolean
        get() = source.isRunning()

    /**
     * Start the pipeline.
     *
     * Delegates to source.start().
     */
    fun start() {
        if (!running) {
            source.start()
        }
    }

    /**
     * Stop the pipeline.
     *
     * Delegates to source.stop().
     */
    fun stop() {
        if (running) {
            source.stop()
        }
    }
}
