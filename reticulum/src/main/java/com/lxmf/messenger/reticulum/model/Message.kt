package com.lxmf.messenger.reticulum.model

data class Message(
    val id: String,
    val destinationHash: String,
    val content: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    val status: String = "sent", // "sent", "delivered", "failed"
    // LXMF fields as JSON: {"6": "hex_image_data", "7": "hex_audio_data"}
    // Key is LXMF field type: 5=FILE_ATTACHMENTS, 6=IMAGE, 7=AUDIO, 15=RENDERER
    val fieldsJson: String? = null,
)
