package tech.torlando.lxst.audio

import android.util.Log
import tech.torlando.lxst.bridge.NetworkPacketBridge
import tech.torlando.lxst.codec.Codec
import tech.torlando.lxst.codec.Codec2
import tech.torlando.lxst.codec.Null
import tech.torlando.lxst.codec.Opus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Packetizer - RemoteSink that sends encoded frames to Python Reticulum.
 *
 * Matches Python LXST Network.py Packetizer (lines 49-89). Receives float32
 * frames from Pipeline/Mixer, encodes them using the assigned codec, prepends
 * a codec header byte, and sends via NetworkPacketBridge.
 *
 * **Wire Format:**
 * Each packet is: [codec_header_byte (1 byte)] + [encoded_frame (N bytes)]
 *
 * Codec header bytes (match Python LXST Codecs/__init__.py):
 * - 0xFF = Null codec (passthrough)
 * - 0x00 = Raw codec
 * - 0x01 = Opus codec
 * - 0x02 = Codec2 codec
 *
 * **Threading:**
 * - handleFrame is called from audio thread (Pipeline/Mixer)
 * - NetworkPacketBridge.sendPacket uses Dispatchers.IO (non-blocking)
 *
 * **CRITICAL:** No Log.d() in handleFrame - this is the audio hot path.
 *
 * @param bridge NetworkPacketBridge for sending packets to Python
 * @param failureCallback Optional callback invoked on transmission failure
 */
class Packetizer(
    private val bridge: NetworkPacketBridge,
    private val failureCallback: (() -> Unit)? = null
) : RemoteSink() {

    companion object {
        private const val TAG = "Columba:Packetizer"

        // Codec header bytes (match Python LXST Codecs/__init__.py)
        const val CODEC_NULL: Byte = 0xFF.toByte()
        const val CODEC_RAW: Byte = 0x00.toByte()
        const val CODEC_OPUS: Byte = 0x01.toByte()
        const val CODEC_CODEC2: Byte = 0x02.toByte()
    }

    private val shouldRun = AtomicBoolean(false)

    // TEMP: Diagnostic counters
    private var debugFrameCount = 0
    private var debugEncodeErrors = 0
    private var debugSendCount = 0

    /**
     * Codec to use for encoding frames.
     *
     * Set by Pipeline or caller before starting. Required for encoding.
     */
    var codec: Codec? = null

    /**
     * Tracks transmission failure state.
     *
     * Set to true if sendPacket throws an exception.
     * Caller can check this to detect link failures.
     */
    @Volatile
    var transmitFailure: Boolean = false

    /**
     * Get the codec header byte for a given codec.
     *
     * Matches Python LXST Codecs/__init__.py codec_header_byte function.
     *
     * @param codec The codec instance
     * @return Header byte identifying the codec type
     */
    private fun codecHeaderByte(codec: Codec?): Byte {
        return when (codec) {
            is Null -> CODEC_NULL
            is Opus -> CODEC_OPUS
            is Codec2 -> CODEC_CODEC2
            else -> CODEC_RAW
        }
    }

    /**
     * Check if this sink can receive frames.
     *
     * Always returns true when running - Python/network handles backpressure.
     * Unlike local sinks (LineSink), network sinks don't apply local backpressure;
     * packet loss is acceptable for real-time audio.
     *
     * @param fromSource Optional source reference (ignored)
     * @return true if packetizer is running, false otherwise
     */
    override fun canReceive(fromSource: Source?): Boolean {
        return shouldRun.get()
    }

    /**
     * Handle an incoming audio frame.
     *
     * Encodes the float32 frame using the configured codec, prepends the codec
     * header byte, and sends via NetworkPacketBridge.
     *
     * Matches Python LXST Network.py Packetizer.handle_frame (lines 57-67).
     *
     * **CRITICAL:** No Log.d() calls - this is the audio hot path.
     *
     * @param frame Float32 audio samples in range [-1.0, 1.0]
     * @param source Optional source reference (ignored, codec comes from property)
     */
    override fun handleFrame(frame: FloatArray, source: Source?) {
        if (!shouldRun.get()) return

        // Get codec from property
        val activeCodec = codec ?: run {
            if (debugFrameCount++ < 5) Log.w(TAG, "TX#$debugFrameCount: no codec set!")
            return
        }

        debugFrameCount++

        // TEMP: Log first 5 frames for diagnostics
        if (debugFrameCount <= 5) {
            val maxAmp = frame.maxOrNull() ?: 0f
            Log.w(TAG, "TX#$debugFrameCount: frame=${frame.size} samples, maxAmp=$maxAmp, codec=$activeCodec")
        }

        // Encode frame to bytes
        val encodedFrame = try {
            activeCodec.encode(frame)
        } catch (e: Exception) {
            debugEncodeErrors++
            // TEMP: Log first 5 encode errors
            if (debugEncodeErrors <= 5) {
                Log.e(TAG, "TX encode error #$debugEncodeErrors: ${e.message}, frame=${frame.size} samples")
            }
            return
        }

        // Prepend codec header byte (match Python LXST format)
        val packet = ByteArray(1 + encodedFrame.size)
        packet[0] = codecHeaderByte(activeCodec)
        encodedFrame.copyInto(packet, destinationOffset = 1)

        // Send to Python via bridge (non-blocking)
        try {
            bridge.sendPacket(packet)
            debugSendCount++
            // TEMP: Log every 50th send
            if (debugSendCount <= 5 || debugSendCount % 50 == 0) {
                Log.w(TAG, "TX sent #$debugSendCount: ${packet.size} bytes, hdr=0x${(packet[0].toInt() and 0xFF).toString(16)}")
            }
        } catch (e: Exception) {
            // Mark failure state and invoke callback
            Log.e(TAG, "TX send error: ${e.message}")
            transmitFailure = true
            failureCallback?.invoke()
        }
    }

    /**
     * Start the packetizer.
     *
     * After calling start(), handleFrame will accept and send frames.
     */
    override fun start() {
        shouldRun.set(true)
    }

    /**
     * Stop the packetizer.
     *
     * After calling stop(), handleFrame will silently ignore frames.
     */
    override fun stop() {
        shouldRun.set(false)
    }

    /**
     * Check if packetizer is currently running.
     *
     * @return true if started and not stopped
     */
    override fun isRunning(): Boolean = shouldRun.get()
}
