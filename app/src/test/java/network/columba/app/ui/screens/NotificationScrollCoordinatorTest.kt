package network.columba.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior-level regression tests for [NotificationScrollCoordinator].
 *
 * These tests verify the three critical acceptance criteria for notification-entry
 * scroll-to-bottom behavior:
 *
 * (a) Notification entry **waits** for Paging/newest-content readiness before scrolling.
 * (b) The coordinator issues the scroll **exactly once** — later inbound items
 *     cannot re-trigger the notification scroll (anti-yank).
 * (c) Non-notification entry never triggers a forced scroll through this coordinator.
 */
class NotificationScrollCoordinatorTest {

    // ========================================================================
    // (a) Notification entry waits for readiness
    // ========================================================================

    @Test
    fun `notification entry does NOT scroll before content is ready`() {
        // Given - notification entry, but messages not yet loaded
        val coordinator = NotificationScrollCoordinator(fromNotification = true)

        // When - shouldScroll called before content ready
        val result = coordinator.shouldScroll()

        // Then - no scroll until content is ready
        assertFalse(
            "shouldScroll must return false before onContentReady() — " +
                "otherwise the list scrolls to an empty index and crashes",
            result,
        )
    }

    @Test
    fun `notification entry scrolls after content becomes ready`() {
        // Given - notification entry
        val coordinator = NotificationScrollCoordinator(fromNotification = true)

        // When - content becomes ready
        coordinator.onContentReady()

        // Then - first shouldScroll returns true
        assertTrue(
            "shouldScroll must return true once content is ready for notification entry",
            coordinator.shouldScroll(),
        )
    }

    @Test
    fun `notification entry scrolls only after both conditions - fromNotification AND content ready`() {
        // Given - notification entry
        val coordinator = NotificationScrollCoordinator(fromNotification = true)

        // When - shouldScroll called before content
        assertFalse(coordinator.shouldScroll())

        // And - content ready
        coordinator.onContentReady()

        // Then - shouldScroll returns true
        assertTrue(coordinator.shouldScroll())
    }

    // ========================================================================
    // (b) One-shot: later inbound items cannot re-trigger notification scroll
    // ========================================================================

    @Test
    fun `notification entry issues scroll exactly once - second call returns false`() {
        // Given - notification entry, content ready
        val coordinator = NotificationScrollCoordinator(fromNotification = true)
        coordinator.onContentReady()

        // When - first shouldScroll
        val first = coordinator.shouldScroll()

        // And - second shouldScroll (simulating a new inbound message)
        val second = coordinator.shouldScroll()

        // Then - only the first call triggers a scroll
        assertTrue("First shouldScroll must return true", first)
        assertFalse(
            "Second shouldScroll must return false — " +
                "otherwise later inbound messages re-yank the user from history",
            second,
        )
    }

    @Test
    fun `notification entry does not re-scroll after multiple content updates`() {
        // Given - notification entry, content ready
        val coordinator = NotificationScrollCoordinator(fromNotification = true)
        coordinator.onContentReady()

        // When - first scroll issued
        assertTrue(coordinator.shouldScroll())

        // And - simulate multiple new inbound messages (repeated content ready calls)
        coordinator.onContentReady()
        coordinator.onContentReady()
        coordinator.onContentReady()

        // Then - still no further scrolls
        assertFalse(coordinator.shouldScroll())
        assertFalse(coordinator.shouldScroll())
        assertFalse(coordinator.shouldScroll())
    }

    @Test
    fun `notification entry does not re-scroll even after many shouldScroll calls`() {
        val coordinator = NotificationScrollCoordinator(fromNotification = true)
        coordinator.onContentReady()

        // Issue the first scroll
        assertTrue(coordinator.shouldScroll())

        // 100 more calls — all must be false
        for (i in 1..100) {
            assertFalse(
                "Call #${i + 1} should not trigger another scroll",
                coordinator.shouldScroll(),
            )
        }
    }

    // ========================================================================
    // (c) Non-notification entry does NOT issue a forced scroll
    // ========================================================================

    @Test
    fun `non-notification entry never triggers scroll even when content is ready`() {
        // Given - ordinary navigation (not from notification)
        val coordinator = NotificationScrollCoordinator(fromNotification = false)

        // When - content becomes ready
        coordinator.onContentReady()

        // Then - shouldScroll always returns false
        assertFalse(
            "Non-notification entry must never trigger a forced scroll — " +
                "this would interrupt a user actively reading history",
            coordinator.shouldScroll(),
        )
    }

    @Test
    fun `non-notification entry never triggers scroll even after repeated shouldScroll calls`() {
        val coordinator = NotificationScrollCoordinator(fromNotification = false)
        coordinator.onContentReady()

        for (i in 1..10) {
            assertFalse(
                "Call #$i: non-notification entry must never scroll",
                coordinator.shouldScroll(),
            )
        }
    }

    @Test
    fun `non-notification entry does not scroll even before content ready`() {
        val coordinator = NotificationScrollCoordinator(fromNotification = false)

        assertFalse(
            "Non-notification entry must not scroll before content is ready",
            coordinator.shouldScroll(),
        )
    }

    // ========================================================================
    // State inspection for setup verification
    // ========================================================================

    @Test
    fun `isFromNotification returns correct value for notification entry`() {
        val coordinator = NotificationScrollCoordinator(fromNotification = true)
        assertTrue(coordinator.isFromNotification())
    }

    @Test
    fun `isFromNotification returns correct value for ordinary entry`() {
        val coordinator = NotificationScrollCoordinator(fromNotification = false)
        assertFalse(coordinator.isFromNotification())
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    fun `multiple onContentReady calls before first shouldScroll still only scrolls once`() {
        val coordinator = NotificationScrollCoordinator(fromNotification = true)

        // Content becomes ready multiple times (e.g., Paging refreshes)
        coordinator.onContentReady()
        coordinator.onContentReady()
        coordinator.onContentReady()

        // Should still only scroll once
        assertTrue(coordinator.shouldScroll())
        assertFalse(coordinator.shouldScroll())
    }

    @Test
    fun `shouldScroll before and after onContentReady for notification entry`() {
        val coordinator = NotificationScrollCoordinator(fromNotification = true)

        // Before content ready - no scroll
        assertFalse("Must not scroll before content", coordinator.shouldScroll())

        // Content ready
        coordinator.onContentReady()

        // Now scroll once
        assertTrue("Must scroll after content ready", coordinator.shouldScroll())

        // Still no more scrolls
        assertFalse("Must not scroll again", coordinator.shouldScroll())
    }
}
