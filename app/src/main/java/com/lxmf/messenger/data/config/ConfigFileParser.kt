package com.lxmf.messenger.data.config

import android.util.Log
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationConstants
import com.lxmf.messenger.util.validation.ValidationResult
import java.io.File

/**
 * Parser for Reticulum INI-style configuration files.
 *
 * Extracts interface configurations from the [interfaces] section and converts them
 * to InterfaceConfig sealed class instances.
 */
object ConfigFileParser {
    /**
     * Parse a Reticulum config file and extract interface configurations.
     *
     * @param configFile The config file to parse
     * @return List of InterfaceConfig instances
     * @throws IllegalArgumentException if file is too large
     */
    fun parseConfigFile(configFile: File): List<InterfaceConfig> {
        if (!configFile.exists() || !configFile.isFile) {
            Log.w(TAG, "Config file does not exist: ${configFile.absolutePath}")
            return emptyList()
        }

        // Check file size limit
        if (configFile.length() > ValidationConstants.MAX_CONFIG_FILE_SIZE) {
            Log.e(TAG, "Config file too large: ${configFile.length()} bytes (max: ${ValidationConstants.MAX_CONFIG_FILE_SIZE})")
            throw IllegalArgumentException("Config file exceeds maximum size of ${ValidationConstants.MAX_CONFIG_FILE_SIZE} bytes")
        }

        return try {
            val lines = configFile.readLines()
            parseConfigLines(lines)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing config file", e)
            emptyList()
        }
    }

    /**
     * Parse config file lines and extract interfaces.
     * Validates parameter names and values.
     *
     * @throws IllegalArgumentException if too many interfaces
     */
    private fun parseConfigLines(lines: List<String>): List<InterfaceConfig> {
        val interfaces = mutableListOf<InterfaceConfig>()
        var inInterfacesSection = false
        var currentInterfaceName: String? = null
        val currentInterfaceParams = mutableMapOf<String, String>()

        for (line in lines) {
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue
            }

            // Check if entering [interfaces] section
            if (trimmed == "[interfaces]") {
                inInterfacesSection = true
                continue
            }

            // Check if entering a different section (leaving interfaces)
            if (trimmed.startsWith("[") && !trimmed.startsWith("[[")) {
                // Save previous interface if any
                if (currentInterfaceName != null) {
                    parseInterface(currentInterfaceName, currentInterfaceParams)?.let {
                        // Check interface count limit
                        if (interfaces.size >= ValidationConstants.MAX_INTERFACE_COUNT) {
                            Log.e(TAG, "Too many interfaces in config file (max: ${ValidationConstants.MAX_INTERFACE_COUNT})")
                            throw IllegalArgumentException(
                                "Config file contains too many interfaces (max: ${ValidationConstants.MAX_INTERFACE_COUNT})",
                            )
                        }
                        interfaces.add(it)
                    }
                    currentInterfaceName = null
                    currentInterfaceParams.clear()
                }
                inInterfacesSection = false
                continue
            }

            if (!inInterfacesSection) {
                continue
            }

            // Check for interface subsection [[Interface Name]]
            if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
                // Save previous interface if any
                if (currentInterfaceName != null) {
                    parseInterface(currentInterfaceName, currentInterfaceParams)?.let {
                        // Check interface count limit
                        if (interfaces.size >= ValidationConstants.MAX_INTERFACE_COUNT) {
                            Log.e(TAG, "Too many interfaces in config file (max: ${ValidationConstants.MAX_INTERFACE_COUNT})")
                            throw IllegalArgumentException(
                                "Config file contains too many interfaces (max: ${ValidationConstants.MAX_INTERFACE_COUNT})",
                            )
                        }
                        interfaces.add(it)
                    }
                    currentInterfaceParams.clear()
                }

                // Extract new interface name
                currentInterfaceName = trimmed.substring(2, trimmed.length - 2).trim()
                continue
            }

