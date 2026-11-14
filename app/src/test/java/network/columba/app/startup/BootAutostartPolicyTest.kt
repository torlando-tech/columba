package network.columba.app.startup

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.model.ReticulumConfig
import network.columba.app.rns.host.persistence.ReticulumConfigSnapshot
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every gate of the boot-autostart decision (issue #617): an explicit user shutdown and
 * the "start on boot" toggle win, and a never-configured install stays quiet instead of
 * spinning up a foreground service with an empty stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootAutostartPolicyTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        ReticulumConfigSnapshot.clear(context)
        localPrefs().edit().clear().apply()
    }

    @After
    fun tearDown() {
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
    fun `never-configured install does not autostart`() {
        assertFalse(BootAutostartPolicy.shouldStart(context, Intent.ACTION_BOOT_COMPLETED))
        assertFalse(BootAutostartPolicy.shouldStart(context, Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `configured install autostarts by default`() {
        writeSnapshot()

        assertTrue(BootAutostartPolicy.shouldStart(context, Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BootAutostartPolicy.shouldStart(context, Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `toggle off suppresses reboot autostart but not an app update`() {
        writeSnapshot()
        localPrefs()
            .edit()
            .putBoolean(SettingsRepository.KEY_AUTO_START_ON_BOOT, false)
            .apply()

        assertFalse(BootAutostartPolicy.shouldStart(context, Intent.ACTION_BOOT_COMPLETED))
        assertTrue(
            "a package update restores a working install; the reboot preference does not apply",
            BootAutostartPolicy.shouldStart(context, Intent.ACTION_MY_PACKAGE_REPLACED),
        )
    }

    @Test
    fun `user shutdown wins over the toggle`() {
        writeSnapshot()
        localPrefs()
            .edit()
            .putBoolean(SettingsRepository.KEY_AUTO_START_ON_BOOT, true)
            .putBoolean(SettingsRepository.KEY_IS_USER_SHUTDOWN, true)
            .apply()

        assertFalse(BootAutostartPolicy.shouldStart(context, Intent.ACTION_BOOT_COMPLETED))
        assertFalse(BootAutostartPolicy.shouldStart(context, Intent.ACTION_MY_PACKAGE_REPLACED))
    }
}
