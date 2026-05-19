package network.columba.app.rns.backend.kt

import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.BackendCapabilities.BackendId
import network.columba.app.rns.api.BackendCapabilities.InterfaceCaps
import network.columba.app.rns.api.BackendCapabilities.PerformanceCaps
import network.columba.app.rns.api.BackendCapabilities.Support
import network.columba.app.rns.api.BackendCapabilities.TelemetryCaps
import network.columba.app.rns.api.BackendCapabilities.Versions

/**
 * Static capability snapshot published by [NativeRnsBackend]'s
 * `capabilities` StateFlow.
 *
 * Telemetry collector host mode is intentionally [Support.EXPERIMENTAL] until
 * Phase B's lxmf-kt encoder parity test promotes it to [Support.FULL] — the
 * reimplementation hasn't been parity-tested against upstream Python LXMF's
 * FIELD_TELEMETRY_STREAM encoder yet (see Plan A.11 / Phase B note in the
 * top-level plan doc). UI renders an "EXPERIMENTAL" pill in this state.
 */
val NATIVE_CAPABILITIES: BackendCapabilities = BackendCapabilities(
    backendId = BackendId.KOTLIN_NATIVE,
    versions = Versions(
        reticulum = "Reticulum-kt ${BuildConfig.RNS_KT_VERSION}",
        lxmf = "LXMF-kt ${BuildConfig.LXMF_KT_VERSION}",
        lxst = "LXST-kt ${BuildConfig.LXST_KT_VERSION}",
        bleReticulum = null,
    ),
    interfaces = InterfaceCaps(
        hotReloadInterfaces = true,
    ),
    telemetry = TelemetryCaps(
        collectorHostMode = Support.EXPERIMENTAL,
        storeOwnTelemetry = Support.FULL,
        allowedRequestersFilter = Support.FULL,
        degradationHint = "lxmf-kt FIELD_TELEMETRY_STREAM encoder pending parity test against upstream Python LXMF",
    ),
    performance = PerformanceCaps(
        batteryProfileTuning = Support.FULL,
        sharedInstanceAvailabilityChecks = false,
    ),
)
