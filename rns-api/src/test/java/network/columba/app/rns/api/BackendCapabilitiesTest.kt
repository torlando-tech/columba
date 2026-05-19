package network.columba.app.rns.api

import network.columba.app.rns.api.BackendCapabilities.BackendId
import network.columba.app.rns.api.BackendCapabilities.InterfaceCaps
import network.columba.app.rns.api.BackendCapabilities.PerformanceCaps
import network.columba.app.rns.api.BackendCapabilities.Support
import network.columba.app.rns.api.BackendCapabilities.TelemetryCaps
import network.columba.app.rns.api.BackendCapabilities.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BackendCapabilitiesTest {
    @Test
    fun `kotlin reference snapshot has expected flag set`() {
        val caps = sampleKotlinCapabilities()
        assertEquals(BackendId.KOTLIN_NATIVE, caps.backendId)
        assert(caps.interfaces.hotReloadInterfaces)
        assertEquals(Support.FULL, caps.performance.batteryProfileTuning)
        assertEquals(Support.FULL, caps.telemetry.collectorHostMode)
        assert(!caps.performance.sharedInstanceAvailabilityChecks)
    }

    @Test
    fun `python reference snapshot has expected flag set`() {
        val caps = samplePythonCapabilities()
        assertEquals(BackendId.PYTHON_CHAQUOPY, caps.backendId)
        assert(!caps.interfaces.hotReloadInterfaces)
        assertEquals(Support.UNSUPPORTED, caps.performance.batteryProfileTuning)
        // Telemetry collector host mode is the well-tested reference path on python.
        assertEquals(Support.FULL, caps.telemetry.collectorHostMode)
        assert(caps.performance.sharedInstanceAvailabilityChecks)
    }

    @Test
    fun `degradation hint is null by default`() {
        val caps = sampleKotlinCapabilities()
        assertNull(caps.interfaces.degradationHint)
        assertNull(caps.telemetry.degradationHint)
    }

    @Test
    fun `degradation hint round-trips when provided`() {
        val hint = "Python backend restarts RNS for ~5-10s when applying interface changes."
        val caps = samplePythonCapabilities().copy(interfaces = InterfaceCaps(hotReloadInterfaces = false, degradationHint = hint))
        assertEquals(hint, caps.interfaces.degradationHint)
    }

    @Test
    fun `equality is structural`() {
        val a = sampleKotlinCapabilities()
        val b = sampleKotlinCapabilities()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `support enum has three states`() {
        // Guards against accidental pruning of the EXPERIMENTAL state, which the
        // UI relies on to render "Beta" pills for capabilities that exist but
        // aren't trust-validated.
        assertEquals(3, Support.entries.size)
        assertNotNull(Support.entries.find { it == Support.FULL })
        assertNotNull(Support.entries.find { it == Support.UNSUPPORTED })
        assertNotNull(Support.entries.find { it == Support.EXPERIMENTAL })
    }

    private fun sampleKotlinCapabilities() =
        BackendCapabilities(
            backendId = BackendId.KOTLIN_NATIVE,
            versions = Versions(reticulum = "0.0.20", lxmf = "0.0.13", lxst = "0.0.3", bleReticulum = "0.2.2"),
            interfaces = InterfaceCaps(hotReloadInterfaces = true),
            telemetry =
                TelemetryCaps(
                    collectorHostMode = Support.FULL,
                    storeOwnTelemetry = Support.FULL,
                    allowedRequestersFilter = Support.FULL,
                ),
            performance =
                PerformanceCaps(
                    batteryProfileTuning = Support.FULL,
                    sharedInstanceAvailabilityChecks = false,
                ),
        )

    private fun samplePythonCapabilities() =
        BackendCapabilities(
            backendId = BackendId.PYTHON_CHAQUOPY,
            versions = Versions(reticulum = "0.7.4", lxmf = "0.5.4", lxst = null, bleReticulum = "n/a"),
            interfaces = InterfaceCaps(hotReloadInterfaces = false),
            telemetry =
                TelemetryCaps(
                    collectorHostMode = Support.FULL,
                    storeOwnTelemetry = Support.FULL,
                    allowedRequestersFilter = Support.FULL,
                ),
            performance =
                PerformanceCaps(
                    batteryProfileTuning = Support.UNSUPPORTED,
                    sharedInstanceAvailabilityChecks = true,
                ),
        )
}
