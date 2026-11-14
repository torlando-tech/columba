package network.columba.app.startup

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.model.ReticulumConfig
import network.columba.app.rns.host.ReticulumService
import network.columba.app.rns.host.persistence.ReticulumConfigSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The adapter side of boot autostart: the receiver must start `ReticulumService` with the
 * documented action when the policy allows it, and must not crash a booting device when the
 * platform refuses the background start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootAutostartReceiverTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val started = mutableListOf<Intent>()

    @Before
    fun setup() {
        ReticulumConfigSnapshot.clear(context)
        localPrefs().edit().clear().apply()
        started.clear()
        // Unconfined so the background hop in onReceive completes inside the call.
        BootAutostartReceiver.dispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        BootAutostartReceiver.startForegroundService = { _, intent -> started += intent }
    }

    @After
    fun tearDown() {
        BootAutostartReceiver.startForegroundService = { ctx, intent ->
            androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
        }
        BootAutostartReceiver.dispatcher = kotlinx.coroutines.Dispatchers.IO
        ReticulumConfigSnapshot.clear(context)
        localPrefs().edit().clear().apply()
    }

    @Suppress("DEPRECATION")
    private fun localPrefs() =
        context.getSharedPreferences(SettingsRepository.PREFS_NAME_LOCAL, Context.MODE_PRIVATE)

    private fun writeSnapshot() {
        ReticulumConfigSnapshot.write(
            context,
            ReticulumConfig(
                storagePath = "/data/data/test/files/reticulum",
                enabledInterfaces = emptyList(),
                displayName = "Test Node",
                enableTransport = false,
                shareInstanceHosting = false,
                deliveryIdentityKey = null,
            ),
            identityHashHex = "abcd1234",
        )
    }

    @Test
    fun `boot starts the service with ACTION_START`() {
        writeSnapshot()

        BootAutostartReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val intent = started.single()
        assertEquals(ReticulumService.ACTION_START, intent.action)
        assertEquals(ReticulumService::class.java.name, intent.component?.className)
    }

    @Test
    fun `user shutdown or no snapshot starts nothing`() {
        BootAutostartReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue("never configured", started.isEmpty())

        writeSnapshot()
        localPrefs()
            .edit()
            .putBoolean(SettingsRepository.KEY_IS_USER_SHUTDOWN, true)
            .apply()
        BootAutostartReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue("user shut the service down", started.isEmpty())
    }

    @Test
    fun `unrelated broadcasts are ignored`() {
        writeSnapshot()

        BootAutostartReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))

        assertTrue(started.isEmpty())
    }

    @Test
    fun `platform refusing the background start does not crash the boot`() {
        writeSnapshot()
        var attempts = 0
        BootAutostartReceiver.startForegroundService = { _, _ ->
            attempts += 1
            error("startForegroundService not allowed")
        }

        // The exception must not escape: a device that just rebooted gets no crash dialog.
        BootAutostartReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals("the start was attempted exactly once", 1, attempts)
        assertTrue(started.isEmpty())
    }
}
