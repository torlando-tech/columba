package com.lxmf.messenger.test

import com.lxmf.messenger.ui.model.MessageUi

/**
 * Test fixtures for MessageDetailScreen UI tests.
 */
object MessageDetailTestFixtures {
    const val TEST_MESSAGE_ID = "test-message-id-12345"
    const val TEST_DESTINATION_HASH = "abcdef0123456789"
    const val TEST_TIMESTAMP = 1702400000000L // Fixed timestamp for predictable formatting

    /**
     * Creates a test MessageUi with customizable parameters.
     */
    fun createMessageUi(
        id: String = TEST_MESSAGE_ID,
        destinationHash: String = TEST_DESTINATION_HASH,
        content: String = "Test message content",
        timestamp: Long = TEST_TIMESTAMP,
        isFromMe: Boolean = true,
        status: String = "sent",
        deliveryMethod: String? = null,
        errorMessage: String? = null,
    ): MessageUi =
        MessageUi(
            id = id,
            destinationHash = destinationHash,
            content = content,
            timestamp = timestamp,
            isFromMe = isFromMe,
            status = status,
            decodedImage = null,
            deliveryMethod = deliveryMethod,
            errorMessage = errorMessage,
        )

    fun deliveredMessage() =
        createMessageUi(
            status = "delivered",
            deliveryMethod = "direct",
        )

    fun failedMessage(errorMessage: String = "Connection timeout") =
        createMessageUi(
            status = "failed",
            deliveryMethod = "direct",
            errorMessage = errorMessage,
        )

    fun pendingMessage() =
        createMessageUi(
            status = "pending",
            deliveryMethod = "opportunistic",
        )

    fun sentMessage() =
        createMessageUi(
            status = "sent",
            deliveryMethod = "direct",
        )

    fun opportunisticMessage() =
        createMessageUi(
            status = "delivered",
            deliveryMethod = "opportunistic",
        )

    fun directMessage() =
        createMessageUi(
            status = "delivered",
            deliveryMethod = "direct",
        )

    fun propagatedMessage() =
        createMessageUi(
            status = "delivered",
            deliveryMethod = "propagated",
        )

    fun messageWithNoDeliveryMethod() =
        createMessageUi(
            status = "delivered",
            deliveryMethod = null,
        )

    fun failedWithoutErrorMessage() =
        createMessageUi(
            status = "failed",
            deliveryMethod = "direct",
            errorMessage = null,
        )
}
