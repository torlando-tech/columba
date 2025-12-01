package com.lxmf.messenger.reticulum.util

import android.util.Log

/**
 * Standardized logging utilities for the Reticulum module.
 *
 * Logging Format: Columba:Kotlin:ClassName:method
 *
 * This provides consistent, easily grep-able log output across all Reticulum components.
 * Makes it easy to filter logs by component and trace execution flow.
 *
 * Usage:
 * ```kotlin
 * class BleGattClient {
 *     companion object {
 *         private const val TAG = "Columba:Kotlin:BleGattClient"
 *     }
 *
 *     fun connect(address: String) {
 *         Log.d(TAG, "connect(): Connecting to $address")
 *     }
 * }
 * ```
 *
 * Or for convenience, define TAG helper:
 * ```kotlin
 * private val TAG = columbaTag("BleGattClient")
 * ```
 */

/**
 * Generate standardized Columba log TAG for a class.
 *
 * @param className Simple class name (e.g., "BleGattClient")
 * @return Standardized TAG string: "Columba:Kotlin:ClassName"
 */
fun columbaTag(className: String): String {
    return "Columba:Kotlin:$className"
}

/**
 * Format a log message with method name prefix.
 *
 * @param methodName Name of the method
 * @param message Log message
 * @return Formatted message: "methodName(): message"
 */
fun logMessage(
    methodName: String,
    message: String,
): String {
    return "$methodName(): $message"
}

/**
 * Format a log message with method name and line number.
 *
 * @param methodName Name of the method
 * @param lineNumber Line number in source (optional)
 * @param message Log message
 * @return Formatted message: "methodName:line: message" or "methodName(): message"
 */
fun logMessage(
    methodName: String,
    lineNumber: Int? = null,
    message: String,
): String {
    return if (lineNumber != null) {
        "$methodName:$lineNumber: $message"
    } else {
        "$methodName(): $message"
    }
}

/**
 * Extension functions for inline logging with automatic TAG generation.
 * Use these for quick logging without explicit TAG constants.
 *
 * Example:
 * ```kotlin
 * class MyClass {
 *     fun doSomething() {
 *         clogd("doSomething", "Starting operation")  // Columba:Kotlin:MyClass: doSomething(): Starting operation
 *     }
 * }
 * ```
 */

/** Log.d with Columba format */
inline fun <reified T> T.clogd(
    methodName: String,
    message: String,
) {
    Log.d(columbaTag(T::class.simpleName ?: "Unknown"), logMessage(methodName, message))
}

/** Log.i with Columba format */
inline fun <reified T> T.clogi(
    methodName: String,
    message: String,
) {
    Log.i(columbaTag(T::class.simpleName ?: "Unknown"), logMessage(methodName, message))
}

/** Log.w with Columba format */
inline fun <reified T> T.clogw(
    methodName: String,
    message: String,
) {
    Log.w(columbaTag(T::class.simpleName ?: "Unknown"), logMessage(methodName, message))
}

/** Log.e with Columba format */
inline fun <reified T> T.cloge(
    methodName: String,
    message: String,
) {
    Log.e(columbaTag(T::class.simpleName ?: "Unknown"), logMessage(methodName, message))
}

/** Log.e with Columba format and throwable */
inline fun <reified T> T.cloge(
    methodName: String,
    message: String,
    throwable: Throwable,
) {
    Log.e(columbaTag(T::class.simpleName ?: "Unknown"), logMessage(methodName, message), throwable)
}

/** Log.v with Columba format */
inline fun <reified T> T.clogv(
    methodName: String,
    message: String,
) {
    Log.v(columbaTag(T::class.simpleName ?: "Unknown"), logMessage(methodName, message))
}

/**
 * Grep patterns for filtering Columba logs:
 *
 * All Columba logs:
 *   adb logcat | grep "Columba:Kotlin:"
 *
 * Specific component:
 *   adb logcat | grep "Columba:Kotlin:BleGattClient"
 *
 * Multiple components:
 *   adb logcat | grep -E "Columba:Kotlin:(BleGattClient|BleScanner)"
 *
 * All BLE components:
 *   adb logcat | grep "Columba:Kotlin:Ble"
 *
 * With method context:
 *   adb logcat | grep "Columba:Kotlin:BleGattClient" | grep "connect()"
 */
