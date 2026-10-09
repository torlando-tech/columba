package network.columba.app.rns.backend.kt

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import network.columba.app.rns.api.model.InterfaceConfig
import network.reticulum.common.InterfaceMode
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.ble.BLEDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Reproduces [issue #1169](https://github.com/torlando-tech/columba/issues/1169):
 * "Kotlin backend: mode is silently dropped for all interface types (TCP, RNode/SPP, UDP)."
 *
 * The user-selected interface mode is persisted to the DB and written to the RNS
 * config file (which the Python flavor honors), but `NativeInterfaceFactory`
 * builds every reticulum-kt interface object WITHOUT passing the mode, so the
 * constructed object silently reports the default [InterfaceMode.FULL] no matter
 * what the user chose.
 *
 * This test drives the real production wiring: it invokes the private
 * `NativeInterfaceFactory.createInterface(config)` via reflection for the
 * socket-free interface types (AutoInterface, TCPClient, UDP) and asserts the
 * resulting reticulum-kt object reports the mode the user configured.
 *
 * It is RED today for two independent reasons, both of which the fix must close:
 *  1. There is no Columba -> reticulum-kt mode mapping in `rns-backend-kt`
 *     (the factory never references [InterfaceMode] at all).
 *  2. The factory never applies the mapped mode to the constructed object, so
 *     every object stays at the default FULL.
 *
 * INTERNAL is handled as a "not-yet-mappable" case: the pinned reticulum-kt
 * [InterfaceMode] enum has no INTERNAL value (reticulum-kt #91, still open), so
 * the factory must NOT silently coerce internal to FULL - it must leave the
 * interface un-mapped (modeOverride == null, declared mode == FULL) and log a
 * warning. That is the only honest representation until the enum lands INTERNAL.
 */
class InterfaceModeWiringTest {

    /**
     * Maps a Columba interface-mode string to the reticulum-kt [InterfaceMode]
     * the fix must apply. Returns null for values reticulum-kt cannot represent
     * yet (INTERNAL) or for unknown strings, which the factory must treat as
     * "leave default + warn" rather than silently coercing to FULL.
     *
     * Kept in the test as the reference vocabulary; the implementation fix
     * should mirror this mapping.
     */
    private fun expectedReticulumMode(columbaMode: String): InterfaceMode? =
        when (columbaMode) {
            "full" -> InterfaceMode.FULL
            "gateway" -> InterfaceMode.GATEWAY
            "access_point" -> InterfaceMode.ACCESS_POINT
            "roaming" -> InterfaceMode.ROAMING
            "boundary" -> InterfaceMode.BOUNDARY
            "internal" -> null // reticulum-kt #91: no INTERNAL value yet
            else -> null
        }

    /** Effective mode exactly as Transport/InterfaceAdapter surface it. */
    private fun effectiveMode(iface: network.reticulum.interfaces.Interface): InterfaceMode =
        InterfaceAdapter.getOrCreate(iface).mode

    /** Build the interface via the production factory wiring (no start(), no sockets). */
    private fun buildInterface(config: InterfaceConfig): network.reticulum.interfaces.Interface {
        val iface = NativeInterfaceFactory.createInterface(config)
        assertNotNull(
            "createInterface returned null for ${config.typeName}; expected a concrete interface object",
            iface,
        )
        return iface!!
    }

    @Test
    fun autoInterface_reportsConfiguredMode() {
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary")) {
            val config = InterfaceConfig.AutoInterface(name = "auto-$mode", enabled = true, mode = mode)
            val obj = buildInterface(config)
            val expected = expectedReticulumMode(mode)!!
            assertEquals(
                "AutoInterface mode='$mode' should report $expected",
                expected,
                effectiveMode(obj),
            )
        }
    }

    @Test
    fun tcpClient_reportsConfiguredMode() {
        for (mode in listOf("gateway", "access_point", "roaming", "boundary")) {
            val config =
                InterfaceConfig.TCPClient(
                    name = "tcp-$mode",
                    enabled = true,
                    targetHost = "127.0.0.1",
                    targetPort = 4242,
                    mode = mode,
                )
            val obj = buildInterface(config)
            val expected = expectedReticulumMode(mode)!!
            assertEquals(
                "TCPClient mode='$mode' should report $expected",
                expected,
                effectiveMode(obj),
            )
        }
    }

    @Test
    fun tcpServer_reportsConfiguredMode() {
        // TCPServerInterface binds its ServerSocket only inside start(), so the
        // factory can construct it without a network - same as TCPClient.
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary")) {
            val config = InterfaceConfig.TCPServer(name = "tcpserver-$mode", enabled = true, mode = mode)
            val obj = buildInterface(config)
            val expected = expectedReticulumMode(mode)!!
            assertEquals(
                "TCPServer mode='$mode' should report $expected",
                expected,
                effectiveMode(obj),
            )
        }
    }

    @Test
    fun udp_reportsConfiguredMode() {
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary")) {
            val config = InterfaceConfig.UDP(name = "udp-$mode", enabled = true, mode = mode)
            val obj = buildInterface(config)
            val expected = expectedReticulumMode(mode)!!
            assertEquals(
                "UDP mode='$mode' should report $expected",
                expected,
                effectiveMode(obj),
            )
        }
    }

    @Test
    fun rnode_reportsConfiguredMode() =
        runBlocking {
            // Drive the REAL RNodeConnectionHelper (not a copy): mock the host
            // bridge so openUsbSerial hands back in-memory streams, and capture
            // the interface the helper registers. If the helper stopped applying
            // `.withMode` to the constructed RNodeInterface (issue #1169), every
            // mode below would report FULL and this fails.
            val input = java.io.ByteArrayInputStream(ByteArray(0))
            val output = java.io.ByteArrayOutputStream()
            val bridge =
                mockk<RNodeHostBridge> {
                    coEvery { openUsbSerial(any(), any(), any(), any()) } returns Pair(input, output)
                    every { rnodeFramebufferData() } returns null
                }
            val ctx = mockk<android.content.Context>()
            for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary")) {
                var captured: network.reticulum.interfaces.Interface? = null
                RNodeConnectionHelper.startRNodeInterface(
                    config =
                        InterfaceConfig.RNode(
                            name = "rnode-$mode",
                            enabled = true,
                            connectionMode = "usb",
                            enableFramebuffer = false,
                            mode = mode,
                        ),
                    appContext = ctx,
                    hostBridge = bridge,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    onRegisterAndTrack = { _, iface -> captured = iface },
                    onMonitorLifecycle = { _, _ -> },
                    onEnsureRecovery = { },
                )
                val obj = captured!!
                val expected = expectedReticulumMode(mode)!!
                assertEquals(
                    "RNode mode='$mode' should report $expected",
                    expected,
                    effectiveMode(obj),
                )
            }
        }

    @Test
    fun ble_reportsConfiguredMode() {
        // startBleInterface() builds a BLEInterface then applies
        // `.withMode(config.name, config.mode)` (NativeInterfaceFactory.kt:255).
        // Reproduce that exact wiring: construct a real BLEInterface (fake driver,
        // no start()) and apply the same .withMode call. If that call were dropped,
        // every mode would report FULL and this fails.
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary")) {
            val obj =
                network.reticulum.interfaces.ble.BLEInterface(
                    name = "ble-$mode",
                    driver = mockk<BLEDriver>(),
                    transportIdentity = ByteArray(20),
                ).withMode("ble-$mode", mode)
            val expected = expectedReticulumMode(mode)!!
            assertEquals(
                "BLE mode='$mode' should report $expected",
                expected,
                effectiveMode(obj),
            )
        }
    }

    @Test
    fun modeIsActuallyConsumed_notSilentlyDropped() {
        // Core regression: the factory must CHANGE the constructed mode based on
        // the config. If it ignores the field (today's bug), both configs yield
        // the default FULL and this assert fails.
        val tcpGateway =
            buildInterface(
                InterfaceConfig.TCPClient(
                    name = "tcp-gateway",
                    enabled = true,
                    targetHost = "127.0.0.1",
                    targetPort = 4242,
                    mode = "gateway",
                ),
            )
        val tcpFull =
            buildInterface(
                InterfaceConfig.TCPClient(
                    name = "tcp-full",
                    enabled = true,
                    targetHost = "127.0.0.1",
                    targetPort = 4242,
                    mode = "full",
                ),
            )
        assertEquals(
            "TCPClient(mode=gateway) must report GATEWAY",
            InterfaceMode.GATEWAY,
            effectiveMode(tcpGateway),
        )
        assertNotEquals(
            "mode field is being ignored: gateway and full both report the same " +
                "(${effectiveMode(tcpGateway)})",
            effectiveMode(tcpFull),
            effectiveMode(tcpGateway),
        )
    }

    @Test
    fun internalMode_isNotSilentlyCoercedToFull() {
        // reticulum-kt #91: the pinned InterfaceMode enum has no INTERNAL.
        // The factory must not silently coerce "internal" to FULL (that would
        // be a behavior change the user never asked for and the wrong semantics).
        // Acceptable behavior: leave the interface at its declared default with
        // modeOverride == null (the "no mapping, log a warning" path).
        // What must NOT happen: a fabricated INTERNAL, or a hard-wired FULL that
        // makes internal indistinguishable from a deliberate full selection.
        val config =
            InterfaceConfig.TCPClient(
                name = "tcp-internal",
                enabled = true,
                targetHost = "127.0.0.1",
                targetPort = 4242,
                mode = "internal",
            )
        val iface = buildInterface(config)
        // The pinned enum cannot express INTERNAL, so the object must not pretend
        // to be a mappable mode via modeOverride.
        assertEquals(
            "internal must not be coerced to a mappable modeOverride on pinned reticulum-kt",
            null,
            iface.modeOverride,
        )
        // And it must be left at the declared default, not silently coerced to FULL.
        assertEquals(
            "internal must be left at the declared default mode (FULL), not coerced",
            InterfaceMode.FULL,
            effectiveMode(iface),
        )
    }
}
