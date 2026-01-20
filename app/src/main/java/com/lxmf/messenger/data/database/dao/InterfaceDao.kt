package com.lxmf.messenger.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

/**
 * Data Access Object for Reticulum interface configurations.
 */
@Dao
interface InterfaceDao {
    /**
     * Get all configured interfaces ordered by display order.
     *
     * @return Flow of all interfaces, emitting updates whenever the data changes
     */
    @Query("SELECT * FROM interfaces ORDER BY displayOrder ASC, id ASC")
    fun getAllInterfaces(): Flow<List<InterfaceEntity>>

    /**
     * Get only enabled interfaces ordered by display order.
     *
     * @return Flow of enabled interfaces, emitting updates whenever the data changes
     */
    @Query("SELECT * FROM interfaces WHERE enabled = 1 ORDER BY displayOrder ASC, id ASC")
    fun getEnabledInterfaces(): Flow<List<InterfaceEntity>>

    /**
     * Get a specific interface by ID.
     *
     * @param id The interface ID
     * @return Flow of the interface or null if not found
     */
    @Query("SELECT * FROM interfaces WHERE id = :id")
    fun getInterfaceById(id: Long): Flow<InterfaceEntity?>

    /**
     * Insert a new interface configuration.
     *
     * @param interfaceEntity The interface to insert
     * @return The ID of the newly inserted interface
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInterface(interfaceEntity: InterfaceEntity): Long

    /**
     * Update an existing interface configuration.
     *
     * @param interfaceEntity The interface with updated data
     * @return The number of rows updated (should be 1)
     */
    @Update
    suspend fun updateInterface(interfaceEntity: InterfaceEntity): Int

    /**
     * Delete an interface by ID.
     *
     * @param id The ID of the interface to delete
     */
    @Query("DELETE FROM interfaces WHERE id = :id")
    suspend fun deleteInterface(id: Long)

    /**
     * Toggle the enabled state of an interface.
     *
     * @param id The ID of the interface to toggle
     * @param enabled The new enabled state
     */
    @Query("UPDATE interfaces SET enabled = :enabled WHERE id = :id")
    suspend fun setInterfaceEnabled(
        id: Long,
        enabled: Boolean,
    )

    /**
     * Delete all interfaces (useful for testing).
     */
    @Query("DELETE FROM interfaces")
    suspend fun deleteAllInterfaces()

    /**
     * Get the count of enabled interfaces.
     *
     * @return Flow emitting the count of enabled interfaces
     */
    @Query("SELECT COUNT(*) FROM interfaces WHERE enabled = 1")
    fun getEnabledInterfaceCount(): Flow<Int>

    /**
     * Get the count of all interfaces.
     *
     * @return Flow emitting the total count of interfaces
     */
    @Query("SELECT COUNT(*) FROM interfaces")
    fun getTotalInterfaceCount(): Flow<Int>

    /**
     * Check if any Bluetooth-requiring interface is enabled.
     * This includes AndroidBLE and RNode interfaces (except RNode in TCP mode, which uses network connections).
     *
     * @return Flow emitting true if any Bluetooth-requiring interface is enabled
     */
    fun hasEnabledBluetoothInterface(): Flow<Boolean> {
        return getEnabledBluetoothCandidates().map { interfaces ->
            interfaces.any { it.requiresBluetooth() }
        }
    }

    @Query("SELECT * FROM interfaces WHERE enabled = 1 AND (type = 'AndroidBLE' OR type = 'RNode')")
    fun getEnabledBluetoothCandidates(): Flow<List<InterfaceEntity>>

    /**
     * Find an RNode interface by USB device ID.
     * Used to check if a USB device is already configured when it's plugged in.
     *
     * Note: We check for the device ID followed by comma or closing brace to avoid
     * partial matches (e.g., searching for 100 shouldn't match 1002).
     *
     * @param usbDeviceId The Android USB device ID to search for
     * @return The matching interface entity or null if not found
     * @deprecated Use findRNodeByUsbVidPid instead - device IDs are runtime IDs that change
     */
    @Query(
        """
        SELECT * FROM interfaces
        WHERE type = 'RNode'
        AND (
            configJson LIKE '%"usb_device_id":' || :usbDeviceId || ',%'
            OR configJson LIKE '%"usb_device_id":' || :usbDeviceId || '}%'
        )
        LIMIT 1
        """,
    )
    suspend fun findRNodeByUsbDeviceId(usbDeviceId: Int): InterfaceEntity?

    /**
     * Find an RNode interface by USB Vendor ID and Product ID.
     * This is the preferred method for matching USB devices since VID/PID are stable
     * hardware identifiers, unlike device IDs which are runtime IDs that can change.
     *
     * Uses SQLite's json_extract() for reliable JSON parsing (requires SQLite 3.38+/Android 13+).
     *
     * @param vendorId The USB Vendor ID (VID)
     * @param productId The USB Product ID (PID)
     * @return The matching interface entity or null if not found
     */
    @Query(
        """
        SELECT * FROM interfaces
        WHERE type = 'RNode'
        AND json_extract(configJson, '${"$"}.usb_vendor_id') = :vendorId
        AND json_extract(configJson, '${"$"}.usb_product_id') = :productId
        AND json_extract(configJson, '${"$"}.connection_mode') = 'usb'
        LIMIT 1
        """,
    )
    suspend fun findRNodeByUsbVidPid(vendorId: Int, productId: Int): InterfaceEntity?

    /**
     * Get an interface by ID (suspend version for one-shot queries).
     *
     * @param id The interface ID
     * @return The interface entity or null if not found
     */
    @Query("SELECT * FROM interfaces WHERE id = :id")
    suspend fun getInterfaceByIdOnce(id: Long): InterfaceEntity?

    /**
     * Find an interface by name.
     * Used to look up the database ID when navigating from the network status screen.
     *
     * @param name The interface name to search for
     * @return The interface entity or null if not found
     */
    @Query("SELECT * FROM interfaces WHERE name = :name LIMIT 1")
    suspend fun findInterfaceByName(name: String): InterfaceEntity?
}

/**
 * Check if this interface requires Bluetooth permissions.
 */
@Suppress("SwallowedException")
private fun InterfaceEntity.requiresBluetooth(): Boolean {
    return when (type) {
        "AndroidBLE" -> true
        "RNode" -> {
            try {
                val json = JSONObject(configJson)
                json.optString("connection_mode") != "tcp"
            } catch (e: JSONException) {
                // Malformed JSON defaults to requiring Bluetooth (conservative fallback)
                true
            }
        }
        else -> false
    }
}
