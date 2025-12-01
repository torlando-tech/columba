package com.lxmf.messenger;

/**
 * Callback interface for service binding readiness notification.
 *
 * Phase 2, Task 2.3: Explicit Service Readiness
 *
 * This callback eliminates arbitrary delay() calls after service binding
 * by providing explicit notification when the service is ready to handle
 * requests. The service calls this immediately after binding completes,
 * allowing clients to proceed without guessing when it's safe to call methods.
 *
 * Benefits:
 * - No arbitrary delays (e.g., delay(500))
 * - Deterministic behavior
 * - Faster service interaction (< 100ms from bind to ready)
 * - Removes race conditions from timing assumptions
 */
interface IReadinessCallback {
    /**
     * Called when the service is ready to handle requests.
     * This is invoked immediately after service binding completes
     * and the service has initialized its internal state.
     */
    void onServiceReady();
}
