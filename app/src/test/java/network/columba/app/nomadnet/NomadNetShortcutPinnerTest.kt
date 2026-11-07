package network.columba.app.nomadnet

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import network.columba.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Shortcut wiring: the launch intent is the contract between a pinned home-screen
 * icon and MainActivity's deep-link handling, and launcher-held shortcuts are not
 * covered by any in-app test path, so pin it here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NomadNetShortcutPinnerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val node = "9ce92808be498e9e05590ff27cbfdfe4"

    @Test
    fun `shortcut intent is an explicit view intent for MainActivity`() {
        val intent = NomadNetShortcutPinner.shortcutIntent(context, "nomadnetwork://$node:/page/index.mu")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("nomadnetwork://$node:/page/index.mu", intent.dataString)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `shortcut info carries the labels, icon and launch intent`() {
        val spec = NomadNetShortcuts.spec(node, "/page/index.mu", "Welcome node")
        val info = NomadNetShortcutPinner.buildShortcutInfo(context, spec)

        assertEquals(spec.id, info.id)
        assertEquals(node, spec.nodeHash)
        assertEquals("Welcome node", info.shortLabel.toString())
        assertTrue(info.longLabel.toString().startsWith("Welcome node"))
        assertNotNull(info.icon)
        assertEquals(spec.deepLink, info.intent?.dataString)
    }

    @Test
    fun `identicon bitmap renders at the requested size`() {
        val bitmap = NomadNetShortcutPinner.identiconBitmap(node, 96)
        assertEquals(96, bitmap.width)
        assertEquals(96, bitmap.height)
    }

    @Test
    fun `identicon bitmap tolerates malformed hashes`() {
        // Odd-length / non-hex input exercises the UTF-8 + padding fallback.
        listOf("abc", "", "zz-not-hex").forEach { hash ->
            val bitmap = NomadNetShortcutPinner.identiconBitmap(hash, 64)
            assertEquals(64, bitmap.width)
        }
    }

    @Test
    fun `requestPin skips the launcher call when pinning is unsupported`() {
        mockkStatic(ShortcutManagerCompat::class)
        try {
            every { ShortcutManagerCompat.isRequestPinShortcutSupported(context) } returns false

            val pinned = NomadNetShortcutPinner.requestPin(context, NomadNetShortcuts.spec(node, "/page/index.mu"))

            assertFalse(pinned)
            verify(exactly = 0) { ShortcutManagerCompat.requestPinShortcut(any(), any(), any()) }
        } finally {
            unmockkStatic(ShortcutManagerCompat::class)
        }
    }

    @Test
    fun `requestPin hands the page shortcut to the launcher`() {
        mockkStatic(ShortcutManagerCompat::class)
        try {
            every { ShortcutManagerCompat.isRequestPinShortcutSupported(context) } returns true
            every { ShortcutManagerCompat.requestPinShortcut(any(), any(), any()) } returns true

            val spec = NomadNetShortcuts.spec(node, "/page/forum/thread.mu", "A thread")
            val pinned = NomadNetShortcutPinner.requestPin(context, spec)

            assertTrue(pinned)
            val shortcut = slot<ShortcutInfoCompat>()
            verify {
                ShortcutManagerCompat.requestPinShortcut(context, capture(shortcut), null)
            }
            assertEquals(spec.id, shortcut.captured.id)
            assertEquals("A thread", shortcut.captured.shortLabel.toString())
            assertEquals(spec.deepLink, shortcut.captured.intent?.dataString)
        } finally {
            unmockkStatic(ShortcutManagerCompat::class)
        }
    }
}
