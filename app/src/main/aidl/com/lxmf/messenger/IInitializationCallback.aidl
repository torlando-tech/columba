package com.lxmf.messenger;

/**
 * Callback interface for asynchronous Reticulum initialization.
 *
 * Phase 1, Task 1.2: Remove runBlocking from IPC
 *
 * This callback replaces the blocking initialize() method with an async pattern.
 * The service can launch a coroutine and return the binder thread immediately,
 * then call back when initialization completes.
 *
 * Benefits:
 * - Binder thread returns in < 1ms (no blocking)
 * - No risk of ANR (Application Not Responding)
 * - No binder thread pool exhaustion
 * - Proper async/await semantics in Kotlin
 */
interface IInitializationCallback {
    /**
     * Called when initialization completes successfully.
     *
     * @param result JSON string with initialization result data
     */
    void onInitializationComplete(String result);

    /**
     * Called when initialization fails.
     *
     * @param error Error message describing what went wrong
     */
    void onInitializationError(String error);
}
