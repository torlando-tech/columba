package com.lxmf.messenger

import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Bridge between Kotlin and Python code.
 * Handles initialization and communication with Python modules via Chaquopy.
 */
object PythonBridge {
    private const val TAG = "PythonBridge"
    private var isInitialized = false

    /**
     * Initialize the Python environment.
     * Should be called once, typically in Application.onCreate()
     */
    fun initialize(application: android.app.Application) {
        if (isInitialized) {
            Log.d(TAG, "Python already initialized")
            return
        }

        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(application))
                Log.d(TAG, "Python environment started successfully")
            }
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python", e)
            throw e
        }
    }

    /**
     * Get a hello message from Python to verify integration works.
     *
     * @return Hello message string from Python, or error message if failed
     */
    fun getHelloFromPython(): String {
        return try {
            if (!isInitialized) {
                return "Python not initialized"
            }

            val py = Python.getInstance()
            val module = py.getModule("reticulum_wrapper")
            val result = module.callAttr("get_hello_message")

            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call Python function", e)
            "Error calling Python: ${e.message}"
        }
    }

    /**
     * Check if Python is initialized and ready.
     */
    fun isReady(): Boolean = isInitialized && Python.isStarted()
}
