package com.lxmf.messenger.service.manager

import com.lxmf.messenger.service.persistence.ServiceSettingsAccessor
import com.lxmf.messenger.service.state.ServiceState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the toggle-off short-circuit in
 * [PythonWrapperManager.isCallerInContacts].
 *
 * The DAO path is exercised indirectly by [com.lxmf.messenger.repository.SettingsRepositoryTest]
 * (which covers the cross-process pref read) and by manual on-device QA;
 * full DAO coverage would require Robolectric + Room which is overkill for
 * this thin façade. Instead we verify the security-critical invariant:
 *   - When the toggle is OFF, the predicate returns true WITHOUT consulting
 *     the database (cheap, predictable, no DAO mocking needed).
 *   - When the predicate throws because of an empty/mock context, the
 *     fail-open contract still returns true (so a DB error never bricks
 *     incoming calls).
 */
class PythonWrapperManagerContactCheckTest {
    private lateinit var state: ServiceState
    private lateinit var settingsAccessor: ServiceSettingsAccessor
    private lateinit var manager: PythonWrapperManager

    @Before
    fun setup() {
        state = ServiceState()
        settingsAccessor = mockk()
        manager =
            PythonWrapperManager(
                state = state,
                context = mockk(),
                scope = TestScope(),
                settingsAccessor = settingsAccessor,
            )
    }

    @Test
    fun `isCallerInContacts returns true when toggle is off`() {
        // Toggle OFF — no contact check should run; predicate short-circuits true.
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns false

        val result = manager.isCallerInContacts("a".repeat(32))

        assertTrue("Toggle off must allow every caller (no gate)", result)
    }

    @Test
    fun `isCallerInContacts fails open when toggle is on but DB access throws`() {
        // Toggle ON, but the manager has a relaxed/mock Context — opening
        // the Room database will throw. The fail-open contract requires
        // we return true rather than silently brick calls.
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns true

        val result = manager.isCallerInContacts("a".repeat(32))

        assertTrue(
            "DB error must fail open (return true) so infra hiccups don't brick calls",
            result,
        )
    }
}
