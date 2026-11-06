package network.columba.app.data.config

import android.util.Log
import network.columba.app.rns.api.model.InterfaceConfig
import java.io.File

/**
 * Legacy entry point for importing interfaces from the on-disk Reticulum
 * config file during first-run database creation.
 *
 * The parsing itself lives in [InterfaceConfigCodec] so the same rules serve
 * config-file import, clipboard paste, and config sharing. This wrapper keeps
 * the historical failure mode (log + empty list) for a corrupt config file so
 * database creation can fall back to the default interface.
 */
object ConfigFileParser {
    private const val TAG = "ConfigFileParser"

    /**
     * Parse a Reticulum config file and extract enabled interface configs from
     * its `[interfaces]` section.
     */
    fun parseConfigFile(configFile: File): List<InterfaceConfig> =
        try {
            InterfaceConfigCodec.parseConfigFile(configFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing config file", e)
            emptyList()
        }
}
