package com.lxmf.messenger.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Database entity representing a Reticulum network interface configuration.
 *
 * The interface-specific parameters are stored as JSON in the [configJson] field,
 * which allows for flexibility in supporting different interface types with varying parameters.
 */
@Entity(tableName = "interfaces")
data class InterfaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /**
     * User-friendly name for this interface (e.g., "Home WiFi", "Laptop TCP", "LoRa Radio")
     */
    val name: String,
    /**
     * Interface type identifier (e.g., "AutoInterface", "TCPClient", "AndroidBLE", "RNode")
     */
    val type: String,
    /**
     * Whether this interface is currently enabled and should be initialized
     */
    val enabled: Boolean = true,
    /**
     * JSON string containing interface-specific configuration parameters.
     * Structure varies by interface type:
     *
     * AutoInterface:
     * {
     *   "group_id": "custom_network",
     *   "discovery_scope": "link",
     *   "discovery_port": 48555,
     *   "data_port": 49555,
     *   "mode": "full"
     * }
     *
     * TCPClient:
     * {
     *   "target_host": "10.0.0.245",
     *   "target_port": 4242,
     *   "kiss_framing": false,
     *   "mode": "full"
     * }
     */
    val configJson: String,
    /**
     * Display order for the interface list (lower numbers appear first).
     * Used for future drag-and-drop reordering feature.
     */
    val displayOrder: Int = 0,
)
