package com.lxmf.messenger.service.rns

import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.bindings.rns.RnsReticulum

/**
 * Chaquopy implementation of [RnsReticulum].
 * Wraps `rns_api.RnsApi` lifecycle methods.
 *
 * @param api The live Python `RnsApi` instance
 */
class ChaquopyRnsReticulum(
    private val api: PyObject,
) : RnsReticulum,
    AutoCloseable {
    @Volatile
    var shuttingDown = false

    override fun start(
        configDir: String,
        enableTransport: Boolean,
    ): Boolean {
        if (shuttingDown) return false
        val result = api.callAttr("start", configDir, enableTransport)
        return try {
            result?.toBoolean() ?: false
        } finally {
            result?.close()
        }
    }

    override fun stop() {
        shuttingDown = true
        runCatching { api.callAttr("stop")?.close() }
    }

    override fun isStarted(): Boolean {
        if (shuttingDown) return false
        val result = api.callAttr("is_started")
        return try {
            result?.toBoolean() ?: false
        } finally {
            result?.close()
        }
    }

    override fun isTransportEnabled(): Boolean {
        if (shuttingDown) return false
        val result = api.callAttr("is_transport_enabled")
        return try {
            result?.toBoolean() ?: false
        } finally {
            result?.close()
        }
    }

    override fun getVersion(): String? {
        if (shuttingDown) return null
        val result = api.callAttr("get_rns_version")
        return try {
            result?.toString()
        } finally {
            result?.close()
        }
    }

    override fun close() {
        shuttingDown = true
    }
}
