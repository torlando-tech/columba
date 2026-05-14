package network.columba.app.rns.backend.py

import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.BackendCapabilities.BackendId
import network.columba.app.rns.api.BackendCapabilities.InterfaceCaps
import network.columba.app.rns.api.BackendCapabilities.PerformanceCaps
import network.columba.app.rns.api.BackendCapabilities.Support
import network.columba.app.rns.api.BackendCapabilities.TelemetryCaps
import network.columba.app.rns.api.BackendCapabilities.Versions

/**
 * Static capability snapshot published by [ChaquopyRnsBackend]'s
 * `capabilities` StateFlow.
 *
 * The capability deltas vs. the kotlin backend ([NATIVE_CAPABILITIES]):
 *
 * - **`hotReloadInterfaces = false`** — upstream Python RNS has no live
 *   interface-reload path. Interface changes require a full RNS restart
 *   (`RnsCore.shutdown()` then `initialize()`), which the UI surfaces as
 *   "Apply & Restart". This is the headline capability difference.
 * - **`batteryProfileTuning = UNSUPPORTED`** — the BLE-scan / multicast-lock
 *   / AutoInterface aggressiveness tuning lives in reticulum-kt. The Python
 *   stack has no equivalent runtime knob; the UI replaces the battery
 *   profile picker with a notice pointing at Android's battery settings.
 * - **`collectorHostMode = FULL`** — telemetry collector host mode is the
 *   *well-tested reference path*. `release/v0.10.x` shipped it with upstream
 *   LXMF doing the FIELD_TELEMETRY_STREAM encoding; the slim-Python design
 *   preserves that by having `PythonRnsTelemetry` call upstream LXMF
 *   directly. (It is the kotlin backend's lxmf-kt reimplementation that is
 *   [Support.EXPERIMENTAL] pending the A.11 parity test — not this one.)
 * - **`sharedInstanceAvailabilityChecks = true`** — upstream RNS can probe
 *   for a co-located shared `rnsd` instance.
 */
val PYTHON_CAPABILITIES: BackendCapabilities = BackendCapabilities(
    backendId = BackendId.PYTHON_CHAQUOPY,
    versions = Versions(
        reticulum = "Reticulum ${BuildConfig.PY_RNS_VERSION}",
        lxmf = "LXMF ${BuildConfig.PY_LXMF_VERSION}",
        // LXST-kt drives voice on both backends — the Python flavor does not
        // ship a Python LXST. Version is filled in by :rns-host at wiring time
        // (it owns the lxst-kt dependency); null here is the pre-wire default.
        lxst = null,
        // ble-reticulum is torlando-tech's own project, NOT a fork of someone
        // else's — so no "fork" label (unlike RNS/LXMF, which are forks).
        bleReticulum = "ble-reticulum ${BuildConfig.PY_BLE_RETICULUM_VERSION}",
    ),
    interfaces = InterfaceCaps(
        hotReloadInterfaces = false,
        degradationHint =
            "The Python backend cannot hot-reload interfaces — applying changes " +
                "restarts Reticulum (briefly disconnecting from peers).",
    ),
    telemetry = TelemetryCaps(
        collectorHostMode = Support.FULL,
        storeOwnTelemetry = Support.FULL,
        allowedRequestersFilter = Support.FULL,
    ),
    performance = PerformanceCaps(
        batteryProfileTuning = Support.UNSUPPORTED,
        sharedInstanceAvailabilityChecks = true,
    ),
)
