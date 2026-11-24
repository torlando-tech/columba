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
 *
 * Membership is by Python object identity, so the ids here are the only signal:
 * names never enter the decision, which is what keeps a replacement parent that
 * reuses the same `[[section]]` name from adopting the old parent's children.
 */
class OrphanedSubInterfaceTest {
    @Test
    fun `sub-interface whose parent object is live is kept`() {
        assertFalse(
            isOrphanedSubInterface(
                parentId = 42L,
                liveInterfaceIds = setOf(7L, 42L),
            ),
        )
    }

    @Test
    fun `sub-interface whose parent object is gone is orphaned`() {
        assertTrue(
            isOrphanedSubInterface(
                parentId = 42L,
                liveInterfaceIds = setOf(7L),
            ),
        )
    }

    @Test
    fun `replacement parent with the same name does not keep old children`() {
        // Old parent object id 42 was detached; a replacement interface with the
        // same configured name got a new identity. The child still points at 42,
        // so it must be treated as orphaned even though the name matches.
        val oldParentId = 42L
        val replacementParentId = 43L
        assertTrue(isOrphanedSubInterface(parentId = oldParentId, liveInterfaceIds = setOf(replacementParentId)))
    }

    @Test
    fun `top-level interface is never orphaned`() {
        assertFalse(isOrphanedSubInterface(parentId = null, liveInterfaceIds = emptySet()))
        assertFalse(
            isOrphanedSubInterface(
                parentId = null,
                liveInterfaceIds = setOf(7L, 42L),
            ),
        )
    }
}