            // Parse parameter line: key = value
            if (currentInterfaceName != null && trimmed.contains("=")) {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()

                    // Validate parameter name (whitelist check)
                    if (key !in ValidationConstants.ALLOWED_INTERFACE_PARAMS) {
                        Log.w(TAG, "Unknown config parameter ignored: $key")
                        continue
                    }

                    // Validate parameter value based on type
                    val validatedValue =
                        when (key) {
                            "target_port", "listen_port", "forward_port", "discovery_port", "data_port" -> {
                                when (val portResult = InputValidator.validatePort(value)) {
                                    is ValidationResult.Error -> {
                                        Log.w(TAG, "Invalid port value for $key: $value - ${portResult.message}")
                                        continue
                                    }
                                    is ValidationResult.Success -> value
                                }
                            }
                            "target_host", "listen_ip", "forward_ip" -> {
                                when (val hostResult = InputValidator.validateHostname(value)) {
                                    is ValidationResult.Error -> {
                                        Log.w(TAG, "Invalid hostname/IP for $key: $value - ${hostResult.message}")
                                        continue
                                    }
                                    is ValidationResult.Success -> value
                                }
                            }
                            "device_name" -> {
                                when (val nameResult = InputValidator.validateDeviceName(value)) {
                                    is ValidationResult.Error -> {
                                        Log.w(TAG, "Invalid device name: $value - ${nameResult.message}")
                                        continue
                                    }
                                    is ValidationResult.Success -> value
                                }
                            }
                            else -> value
                        }

                    currentInterfaceParams[key] = validatedValue
                }
            }
        }

        // Don't forget the last interface
        if (currentInterfaceName != null) {
            parseInterface(currentInterfaceName, currentInterfaceParams)?.let {
                // Check interface count limit
                if (interfaces.size >= ValidationConstants.MAX_INTERFACE_COUNT) {
                    Log.e(TAG, "Too many interfaces in config file (max: ${ValidationConstants.MAX_INTERFACE_COUNT})")
                    throw IllegalArgumentException(
                        "Config file contains too many interfaces (max: ${ValidationConstants.MAX_INTERFACE_COUNT})",
                    )
                }
                interfaces.add(it)
            }
        }

        return interfaces
    }

    /**
     * Convert parsed interface parameters to InterfaceConfig.
     */
    private fun parseInterface(
        name: String,
        params: Map<String, String>,
    ): InterfaceConfig? {
        val type = params["type"] ?: return null
        val enabled = params["enabled"]?.lowercase() == "yes"

        if (!enabled) {
            Log.d(TAG, "Skipping disabled interface: $name")
            return null
        }

        return when (type) {
            "AutoInterface" -> parseAutoInterface(name, params)
            "TCPClientInterface" -> parseTCPClientInterface(name, params)
            "RNodeInterface" -> parseRNodeInterface(name, params)
            else -> {
                Log.w(TAG, "Unknown interface type: $type for interface: $name")
                null
            }
        }
    }

    private fun parseAutoInterface(
        name: String,
        params: Map<String, String>,
    ): InterfaceConfig.AutoInterface {
        return InterfaceConfig.AutoInterface(
            name = name,
            enabled = true,
            groupId = params["group_id"] ?: "",
            discoveryScope = params["discovery_scope"] ?: "link",
            discoveryPort = params["discovery_port"]?.toIntOrNull() ?: 48555,
            dataPort = params["data_port"]?.toIntOrNull() ?: 49555,
            mode = params["mode"] ?: "full",
        )
    }

    private fun parseTCPClientInterface(
        name: String,
        params: Map<String, String>,
    ): InterfaceConfig.TCPClient? {
        val targetHost = params["target_host"] ?: return null
        val targetPort = params["target_port"]?.toIntOrNull() ?: return null

        return InterfaceConfig.TCPClient(
            name = name,
            enabled = true,
            targetHost = targetHost,
            targetPort = targetPort,
            mode = params["mode"] ?: "full",
        )
    }

    private fun parseRNodeInterface(
        name: String,
        params: Map<String, String>,
    ): InterfaceConfig.RNode? {
        val port = params["port"] ?: return null

        return InterfaceConfig.RNode(
            name = name,
            enabled = true,
            port = port,
            frequency = params["frequency"]?.toLongOrNull() ?: 915000000,
            bandwidth = params["bandwidth"]?.toIntOrNull() ?: 125000,
            txPower = params["txpower"]?.toIntOrNull() ?: 7,
            spreadingFactor = params["spreadingfactor"]?.toIntOrNull() ?: 7,
            codingRate = params["codingrate"]?.toIntOrNull() ?: 5,
            mode = params["mode"] ?: "full",
        )
    }

    private const val TAG = "ConfigFileParser"
}
