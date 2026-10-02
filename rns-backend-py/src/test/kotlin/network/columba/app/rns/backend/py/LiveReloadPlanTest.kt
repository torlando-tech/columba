package network.columba.app.rns.backend.py

import network.columba.app.rns.api.model.InterfaceConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [LiveReloadPlan] — the pure hot-reload diff that decides which
 * live RNS 1.5.5 operations (detach / attach / reload) to issue. Kept free of any
 * Python runtime so it runs on the JVM.
 *
 * The `toReload` bucket is the behaviour the pre-1.5.5 name-only diff could not
 * express: an interface present in both the live set and the desired set but whose
 * parameters changed since the last successful apply must be reloaded live, not
 * silently left untouched.
 */
class LiveReloadPlanTest {

    private fun auto(name: String = "Auto Discovery") =
        InterfaceConfig.AutoInterface(name = name)

    private fun tcpClient(name: String, host: String = "1.2.3.4", port: Int = 4242) =
        InterfaceConfig.TCPClient(name = name, targetHost = host, targetPort = port)

    private fun tcpServer(name: String, port: Int = 4242) =
        InterfaceConfig.TCPServer(name = name, listenPort = port)

    @Test
    fun `no change produces an empty plan`() {
        val auto = auto()
        val desired = linkedMapOf<String, InterfaceConfig>("Auto Discovery" to auto)
        val running = setOf("Auto Discovery")
        val baseline = desired.toMap()

        val plan = LiveReloadPlan.compute(running, desired, baseline)

        assertEquals(emptySet<String>(), plan.toDetach)
        assertEquals(emptySet<String>(), plan.toAttach)
        assertEquals(emptySet<String>(), plan.toReload)
    }

    @Test
    fun `new interface is attached`() {
        // Auto is live+desired+unchanged; TCP is desired but not live yet.
        val auto = auto()
        val tcp = tcpClient("Bridge")
        val desired = linkedMapOf<String, InterfaceConfig>(
            "Auto Discovery" to auto,
            "Bridge" to tcp,
        )
        val running = setOf("Auto Discovery")
        val baseline = mapOf("Auto Discovery" to auto)

        val plan = LiveReloadPlan.compute(running, desired, baseline)

        assertEquals(emptySet<String>(), plan.toDetach)
        assertEquals(setOf("Bridge"), plan.toAttach)
        assertEquals(emptySet<String>(), plan.toReload)
    }

    @Test
    fun `removed interface is detached`() {
        // Both live at baseline; user disabled Auto (it is no longer desired).
        val auto = auto()
        val tcp = tcpClient("Bridge")
        val desired = linkedMapOf<String, InterfaceConfig>("Bridge" to tcp)
        val running = setOf("Auto Discovery", "Bridge")
        val baseline = mapOf(
            "Auto Discovery" to auto,
            "Bridge" to tcp,
        )

        val plan = LiveReloadPlan.compute(running, desired, baseline)

        assertEquals(setOf("Auto Discovery"), plan.toDetach)
        assertEquals(emptySet<String>(), plan.toAttach)
        assertEquals(emptySet<String>(), plan.toReload)
    }

    @Test
    fun `edited interface is reloaded live`() {
        // Same name, but the TCP port changed since the last apply -> reload.
        val tcpBefore = tcpClient("Bridge", port = 4242)
        val tcpAfter = tcpClient("Bridge", port = 4243)
        val desired = linkedMapOf<String, InterfaceConfig>("Bridge" to tcpAfter)
        val running = setOf("Bridge")
        val baseline = mapOf("Bridge" to tcpBefore)

        val plan = LiveReloadPlan.compute(running, desired, baseline)

        assertEquals(emptySet<String>(), plan.toDetach)
        assertEquals(emptySet<String>(), plan.toAttach)
        assertEquals(setOf("Bridge"), plan.toReload)
    }

    @Test
    fun `edited interface with no baseline is not reloaded`() {
        // Present+desired but absent from the baseline: we cannot prove the live
        // parameters match, and reloading an unverified interface is churn, so it
        // is left to the next baseline-advancing apply.
        val tcp = tcpClient("Bridge")
        val desired = linkedMapOf<String, InterfaceConfig>("Bridge" to tcp)
        val running = setOf("Bridge")
        val baseline = emptyMap<String, InterfaceConfig>()

        val plan = LiveReloadPlan.compute(running, desired, baseline)

        assertEquals(emptySet<String>(), plan.toDetach)
        assertEquals(emptySet<String>(), plan.toAttach)
        assertEquals(emptySet<String>(), plan.toReload)
    }

    @Test
    fun `discovered interface live but not user-configured is not detached`() {
        // RNS auto-connected a discovered interface ("Peer 7788") that is live in
        // Transport.interfaces but was never user-configured, so it is in neither
        // the desired set nor the baseline. It must NOT be detached on a normal
        // reload (detaching it is RNS's job when the peer goes away).
        val auto = auto()
        val desired = linkedMapOf<String, InterfaceConfig>("Auto Discovery" to auto)
        val running = setOf("Auto Discovery", "Peer 7788")
        val baseline = mapOf("Auto Discovery" to auto)

        val plan = LiveReloadPlan.compute(running, desired, baseline)

        assertEquals(emptySet<String>(), plan.toDetach)
        assertEquals(emptySet<String>(), plan.toAttach)
        assertEquals(emptySet<String>(), plan.toReload)
    }

    @Test
    fun `mixed add remove and edit`() {
        // "Old" removed, "New" added, "Bridge" port edited, "Server" untouched.
        val server = tcpServer("Server", port = 5000)
        val bridgeBefore = tcpClient("Bridge", port = 4242)
        val bridgeAfter = tcpClient("Bridge", port = 4245)
        val desired = linkedMapOf<String, InterfaceConfig>(
            "Server" to server,
            "Bridge" to bridgeAfter,
            "New" to tcpClient("New", host = "5.6.7.8"),
        )
        val running = setOf("Old", "Server", "Bridge")
        val baseline = mapOf(
            "Old" to tcpClient("Old", host = "9.9.9.9"),
            "Server" to server,
            "Bridge" to bridgeBefore,
        )

        val plan = LiveReloadPlan.compute(running, desired, baseline)

        assertEquals(setOf("Old"), plan.toDetach)
        assertEquals(setOf("New"), plan.toAttach)
        assertEquals(setOf("Bridge"), plan.toReload)
    }
}
