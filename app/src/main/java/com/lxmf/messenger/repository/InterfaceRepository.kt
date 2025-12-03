package com.lxmf.messenger.repository

import android.util.Log
import com.lxmf.messenger.data.database.dao.InterfaceDao
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.toJsonString
import com.lxmf.messenger.reticulum.model.typeName
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing Reticulum network interface configurations.
 * Handles CRUD operations and conversion between InterfaceEntity and InterfaceConfig.
 */
@Singleton
class InterfaceRepository
    @Inject
    constructor(
        private val interfaceDao: InterfaceDao,
    ) {
        /**
         * Get all configured interfaces as InterfaceConfig objects.
         * Corrupted interfaces are logged and skipped.
         */
        val allInterfaces: Flow<List<InterfaceConfig>> =
            interfaceDao.getAllInterfaces()
                .map { entities -> entities.mapNotNull { safeEntityToConfig(it) } }

        /**
         * Get all enabled interfaces as InterfaceConfig objects.
         * Corrupted interfaces are logged and skipped.
         */
        val enabledInterfaces: Flow<List<InterfaceConfig>> =
            interfaceDao.getEnabledInterfaces()
                .map { entities -> entities.mapNotNull { safeEntityToConfig(it) } }

        /**
         * Safely convert an entity to config, returning null on error.
         */
        private fun safeEntityToConfig(entity: InterfaceEntity): InterfaceConfig? {
            return try {
                entityToConfig(entity)
            } catch (e: Exception) {
                Log.e(TAG, "Skipping corrupted interface '${entity.name}': ${e.message}")
                null
            }
        }

        /**
         * Get all interface entities (for UI display).
         */
        val allInterfaceEntities: Flow<List<InterfaceEntity>> = interfaceDao.getAllInterfaces()

        /**
         * Get enabled interface count.
         */
        val enabledInterfaceCount: Flow<Int> = interfaceDao.getEnabledInterfaceCount()

        /**
         * Get total interface count.
         */
        val totalInterfaceCount: Flow<Int> = interfaceDao.getTotalInterfaceCount()

        /**
         * Get a specific interface by ID.
         */
        fun getInterfaceById(id: Long): Flow<InterfaceEntity?> {
            return interfaceDao.getInterfaceById(id)
        }

        /**
         * Insert a new interface configuration.
         *
         * @param config The interface configuration to insert
         * @return The ID of the newly inserted interface
         */
        suspend fun insertInterface(config: InterfaceConfig): Long {
            val entity = configToEntity(config)
            return interfaceDao.insertInterface(entity)
        }

        /**
         * Update an existing interface configuration.
         *
         * @param id The ID of the interface to update
         * @param config The updated interface configuration
         */
        suspend fun updateInterface(
            id: Long,
            config: InterfaceConfig,
        ) {
            val entity = configToEntity(config).copy(id = id)
            interfaceDao.updateInterface(entity)
        }

        /**
         * Update an interface entity directly (for UI operations).
         */
        suspend fun updateInterfaceEntity(entity: InterfaceEntity) {
            interfaceDao.updateInterface(entity)
        }

        /**
         * Delete an interface by ID.
         */
        suspend fun deleteInterface(id: Long) {
            interfaceDao.deleteInterface(id)
        }

        /**
         * Toggle the enabled state of an interface.
         */
        suspend fun toggleInterfaceEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            interfaceDao.setInterfaceEnabled(id, enabled)
        }

        /**
         * Delete all interfaces (useful for testing).
         */
        suspend fun deleteAllInterfaces() {
            interfaceDao.deleteAllInterfaces()
        }

        /**
         * Convert InterfaceConfig to InterfaceEntity for database storage.
         */
        private fun configToEntity(
            config: InterfaceConfig,
            displayOrder: Int = 0,
        ): InterfaceEntity =
            InterfaceEntity(
                name = config.name,
                type = config.typeName,
                enabled = config.enabled,
                configJson = config.toJsonString(),
                displayOrder = displayOrder,
            )

        /**
         * Convert InterfaceEntity to InterfaceConfig for use in application logic.
         * Validates all parsed values to ensure database integrity.
         *
         * @throws IllegalStateException if JSON is corrupted or invalid
         */
        fun entityToConfig(entity: InterfaceEntity): InterfaceConfig {
            return try {
                val json = JSONObject(entity.configJson)

                when (entity.type) {
                    "AutoInterface" -> {
                        // Ports are optional - null means use RNS defaults
                        val discoveryPort = if (json.has("discovery_port")) {
                            val port = json.getInt("discovery_port")
                            if (port !in 1..65535) {
                                Log.e(TAG, "Invalid discovery port in database: $port")
                                error("Invalid discovery port: $port")
                            }
                            port
                        } else {
                            null
                        }

                        val dataPort = if (json.has("data_port")) {
                            val port = json.getInt("data_port")
                            if (port !in 1..65535) {
                                Log.e(TAG, "Invalid data port in database: $port")
                                error("Invalid data port: $port")
                            }
                            port
                        } else {
                            null
                        }

                        InterfaceConfig.AutoInterface(
                            name = entity.name,
                            enabled = entity.enabled,
                            groupId = json.optString("group_id", ""),
                            discoveryScope = json.optString("discovery_scope", "link"),
                            discoveryPort = discoveryPort,
                            dataPort = dataPort,
                            mode = json.optString("mode", "full"),
                        )
                    }

                    "TCPClient" -> {
                        val targetHost = json.getString("target_host")
                        val targetPort = json.getInt("target_port")

                        // Validate hostname
                        when (val hostResult = InputValidator.validateHostname(targetHost)) {
                            is ValidationResult.Error -> {
                                Log.e(TAG, "Invalid target host in database: $targetHost - ${hostResult.message}")
                                error("Invalid target host: $targetHost")
                            }
                            else -> {}
                        }

                        // Validate port
                        if (targetPort !in 1..65535) {
                            Log.e(TAG, "Invalid target port in database: $targetPort")
                            error("Invalid target port: $targetPort")
                        }

                        InterfaceConfig.TCPClient(
                            name = entity.name,
                            enabled = entity.enabled,
                            targetHost = targetHost,
                            targetPort = targetPort,
                            kissFraming = json.optBoolean("kiss_framing", false),
                            mode = json.optString("mode", "full"),
                            networkName = json.optString("network_name", "").ifEmpty { null },
                            passphrase = json.optString("passphrase", "").ifEmpty { null },
                        )
                    }

                    "RNode" -> {
                        val targetDeviceName = json.getString("target_device_name")

                        // Validate device name (basic check - should be non-empty)
                        if (targetDeviceName.isBlank()) {
                            Log.e(TAG, "Empty RNode target device name in database")
                            error("Empty RNode target device name")
                        }

                        InterfaceConfig.RNode(
                            name = entity.name,
                            enabled = entity.enabled,
                            targetDeviceName = targetDeviceName,
                            connectionMode = json.optString("connection_mode", "classic"),
                            frequency = json.optLong("frequency", 915000000),
                            bandwidth = json.optInt("bandwidth", 125000),
                            txPower = json.optInt("tx_power", 7),
                            spreadingFactor = json.optInt("spreading_factor", 7),
                            codingRate = json.optInt("coding_rate", 5),
                            stAlock = if (json.has("st_alock")) json.getDouble("st_alock") else null,
                            ltAlock = if (json.has("lt_alock")) json.getDouble("lt_alock") else null,
                            mode = json.optString("mode", "full"),
                        )
                    }

                    "UDP" -> {
                        val listenIp = json.optString("listen_ip", "0.0.0.0")
                        val listenPort = json.optInt("listen_port", 4242)
                        val forwardIp = json.optString("forward_ip", "255.255.255.255")
                        val forwardPort = json.optInt("forward_port", 4242)

                        // Validate IPs
                        when (val listenIpResult = InputValidator.validateHostname(listenIp)) {
                            is ValidationResult.Error -> {
                                Log.e(TAG, "Invalid listen IP in database: $listenIp - ${listenIpResult.message}")
                                error("Invalid listen IP: $listenIp")
                            }
                            else -> {}
                        }

                        when (val forwardIpResult = InputValidator.validateHostname(forwardIp)) {
                            is ValidationResult.Error -> {
                                Log.e(TAG, "Invalid forward IP in database: $forwardIp - ${forwardIpResult.message}")
                                error("Invalid forward IP: $forwardIp")
                            }
                            else -> {}
                        }

                        // Validate ports
                        if (listenPort !in 1..65535) {
                            Log.e(TAG, "Invalid listen port in database: $listenPort")
                            error("Invalid listen port: $listenPort")
                        }
                        if (forwardPort !in 1..65535) {
                            Log.e(TAG, "Invalid forward port in database: $forwardPort")
                            error("Invalid forward port: $forwardPort")
                        }

                        InterfaceConfig.UDP(
                            name = entity.name,
                            enabled = entity.enabled,
                            listenIp = listenIp,
                            listenPort = listenPort,
                            forwardIp = forwardIp,
                            forwardPort = forwardPort,
                            mode = json.optString("mode", "full"),
                        )
                    }

                    "AndroidBLE" -> {
                        // Get device name from JSON, empty string is valid (omits from advertisement)
                        val deviceName = json.optString("device_name", "")

                        // Validate device name only if not empty
                        // Empty device names are allowed - they omit the name from BLE advertisement
                        if (deviceName.isNotBlank()) {
                            when (val nameResult = InputValidator.validateDeviceName(deviceName)) {
                                is ValidationResult.Error -> {
                                    Log.e(TAG, "Invalid device name in database: $deviceName - ${nameResult.message}")
                                    error("Invalid device name: $deviceName")
                                }
                                else -> {}
                            }
                        }

                        InterfaceConfig.AndroidBLE(
                            name = entity.name,
                            enabled = entity.enabled,
                            deviceName = deviceName,
                            maxConnections = json.optInt("max_connections", 7),
                            mode = json.optString("mode", "full"),
                        )
                    }

                    else -> {
                        Log.e(TAG, "Unknown interface type in database: ${entity.type}")
                        throw IllegalArgumentException("Unknown interface type: ${entity.type}")
                    }
                }
            } catch (e: JSONException) {
                Log.e(TAG, "Corrupted JSON in database for interface '${entity.name}': ${e.message}", e)
                error("Corrupted interface configuration for '${entity.name}': ${e.message}")
            }
        }

        companion object {
            private const val TAG = "InterfaceRepository"
        }
    }
