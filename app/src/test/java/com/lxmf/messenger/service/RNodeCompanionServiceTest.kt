// AssociationInfo is a final Android framework class; relaxed mocking required
@file:Suppress("NoRelaxedMocks")

package com.lxmf.messenger.service

import android.app.Application
import android.companion.AssociationInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for RNodeCompanionService API level guards.
 *
 * Regression test for COLUMBA-2D: AssociationInfo.associatedDevice was introduced in API 34
 * (UPSIDE_DOWN_CAKE), but was previously guarded by TIRAMISU (API 33), causing a fatal
 * NoSuchMethodError on API 33 devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RNodeCompanionServiceTest {
    @Test
    @Config(sdk = [33])
    fun `onDeviceAppeared does not crash on API 33 - COLUMBA-2D regression`() {
        val service =
            Robolectric
                .buildService(RNodeCompanionService::class.java)
                .create()
                .get()

        val mockAssociationInfo = mockk<AssociationInfo>(relaxed = true)
        every { mockAssociationInfo.displayName } returns "Test RNode"

        // Before the fix, this crashed with NoSuchMethodError because
        // associatedDevice (API 34) was guarded by TIRAMISU (API 33)
        service.onDeviceAppeared(mockAssociationInfo)
    }
}
