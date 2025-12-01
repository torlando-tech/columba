package com.lxmf.messenger.reticulum.model

sealed class ReticulumError : Exception() {
    data class NetworkError(override val message: String) : ReticulumError()

    data class TimeoutError(val operation: String) : ReticulumError() {
        override val message: String
            get() = "Operation timed out: $operation"
    }

    data class InvalidDestination(val hash: String) : ReticulumError() {
        override val message: String
            get() = "Invalid destination: $hash"
    }

    data class EncryptionError(override val message: String) : ReticulumError()

    data class SerializationError(override val message: String) : ReticulumError()
}
