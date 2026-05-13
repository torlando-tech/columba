package network.columba.app.rns.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Snapshot of what a backend implementation supports, observed by the UI to
 * gate features that are kotlin-only or python-only.
 *
 * Structured as a tree (one sub-record per feature area) rather than a flat
 * boolean bag so that grouping forces "which family does this belong to"
 * thinking at the call site, and groups of related capabilities can grow
 * without touching unrelated code.
 *
 * Surfaced as a `StateFlow<BackendCapabilities>` on the root [RnsBackend]
 * binding — observable so runtime-mutable capabilities (LXST jar load
 * status, RNode disconnects, etc.) propagate to the UI without a re-bind.
 *
 * @see Support for the per-feature tri-state.
 * @see BackendId for which implementation is bound.
 */
@Parcelize
data class BackendCapabilities(
    val backendId: BackendId,
    val versions: Versions,
    val interfaces: InterfaceCaps,
    val telemetry: TelemetryCaps,
    val performance: PerformanceCaps,
) : Parcelable {
    /**
     * Versions of the underlying protocol libraries. Co-located with
     * capability flags so the About screen reads them in one IPC roundtrip
     * instead of four. `null` means the library isn't shipped on this
     * backend (e.g., LXST has no python equivalent today).
     */
    @Parcelize
    data class Versions(
        val reticulum: String?,
        val lxmf: String?,
        val lxst: String?,
        val bleReticulum: String?,
    ) : Parcelable

    /**
     * Interface management capabilities. The kotlin backend can hot-reload
     * RNS interface configs without restarting the protocol stack; the
     * python backend needs a full restart (~5–10s outage), so the UI
     * surfaces an explicit "Apply & Restart" button instead of applying
     * silently.
     *
     * `hotReloadInterfaces` is a single boolean rather than a tri-state
     * because every realistic implementation either applies live or
     * requires a restart — there is no "unsupported" state where interface
     * changes have no path to take effect at all.
     */
    @Parcelize
    data class InterfaceCaps(
        val hotReloadInterfaces: Boolean,
        val degradationHint: String? = null,
    ) : Parcelable

    /**
     * Telemetry collector host-mode capabilities. The python backend ships
     * upstream LXMF's well-tested FIELD_TELEMETRY_STREAM encoder; the
     * kotlin backend uses lxmf-kt's reimplementation which needs a parity
     * test pass before being trusted (Phase A.11). If parity fails, the
     * kotlin flag downgrades to [Support.EXPERIMENTAL] until lxmf-kt is
     * fixed — no UI change required beyond rendering a "Beta" pill.
     */
    @Parcelize
    data class TelemetryCaps(
        val collectorHostMode: Support,
        val storeOwnTelemetry: Support,
        val allowedRequestersFilter: Support,
        val degradationHint: String? = null,
    ) : Parcelable

    /**
     * Performance-tuning capabilities that aren't strictly protocol
     * concerns. `batteryProfileTuning` adjusts BLE scan intervals,
     * multicast lock acquisition, and AutoInterface aggressiveness — only
     * the kotlin backend has the hooks. `sharedInstanceAvailabilityChecks`
     * lets the UI detect when a co-located rnsd shared instance is
     * present; only python upstream RNS exposes the necessary check.
     */
    @Parcelize
    data class PerformanceCaps(
        val batteryProfileTuning: Support,
        val sharedInstanceAvailabilityChecks: Boolean,
    ) : Parcelable

    /**
     * Tri-state per-feature support indicator.
     *
     * - [FULL]: feature is implemented and ready for production use.
     * - [UNSUPPORTED]: feature is not implemented; UI should hide or
     *   replace the entry point with an unavailable-notice.
     * - [EXPERIMENTAL]: feature is implemented but not yet trust-validated
     *   (e.g., a port that hasn't passed its parity test). UI should
     *   render with a "Beta" indicator.
     */
    enum class Support {
        FULL,
        UNSUPPORTED,
        EXPERIMENTAL,
    }

    /**
     * Which backend implementation is bound. The UI uses this for
     * informational purposes (About screen, debug tooling); behavior gates
     * should test specific capability flags rather than branching on
     * backend identity, so the seam can grow new backends without churning
     * UI code.
     */
    enum class BackendId {
        KOTLIN_NATIVE,
        PYTHON_CHAQUOPY,
    }

    companion object {
        /**
         * Sentinel snapshot returned by the IPC layer before a backend binding
         * has been established (e.g., from the early seed of the UI-side
         * `StateFlow<BackendCapabilities>` between `RnsBackendClient`
         * construction and `connect()` completing). Every capability is the
         * safe-default; UI code that gates on a capability before the first
         * real snapshot lands behaves as though the backend can't honour it,
         * which is correct: there is no backend.
         */
        val UNKNOWN: BackendCapabilities = BackendCapabilities(
            backendId = BackendId.KOTLIN_NATIVE,
            versions = Versions(null, null, null, null),
            interfaces = InterfaceCaps(hotReloadInterfaces = false),
            telemetry = TelemetryCaps(
                collectorHostMode = Support.UNSUPPORTED,
                storeOwnTelemetry = Support.UNSUPPORTED,
                allowedRequestersFilter = Support.UNSUPPORTED,
            ),
            performance = PerformanceCaps(
                batteryProfileTuning = Support.UNSUPPORTED,
                sharedInstanceAvailabilityChecks = false,
            ),
        )
    }
}
