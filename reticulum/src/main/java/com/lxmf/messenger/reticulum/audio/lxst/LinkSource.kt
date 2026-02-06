package com.lxmf.messenger.reticulum.audio.lxst

import android.util.Log
import com.lxmf.messenger.reticulum.audio.bridge.NetworkPacketBridge
import com.lxmf.messenger.reticulum.audio.codec.Codec
import com.lxmf.messenger.reticulum.audio.codec.Null
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
        private const val TAG = "Columba:LinkSource"

        /** Maximum packets in queue before dropping oldest (backpressure) */
        const val MAX_PACKETS = 8
    }

    // RemoteSource properties
    override var sampleRate: Int = 48000
    override var channels: Int = 1

    // State
    private val shouldRun = AtomicBoolean(false)
    private var debugPacketCount = 0 // TEMP: diagnostic counter

    /**
     * Codec for decoding received frames.
     *
     * Set by Telephone based on the active call profile. This ensures the decoder
     * uses the correct sample rate and channel configuration to match the remote
     * encoder. Without this, the decoder defaults to 8000 Hz and can't decode
     * frames encoded at 24000 Hz or 48000 Hz.
     */
    @Volatile
    var codec: Codec = Null()
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
     * Process a single packet: strip header, decode, push to sink.
     *
     * The codec is pre-configured by Telephone based on the negotiated profile,
     * ensuring the decoder sample rate matches the encoder. The per-packet codec
     * header byte (first byte) is stripped but not used for codec selection —
     * both sides agree on the codec during signalling.
     *
     * Decode errors are caught and the frame is dropped. This prevents a single
     * corrupted packet from crashing the entire service process.
     *
     * @param data Packet data (codec header byte + encoded frame)
     */
    private fun processPacket(data: ByteArray) {
        if (data.size < 2) return  // Need header + at least 1 byte of frame
        val currentSink = sink ?: return

        // TEMP: Log first 5 packets for diagnostics
        debugPacketCount++
        if (debugPacketCount <= 5) {
            val header = data[0].toInt() and 0xFF
            val preview = data.take(8).joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16).padStart(2, '0')}" }
            Log.w(TAG, "PKT#$debugPacketCount: size=${data.size} hdr=0x${header.toString(16).padStart(2, '0')} preview=[$preview] codec=$codec")
        }

        // Strip codec header byte (first byte), remaining is encoded frame
        val frameData = data.copyOfRange(1, data.size)

        // Decode frame using Telephone-configured codec
        try {
            val decodedFrame = codec.decode(frameData)
            // TEMP: Log decode result for first 5 packets
            if (debugPacketCount <= 5) {
                val maxAmp = decodedFrame.maxOrNull() ?: 0f
                val minAmp = decodedFrame.minOrNull() ?: 0f
                Log.w(TAG, "DEC#$debugPacketCount: samples=${decodedFrame.size} range=[$minAmp,$maxAmp]")
            }
            currentSink.handleFrame(decodedFrame, this)
        } catch (e: Exception) {
            // Drop frame on decode error — don't crash the service
            Log.w(TAG, "Decode error, dropping frame: ${e.message}")
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
