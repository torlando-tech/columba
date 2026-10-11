package network.columba.app.rns.backend.kt

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import network.columba.app.rns.api.model.InterfaceConfig
import network.reticulum.common.InterfaceMode
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.ble.BLEDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
 * INTERNAL is mappable from reticulum-kt v0.0.24 (reticulum-kt #91 landed):
 * the [InterfaceMode] enum has INTERNAL with the RNS 1.3.6+ semi-transport
 * semantics, so the factory wires "internal" 1:1 to [InterfaceMode.INTERNAL]
 * rather than leaving the interface at the default.
 */
class InterfaceModeWiringTest {

    /**
     * Maps a Columba interface-mode string to the reticulum-kt [InterfaceMode]
     * the fix must apply. Returns null only for unknown strings, which the
     * factory must treat as "leave default + warn" rather than silently
     * coercing to FULL. INTERNAL maps 1:1 (reticulum-kt v0.0.24, #91 landed).
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
            "internal" -> InterfaceMode.INTERNAL // reticulum-kt v0.0.24 (#91 landed)
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
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary", "internal")) {
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
        for (mode in listOf("gateway", "access_point", "roaming", "boundary", "internal")) {
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
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary", "internal")) {
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
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary", "internal")) {
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
            for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary", "internal")) {
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
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary", "internal")) {
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
    fun savedModeEditOnRunningInterface_restartsAndAppliesMode() {
        // Regression for issue #1169 / PR #1188 review P1: the diff-based
        // syncInterfaces only *started* names not already running, so saving a
        // new mode for a running interface was a silent no-op. It must now
        // restart the interface so the running object reports the new mode.
        val full =
            InterfaceConfig.TCPClient(
                name = "tcp-mode-edit",
                enabled = true,
                targetHost = "127.0.0.1",
                targetPort = 4242,
                mode = "full",
            )
        try {
            NativeInterfaceFactory.syncInterfaces(listOf(full))
            val initial = NativeInterfaceFactory.currentInterfaces.first { it.name == "tcp-mode-edit" }
            assertEquals(
                "interface should start at the configured FULL mode",
                InterfaceMode.FULL,
                effectiveMode(initial),
            )

            // User saves the same interface with a different mode.
            val gateway = full.copy(mode = "gateway")
            NativeInterfaceFactory.syncInterfaces(listOf(gateway))
            val updated = NativeInterfaceFactory.currentInterfaces.first { it.name == "tcp-mode-edit" }
            assertNotSame(
                "a saved mode edit must replace the running interface object",
                initial,
                updated,
            )
            assertEquals(
                "after the saved edit, the running interface must report the new GATEWAY mode",
                InterfaceMode.GATEWAY,
                effectiveMode(updated),
            )
        } finally {
            NativeInterfaceFactory.shutdownAll()
        }
    }

    @Test
    fun unchangedRunningInterface_isNotRestartedOnSync() {
        // Guard against the fix over-firing: if only the mode is the comparison
        // key, a sync with the same mode must NOT restart the interface (no
        // spurious reconnect churn on every save of an unrelated field).
        val full =
            InterfaceConfig.TCPClient(
                name = "tcp-stable",
                enabled = true,
                targetHost = "127.0.0.1",
                targetPort = 4242,
                mode = "full",
            )
        try {
            NativeInterfaceFactory.syncInterfaces(listOf(full))
            val initial = NativeInterfaceFactory.currentInterfaces.first { it.name == "tcp-stable" }
            NativeInterfaceFactory.syncInterfaces(listOf(full))
            val after = NativeInterfaceFactory.currentInterfaces.first { it.name == "tcp-stable" }
            assertSame(
                "a sync with an unchanged mode must not restart the running interface",
                initial,
                after,
            )
        } finally {
            NativeInterfaceFactory.shutdownAll()
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
    fun internalMode_isWiredToReticulumInternal() {
        // reticulum-kt v0.0.24 (#91 landed): the InterfaceMode enum has INTERNAL,
        // so the factory wires "internal" 1:1 to ReticulumMode.INTERNAL instead
        // of leaving it at the default. This is the RNS 1.3.6+ semi-transport
        // mode (relays + path discovery, announce re-broadcast gated on the
        // next-hop mode). What must NOT happen: a fabricated value, or a silent
        // coercion to FULL that makes internal indistinguishable from a
        // deliberate full selection.
        val config =
            InterfaceConfig.TCPClient(
                name = "tcp-internal",
                enabled = true,
                targetHost = "127.0.0.1",
                targetPort = 4242,
                mode = "internal",
            )
        val iface = buildInterface(config)
        // The factory must set modeOverride to the real INTERNAL value.
        assertEquals(
            "internal must be wired to ReticulumMode.INTERNAL (not left null, not coerced to FULL)",
            InterfaceMode.INTERNAL,
            iface.modeOverride,
        )
        // And the effective mode (as Transport/InterfaceAdapter surface it)
        // must be INTERNAL, not the declared default FULL.
        assertEquals(
            "internal must report INTERNAL as its effective mode, not the FULL default",
            InterfaceMode.INTERNAL,
            effectiveMode(iface),
        )
    }

    @Test
    fun disabledInFlightAsyncStart_doesNotReviveInterface() {
        // Regression for PR #1188 review: a saved disable/delete issued while an
        // async RNode/BLE start is still waiting on a radio connection must not
        // let the stale start register later and revive the interface. The
        // factory bumps the start generation when a name is no longer desired;
        // the in-flight start's registerAndTrack must see the mismatch and bail.
        //
        // Drive it deterministically: park the in-flight start in a mock host
        // bridge (suspend on openUsbSerial), disable the interface while it is
        // parked, then release it and assert the interface never registers.
        val gate = CompletableDeferred<Unit>()
        val input = java.io.ByteArrayInputStream(ByteArray(0))
        val output = java.io.ByteArrayOutputStream()
        val bridge =
            mockk<RNodeHostBridge> {
                coEvery { openUsbSerial(any(), any(), any(), any()) } coAnswers {
                    gate.await()
                    Pair(input, output)
                }
                every { rnodeFramebufferData() } returns null
            }
        val ctx = mockk<android.content.Context>()
        NativeInterfaceFactory.appContext = ctx
        NativeInterfaceFactory.rnodeHostBridge = bridge
        try {
            val rnode =
                InterfaceConfig.RNode(
                    name = "rnode-park",
                    enabled = true,
                    connectionMode = "usb",
                    enableFramebuffer = false,
                )
            // Start it: the factory captures gen=1 (synchronously) and launches
            // the async start, which parks in openUsbSerial.
            NativeInterfaceFactory.syncInterfaces(listOf(rnode))
            // Disable it while the start is still in flight. This must bump the
            // generation so the parked start is invalidated on resume.
            NativeInterfaceFactory.syncInterfaces(emptyList())
            // Release the parked start; it resumes and attempts to register.
            gate.complete(Unit)

            // It must NOT register. Poll for a window long enough for the in-memory
            // RNode start to complete, and fail if the interface ever appears.
            val deadline = System.currentTimeMillis() + 3000
            var revived = false
            while (System.currentTimeMillis() < deadline) {
                if (NativeInterfaceFactory.currentInterfaces.any { it.name == "rnode-park" }) {
                    revived = true
                    break
                }
                Thread.sleep(50)
            }
            assertFalse(
                "in-flight RNode start must not revive the interface after it was disabled",
                revived,
            )
        } finally {
            NativeInterfaceFactory.shutdownAll()
            NativeInterfaceFactory.appContext = null
            NativeInterfaceFactory.rnodeHostBridge = null
        }
    }
}
