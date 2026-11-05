package network.columba.app.util

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.activity.result.ActivityResultLauncher
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DocumentsUI-less devices (Amlogic TV boxes, Sentry COLUMBA-EG) resolve nothing
 * for SAF picker actions, so [ActivityResultLauncher.launch] throws
 * [ActivityNotFoundException] and crashed the app. Every document picker call
 * site now goes through [launchSafely]; it must report `false` (so the caller can
 * show its fallback) and must not swallow anything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PickerLauncherTest {
    private fun launcher(): ActivityResultLauncher<String> = mockk()

    @Test
    fun `returns true when the picker accepts the input`() {
        val launcher = launcher()
        every { launcher.launch(any()) } just Runs

        assertTrue(launcher.launchSafely("*/*"))
        verify { launcher.launch("*/*") }
    }

    @Test
    fun `returns false when no activity can handle the picker`() {
        val launcher = launcher()
        every { launcher.launch(any()) } throws ActivityNotFoundException("No Activity found")

        assertFalse(launcher.launchSafely("columba_export.columba"))
    }

    @Test
    fun `propagates unexpected launcher failures`() {
        val launcher = launcher()
        every { launcher.launch(any()) } throws IllegalStateException("Lifecycle is not started")

        assertThrows(IllegalStateException::class.java) {
            launcher.launchSafely("*/*")
        }
    }
}
