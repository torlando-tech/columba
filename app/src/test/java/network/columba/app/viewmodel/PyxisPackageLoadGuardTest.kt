package network.columba.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PyxisPackageLoadGuardTest {
    @Test
    fun `new package selection invalidates all earlier loads`() {
        val guard = PyxisPackageLoadGuard()
        val first = guard.next()
        val second = guard.next()

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }
}
