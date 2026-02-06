package tech.torlando.lxst.bridge

/**
 * Abstraction for sending audio packets and signals to the network layer.
 *
 * Replaces direct PyObject dependency in NetworkPacketBridge so `:lxst` module
 * has no Chaquopy coupling. The implementation wrapping Chaquopy lives in `:app`.
 *
 * Methods are called on [kotlinx.coroutines.Dispatchers.IO] — implementations
 * must be safe for off-main-thread invocation.
 */
interface AudioPacketHandler {
    /**
     * Send encoded audio packet to the remote peer via the network layer.
     *
     * @param packet Encoded audio data (codec header byte + encoded frame)
     */
    fun receiveAudioPacket(packet: ByteArray)

    /**
     * Send signalling value to the remote peer via the network layer.
     *
     * @param signal Signalling code (STATUS_BUSY, STATUS_RINGING, etc.)
     */
    fun receiveSignal(signal: Int)
}
