package com.lxmf.messenger.startup

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the `is_applying_config` SharedPreferences flag that prevents race conditions
 * during interface configuration changes.
 *
 * When InterfaceConfigManager applies configuration changes, it sets this flag to prevent
 * ColumbaApplication from auto-initializing the service with stale config. This manager
 * provides methods to check, clear, and detect stale flags.
 */
@Singleton
class ConfigApplyFlagManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            internal const val PREFS_NAME = "columba_prefs"
            internal const val KEY_IS_APPLYING_CONFIG = "is_applying_config"
        }

        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        /**
         * Check if a config apply operation is in progress.
         *
         * @return true if the is_applying_config flag is set
         */
        fun isApplyingConfig(): Boolean = prefs.getBoolean(KEY_IS_APPLYING_CONFIG, false)

        /**
         * Clear the config apply flag.
         * Called when a stale flag is detected or when config apply completes.
         */
        fun clearFlag() {
            prefs.edit().putBoolean(KEY_IS_APPLYING_CONFIG, false).apply()
        }

        /**
         * Set the config apply flag.
         * Called when starting a config apply operation.
         */
        fun setFlag() {
            prefs.edit().putBoolean(KEY_IS_APPLYING_CONFIG, true).apply()
        }

        /**
         * Check if the config flag is stale (service not actually configuring).
         *
         * A flag is considered stale if the service status indicates it's not running
         * or in an error state. This can happen if the app crashed during a previous
         * config apply operation.
         *
         * @param serviceStatus The current service status string
         * @return true if the flag should be cleared (is stale)
         */
        fun isStaleFlag(serviceStatus: String?): Boolean =
            serviceStatus == "SHUTDOWN" ||
                serviceStatus == null ||
                serviceStatus.startsWith("ERROR:")
    }
