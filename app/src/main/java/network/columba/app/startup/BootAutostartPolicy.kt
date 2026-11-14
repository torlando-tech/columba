package network.columba.app.startup

import android.content.Context
import android.content.Intent
import android.util.Log
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.host.persistence.ReticulumConfigSnapshot

/**
 * Decides whether a boot/system event should start the Reticulum foreground
 * service (issue #617: messages must arrive after a reboot without the user
 * launching Columba first).
 *
 * Kept separate from [BootAutostartReceiver] so the decision is unit-testable
 * and so every gate is documented in one place. Deliberately dependency-free:
 * it runs before DataStore, Hilt, or any UI exists, which is why the autostart
 * preference is read from the plain [SettingsRepository.PREFS_NAME_LOCAL] file
 * the app mirrors it into.
 */
object BootAutostartPolicy {
    private const val TAG = "BootAutostartPolicy"

    /**
     * @param action the broadcast action; either [Intent.ACTION_BOOT_COMPLETED]
     *   or [Intent.ACTION_MY_PACKAGE_REPLACED].
     * @return true when the service should be started by the caller.
     */
    fun shouldStart(
        context: Context,
        action: String,
    ): Boolean {
        val prefs =
            context.getSharedPreferences(SettingsRepository.PREFS_NAME_LOCAL, Context.MODE_PRIVATE)

        // An explicit shutdown (Settings → Stop, Debug → Stop) expresses intent that
        // outlives the reboot; do not override it silently.
        val userShutdown = prefs.getBoolean(SettingsRepository.KEY_IS_USER_SHUTDOWN, false)
        if (userShutdown) Log.i(TAG, "Not autostarting — user shut the service down")

        // MY_PACKAGE_REPLACED restores a working install after an update; a factory
        // reset never emits it, so no need to apply the user's reboot preference there.
        val rebootPreferenceAllows =
            action != Intent.ACTION_BOOT_COMPLETED ||
                prefs.getBoolean(SettingsRepository.KEY_AUTO_START_ON_BOOT, true)
        if (!rebootPreferenceAllows) Log.i(TAG, "Not autostarting — user disabled start-on-boot")

        // Never-configured install (fresh install, no successful initialize yet):
        // starting here would spin up an FGS with nothing to run and no identity.
        val configured = ReticulumConfigSnapshot.read(context)?.configWithoutKey != null
        if (!configured) Log.i(TAG, "Not autostarting — no config snapshot yet (never configured)")

        return !userShutdown && rebootPreferenceAllows && configured
    }
}
