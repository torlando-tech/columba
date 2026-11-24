package network.columba.app.rns.backend.py

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [isOrphanedSubInterface] — the rule
 * `PythonRnsTransportAdmin.collectInterfaces()` uses to detach spawned
 * sub-interfaces whose parent interface is no longer live.
 *
 * The failure mode it guards: after a hot-reload/teardown race, a spawned BLE
 * peer interface outlives its parent and shows up in the UI as an "online"
 * peer underneath an interface that is not running.
 */
class OrphanedSubInterfaceTest {
    @Test
    fun `sub-interface whose parent is live is kept`() {
        assertFalse(
            isOrphanedSubInterface(
                parentName = "Bluetooth LE",
                liveInterfaceNames = setOf("Bluetooth LE", "RNode E517 BLE"),
            ),
        )
    }

    @Test
    fun `sub-interface whose parent is gone is orphaned`() {
        assertTrue(
            isOrphanedSubInterface(
                parentName = "Bluetooth LE",
                liveInterfaceNames = setOf("RNode E517 BLE"),
            ),
        )
    }

    @Test
    fun `top-level interface is never orphaned`() {
        assertFalse(isOrphanedSubInterface(parentName = null, liveInterfaceNames = emptySet()))
        assertFalse(
            isOrphanedSubInterface(
                parentName = null,
                liveInterfaceNames = setOf("RNode E517 BLE", "Bluetooth LE"),
            ),
        )
    }
}
