package com.lxmf.messenger.reticulum.bindings.rns

/**
 * Main entry point for Reticulum lifecycle management.
 *
 * Mirrors reticulum-kt: `Reticulum.start()`, `Reticulum.stop()`, `Reticulum.isStarted()`.
 * Blocking functions — callers dispatch to Dispatchers.IO as needed.
 */
interface RnsReticulum {
    fun start(
        configDir: String,
        enableTransport: Boolean = false,
    ): Boolean

    fun stop()

    fun isStarted(): Boolean

    fun isTransportEnabled(): Boolean

    fun getVersion(): String?
}
