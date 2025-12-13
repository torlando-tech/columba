package com.lxmf.messenger.test

import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.ui.model.MessageUi

/**
 * Test fixtures for MessagingScreen UI tests.
 */
object MessagingTestFixtures {

    object Constants {
        const val TEST_DESTINATION_HASH = "1234567890abcdef1234567890abcdef"
        const val TEST_PEER_NAME = "Test Peer"
        const val TEST_MESSAGE_ID = "msg_001"
        const val TEST_MESSAGE_CONTENT = "Hello, this is a test message"
    }

    // ========== MessageUi Fixtures ==========

    fun createSentMessage(
        id: String = Constants.TEST_MESSAGE_ID,
        destinationHash: String = Constants.TEST_DESTINATION_HASH,
        content: String = Constants.TEST_MESSAGE_CONTENT,
        timestamp: Long = System.currentTimeMillis(),
        status: String = "sent",
        deliveryMethod: String? = "direct",
        errorMessage: String? = null,
    ) = MessageUi(
        id = id,
        destinationHash = destinationHash,
        content = content,
        timestamp = timestamp,
        isFromMe = true,
        status = status,
        decodedImage = null,
        deliveryMethod = deliveryMethod,
        errorMessage = errorMessage,
    )

    fun createReceivedMessage(
        id: String = "msg_received_001",
        destinationHash: String = Constants.TEST_DESTINATION_HASH,
        content: String = "This is a received message",
        timestamp: Long = System.currentTimeMillis(),
    ) = MessageUi(
        id = id,
        destinationHash = destinationHash,
        content = content,
        timestamp = timestamp,
        isFromMe = false,
        status = "delivered",
        decodedImage = null,
        deliveryMethod = null,
        errorMessage = null,
    )

    fun createPendingMessage(
        id: String = "msg_pending_001",
        content: String = "Pending message",
    ) = createSentMessage(
        id = id,
        content = content,
        status = "pending",
    )

    fun createDeliveredMessage(
        id: String = "msg_delivered_001",
        content: String = "Delivered message",
    ) = createSentMessage(
        id = id,
        content = content,
        status = "delivered",
    )

    fun createFailedMessage(
        id: String = "msg_failed_001",
        content: String = "Failed message",
        errorMessage: String = "Network error",
    ) = createSentMessage(
        id = id,
        content = content,
        status = "failed",
        errorMessage = errorMessage,
    )

    fun createRetryingMessage(
        id: String = "msg_retrying_001",
        content: String = "Retrying message",
    ) = createSentMessage(
        id = id,
        content = content,
        status = "retrying_propagated",
    )

    fun createMultipleMessages(count: Int = 5): List<MessageUi> =
        (1..count).map { index ->
            if (index % 2 == 0) {
                createSentMessage(
                    id = "msg_$index",
                    content = "Sent message $index",
                    timestamp = System.currentTimeMillis() - (count - index) * 60_000L,
                )
            } else {
                createReceivedMessage(
                    id = "msg_$index",
                    content = "Received message $index",
                    timestamp = System.currentTimeMillis() - (count - index) * 60_000L,
                )
            }
        }

    // ========== Announce Fixtures ==========

    fun createOnlineAnnounce(
        destinationHash: String = Constants.TEST_DESTINATION_HASH,
        peerName: String = Constants.TEST_PEER_NAME,
    ) = Announce(
        destinationHash = destinationHash,
        peerName = peerName,
        publicKey = ByteArray(64) { it.toByte() },
        appData = null,
        hops = 1,
        lastSeenTimestamp = System.currentTimeMillis() - 60_000L, // 1 minute ago (online)
        nodeType = "node",
        receivingInterface = "ble",
        aspect = "lxmf.delivery",
        isFavorite = false,
        favoritedTimestamp = null,
        stampCost = null,
        stampCostFlexibility = null,
        peeringCost = null,
    )

    fun createOfflineAnnounce(
        destinationHash: String = Constants.TEST_DESTINATION_HASH,
        peerName: String = Constants.TEST_PEER_NAME,
    ) = Announce(
        destinationHash = destinationHash,
        peerName = peerName,
        publicKey = ByteArray(64) { it.toByte() },
        appData = null,
        hops = 3,
        lastSeenTimestamp = System.currentTimeMillis() - 10 * 60_000L, // 10 minutes ago (offline)
        nodeType = "node",
        receivingInterface = "tcp",
        aspect = "lxmf.delivery",
        isFavorite = false,
        favoritedTimestamp = null,
        stampCost = null,
        stampCostFlexibility = null,
        peeringCost = null,
    )

    // ========== Image Data Fixtures ==========

    /**
     * Creates a minimal valid PNG image as ByteArray for testing.
     * This is a 1x1 pixel transparent PNG.
     */
    fun createTestImageData(): ByteArray {
        // Minimal PNG header + IHDR + IDAT + IEND chunks for a 1x1 transparent pixel
        return byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A, // PNG signature
            0x00,
            0x00,
            0x00,
            0x0D, // IHDR length
            0x49,
            0x48,
            0x44,
            0x52, // "IHDR"
            0x00,
            0x00,
            0x00,
            0x01, // width: 1
            0x00,
            0x00,
            0x00,
            0x01, // height: 1
            0x08,
            0x06, // bit depth: 8, color type: 6 (RGBA)
            0x00,
            0x00,
            0x00, // compression, filter, interlace
            0x1F.toByte(),
            0x15,
            0xC4.toByte(),
            0x89.toByte(), // CRC
            0x00,
            0x00,
            0x00,
            0x0A, // IDAT length
            0x49,
            0x44,
            0x41,
            0x54, // "IDAT"
            0x78,
            0x9C.toByte(),
            0x63,
            0x00,
            0x01,
            0x00,
            0x00,
            0x05,
            0x00,
            0x01, // compressed data
            0x0D,
            0x0A,
            0x2D,
            0xB4.toByte(), // CRC
            0x00,
            0x00,
            0x00,
            0x00, // IEND length
            0x49,
            0x45,
            0x4E,
            0x44, // "IEND"
            0xAE.toByte(),
            0x42,
            0x60,
            0x82.toByte(), // CRC
        )
    }
}
