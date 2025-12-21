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
     * Configuration for creating test MessageUi instances.
     */
    data class MessageConfig(
        val id: String = TEST_MESSAGE_ID,
        val destinationHash: String = TEST_DESTINATION_HASH,
        val content: String = "Test message content",
        val timestamp: Long = TEST_TIMESTAMP,
        val isFromMe: Boolean = true,
        val status: String = "sent",
        val deliveryMethod: String? = null,
        val errorMessage: String? = null,
    )

    /**
     * Creates a test MessageUi from a configuration.
     */
    fun createMessageUi(config: MessageConfig = MessageConfig()): MessageUi =
        MessageUi(
            id = config.id,
            destinationHash = config.destinationHash,
            content = config.content,
            timestamp = config.timestamp,
            isFromMe = config.isFromMe,
            status = config.status,
            decodedImage = null,
            deliveryMethod = config.deliveryMethod,
            errorMessage = config.errorMessage,
        )

    fun deliveredMessage() =
        createMessageUi(
            MessageConfig(
                status = "delivered",
                deliveryMethod = "direct",
            ),
        )

    fun failedMessage(errorMessage: String = "Connection timeout") =
        createMessageUi(
            MessageConfig(
                status = "failed",
                deliveryMethod = "direct",
                errorMessage = errorMessage,
            ),
        )

    fun pendingMessage() =
        createMessageUi(
            MessageConfig(
                status = "pending",
                deliveryMethod = "opportunistic",
            ),
        )

    fun sentMessage() =
        createMessageUi(
            MessageConfig(
                status = "sent",
                deliveryMethod = "direct",
            ),
        )

    fun opportunisticMessage() =
        createMessageUi(
            MessageConfig(
                status = "delivered",
                deliveryMethod = "opportunistic",
            ),
        )

    fun directMessage() =
        createMessageUi(
            MessageConfig(
                status = "delivered",
                deliveryMethod = "direct",
            ),
        )

    fun propagatedMessage() =
        createMessageUi(
            MessageConfig(
                status = "delivered",
                deliveryMethod = "propagated",
            ),
        )

    fun messageWithNoDeliveryMethod() =
        createMessageUi(
            MessageConfig(
                status = "delivered",
                deliveryMethod = null,
            ),
        )

    fun failedWithoutErrorMessage() =
        createMessageUi(
            MessageConfig(
                status = "failed",
                deliveryMethod = "direct",
                errorMessage = null,
            ),
        )
}
