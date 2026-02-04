package com.lxmf.messenger.reticulum.audio.lxst

import com.lxmf.messenger.reticulum.audio.bridge.NetworkPacketBridge
import com.lxmf.messenger.reticulum.audio.codec.Codec
import com.lxmf.messenger.reticulum.audio.codec.Codec2
import com.lxmf.messenger.reticulum.audio.codec.Null
import com.lxmf.messenger.reticulum.audio.codec.Opus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RemoteSource that receives encoded audio frames from Python Reticulum.
 *
 * Matches Python LXST Network.py LinkSource (lines 98-145).
 * Receives packets via [NetworkPacketBridge] callback, decodes them,
 * and pushes decoded float32 frames to downstream [Sink].
 *
 * **Threading Model:**
 * - [onPacketReceived]: Called by Python via bridge callback (GIL held) - MUST BE FAST
 *   Just queues packet, no processing, no logging
 * - [processingLoop]: Runs on [Dispatchers.IO] coroutine, does actual decode + sink push
 *
 * **Codec Header Protocol:**
 * First byte of each packet indicates codec type:
 * - 0xFF = Null (raw int16 PCM)
 * - 0x00 = Raw (alias for Null in Python, but we map to Null for simplicity)
 * - 0x01 = Opus
 * - 0x02 = Codec2
 *
 * Dynamic codec switching is supported - remote can change codec mid-call.
 */
class LinkSource(
    private val bridge: NetworkPacketBridge,
    var sink: Sink? = null
) : RemoteSource() {

    companion object {
        /** Maximum packets in queue before dropping oldest (backpressure) */
        const val MAX_PACKETS = 8

        // Codec header bytes (must match Packetizer and Python LXST Codecs/__init__.py)
        const val CODEC_NULL: Byte = 0xFF.toByte()
        const val CODEC_RAW: Byte = 0x00
        const val CODEC_OPUS: Byte = 0x01
        const val CODEC_CODEC2: Byte = 0x02
    }

    // RemoteSource properties
    override var sampleRate: Int = 48000
    override var channels: Int = 1

    // State
    private val shouldRun = AtomicBoolean(false)
    private var codec: Codec = Null()
    private val packetQueue = ArrayDeque<ByteArray>(MAX_PACKETS)
    private val receiveLock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        bridge.setPacketCallback { packetData ->
            onPacketReceived(packetData)
        }
    }

    /**
     * Called by Python via [NetworkPacketBridge] when packet arrives.
     *
     * **CRITICAL:** Must be fast - Python GIL is held during this call.
     * Just queues the packet for processing. No decode, no logging.
     *
     * Implements backpressure by dropping oldest packet when queue is full.
     *
     * @param packetData Raw packet data (codec header byte + encoded frame)
     */
    fun onPacketReceived(packetData: ByteArray) {
        if (!shouldRun.get()) return

        synchronized(receiveLock) {
            // Drop oldest if full (backpressure)
            if (packetQueue.size >= MAX_PACKETS) {
                packetQueue.removeFirst()
            }
            packetQueue.addLast(packetData)
        }
    }

    /**
     * Process a single packet: parse header, decode, push to sink.
     *
     * @param data Packet data (codec header byte + encoded frame)
     */
    private fun processPacket(data: ByteArray) {
        if (data.isEmpty()) return
        val currentSink = sink ?: return

        // Parse codec header byte (first byte)
        val codecType = data[0]
        val frameData = data.copyOfRange(1, data.size)

        // Switch codec if needed (match Python LXST dynamic switching)
        val newCodec = getCodecForHeader(codecType)
        if (newCodec::class != codec::class) {
            codec = newCodec
        }

        // Decode frame
        val decodedFrame = codec.decode(frameData)

        // Push to sink (Mixer)
        currentSink.handleFrame(decodedFrame, this)
    }

    /**
     * Get codec instance for header byte.
     *
     * Creates new codec instance for the specified type.
     * Note: Codec2 mode header is embedded in its encoded data,
     * so Codec2 handles mode switching internally.
     *
     * @param header Codec type byte
     * @return Codec instance for decoding
     */
    private fun getCodecForHeader(header: Byte): Codec {
        return when (header) {
            CODEC_NULL -> Null()
            CODEC_RAW -> Null()  // RAW maps to Null (raw int16 PCM)
            CODEC_OPUS -> Opus()
            CODEC_CODEC2 -> Codec2()
            else -> Null()  // Fall back to Null for unknown
        }
    }

    /**
     * Main processing loop running on [Dispatchers.IO].
     *
     * Continuously dequeues packets and processes them.
     * Brief delay when queue is empty to avoid busy-spinning.
     */
    private suspend fun processingLoop() {
        while (shouldRun.get()) {
            val packet: ByteArray?
            synchronized(receiveLock) {
                packet = packetQueue.removeFirstOrNull()
            }
            if (packet != null) {
                processPacket(packet)
            } else {
                delay(2)  // Brief sleep when queue empty
            }
        }
    }

    /**
     * Start receiving and processing packets.
     *
     * Launches processing coroutine on [Dispatchers.IO].
     */
    override fun start() {
        if (shouldRun.getAndSet(true)) return
        scope.launch { processingLoop() }
    }

    /**
     * Stop receiving and processing packets.
     *
     * Clears packet queue to prevent stale data on restart.
     */
    override fun stop() {
        shouldRun.set(false)
        synchronized(receiveLock) {
            packetQueue.clear()
        }
    }

    /**
     * Check if source is currently running.
     */
    override fun isRunning(): Boolean = shouldRun.get()

    /**
     * Shutdown and release resources.
     *
     * Cancels coroutine scope. Call when LinkSource is no longer needed.
     */
    fun shutdown() {
        stop()
        scope.cancel()
    }
}
