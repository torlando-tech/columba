package com.lxmf.messenger.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "messages",
    primaryKeys = ["id", "identityHash"], // Composite PK for identity separation
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["peerHash", "identityHash"], // Updated to composite FK
            childColumns = ["conversationHash", "identityHash"],
            onDelete = ForeignKey.CASCADE, // Delete messages when conversation deleted
        ),
        ForeignKey(
            entity = LocalIdentityEntity::class,
            parentColumns = ["identityHash"],
            childColumns = ["identityHash"],
            onDelete = ForeignKey.CASCADE, // Delete messages when identity deleted
        ),
    ],
    indices = [
        Index("conversationHash", "identityHash"), // Composite index for FK
        Index("identityHash"),
        Index("timestamp"), // For ordering by timestamp
        Index("conversationHash", "identityHash", "timestamp"), // For queries with ordering
        Index("conversationHash", "identityHash", "isFromMe", "isRead"), // For unread count queries
        Index("replyToMessageId"), // For efficient reply lookups
    ], // Indexes for faster queries
)
data class MessageEntity(
    val id: String, // Message hash or UUID
    val conversationHash: String, // Peer's destination hash
    val identityHash: String, // Which local identity owns this message
    val content: String, // Message content
    val timestamp: Long, // Message timestamp
    val isFromMe: Boolean, // True if sent by us, false if received
    val status: String = "sent", // "sent", "delivered", "failed"
    val isRead: Boolean = false, // Whether message has been read by user
    // LXMF fields support (attachments, images, etc.)
    // Fields are stored as JSON: {"6": "hex_image_data", "7": "hex_audio_data"}
    // Key is LXMF field type: 5=FILE_ATTACHMENTS, 6=IMAGE, 7=AUDIO, 15=RENDERER
    val fieldsJson: String? = null,
    // Delivery method used when sending: "opportunistic", "direct", or "propagated"
    val deliveryMethod: String? = null,
    // Error message if delivery failed (when status == "failed")
    val errorMessage: String? = null,
    // ID of message this is a reply to (extracted from LXMF field 16 "reply_to")
    val replyToMessageId: String? = null,
    // Hop count when message was received (null for sent messages or pre-feature messages)
    val receivedHopCount: Int? = null,
    // Interface name through which message was received (null for sent messages or pre-feature messages)
    val receivedInterface: String? = null,
    // RSSI in dBm when message was received (typically -30 to -120, null for TCP/AutoInterface or sent)
    val receivedRssi: Int? = null,
    // SNR in dB when message was received (typically -20 to +20, null for BLE/TCP or sent messages)
    val receivedSnr: Float? = null,
)
