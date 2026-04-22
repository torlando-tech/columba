package network.columba.app.ui.components

import network.columba.app.reticulum.model.NetworkStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeBannerTest {
    @Test
    fun `shouldShowOfflineBanner returns true for SHUTDOWN`() {
        assertTrue(shouldShowOfflineBanner(NetworkStatus.SHUTDOWN))
    }

    @Test
    fun `shouldShowOfflineBanner returns true for ERROR`() {
        assertTrue(shouldShowOfflineBanner(NetworkStatus.ERROR("test error")))
    }

    @Test
    fun `shouldShowOfflineBanner returns false for READY`() {
        assertFalse(shouldShowOfflineBanner(NetworkStatus.READY))
    }

    @Test
    fun `shouldShowOfflineBanner returns false for CONNECTING`() {
        assertFalse(shouldShowOfflineBanner(NetworkStatus.CONNECTING))
    }

    @Test
    fun `shouldShowOfflineBanner returns false for INITIALIZING`() {
        assertFalse(shouldShowOfflineBanner(NetworkStatus.INITIALIZING))
    }
}
