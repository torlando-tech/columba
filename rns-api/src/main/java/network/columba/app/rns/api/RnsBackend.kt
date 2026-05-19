package network.columba.app.rns.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Root contract for a Reticulum Network Stack backend.
 *
 * The single binding the UI process holds across the AIDL boundary; sub-
 * interfaces (`core`, `lxmf`, `telephony`, `telemetry`, `nomadnet`,
 * `transportAdmin`) categorize the operations exposed by the backend so
 * ViewModels can inject only the surface they need rather than the full
 * 200-method legacy interface.
 *
 * Implementations:
 * - `:rns-backend-kt` — `NativeRnsBackend` over reticulum-kt.
 * - `:rns-backend-py` — `ChaquopyRnsBackend` over upstream Python RNS/LXMF.
 *
 * Capability gating: feature differences between backends are surfaced via
 * [capabilities] rather than per-method optional defaults. Methods that a
 * backend cannot honour throw `RnsException(RnsError.FeatureUnsupported(...))`;
 * the UI is expected to consult [capabilities] before invoking gated calls.
 */
interface RnsBackend {
    /**
     * Observable snapshot of what the bound backend can do. Surfaced as a
     * `StateFlow` (not a static value) because some capabilities mutate at
     * runtime — e.g., the LXST jar may be absent in stripped-down test
     * builds, RNode disconnects can downgrade `interfaces` capabilities,
     * etc. UI code subscribes and re-renders capability gates on change.
     */
    val capabilities: StateFlow<BackendCapabilities>

    /** RNS protocol primitives — identity, destination, packet, link, transport. */
    val core: RnsCore

    /** LXMF messaging — send/receive, propagation node sync, reactions. */
    val lxmf: RnsLxmf

    /** Voice calls (LXST). */
    val telephony: RnsTelephony

    /** Location telemetry & collector host mode. */
    val telemetry: RnsTelemetry

    /** NomadNet page browsing. */
    val nomadnet: RnsNomadnet

    /** Interface management, battery profile, RNode, BLE diagnostics. */
    val transportAdmin: RnsTransportAdmin
}
