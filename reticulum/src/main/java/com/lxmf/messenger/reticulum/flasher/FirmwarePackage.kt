package com.lxmf.messenger.reticulum.flasher

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Represents a firmware package for an RNode device.
 *
 * Firmware packages are ZIP files containing:
 * - For nRF52: .bin (firmware) and .dat (init packet) files
 * - For ESP32: .bin files for bootloader, partitions, application, and optionally console image
 * - manifest.json with metadata
 */
data class FirmwarePackage(
    val board: RNodeBoard,
    val version: String,
    val frequencyBand: FrequencyBand,
    val platform: RNodePlatform,
    val zipFile: File,
    val sha256: String?,
    val releaseDate: String?,
) {
    /**
     * Get an input stream for the firmware ZIP file.
     */
    fun getInputStream(): InputStream = zipFile.inputStream()

    /**
     * Verify the firmware file integrity.
     */
    fun verifyIntegrity(): Boolean {
        if (sha256 == null) return true // No hash to verify

        val actualHash = calculateSha256(zipFile)
        return actualHash.equals(sha256, ignoreCase = true)
    }

    /**
     * Delete the firmware file from storage.
     */
    fun delete(): Boolean = zipFile.delete()

    /**
     * Calculate the firmware hash for provisioning.
     *
     * For ESP32 devices, the RNode firmware binary has the SHA256 hash embedded in
     * the last 32 bytes. This is the hash that rnodeconf extracts and writes to EEPROM.
     * The embedded hash is: SHA256(firmware_data[0:-32]).
     *
     * For nRF52 devices, we calculate SHA256 of the entire binary (no embedded hash).
     *
     * @return 32-byte SHA256 hash as ByteArray, or null if extraction fails
     */
    fun calculateFirmwareBinaryHash(): ByteArray? {
        return try {
            ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val hash = tryExtractHashForEntry(zip, entry)
                    if (hash != null) {
                        return hash
                    }
                    entry = zip.nextEntry
                }
            }
            Log.w(TAG, "No application binary found in firmware ZIP")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract firmware binary hash", e)
            null
        }
    }

    /**
     * Try to extract or calculate hash if this entry is an application binary.
     *
     * For ESP32, extracts the embedded hash from the last 32 bytes of the binary.
     * For nRF52, calculates SHA256 of the entire binary.
     */
    @Suppress("MagicNumber")
    private fun tryExtractHashForEntry(
        zip: ZipInputStream,
        entry: java.util.zip.ZipEntry,
    ): ByteArray? {
        // Find the application binary - try various naming conventions
        val isApplicationBinary =
            entry.name.endsWith("application.bin") ||
                (
                    entry.name.endsWith(".bin") &&
                        !entry.name.contains("bootloader") &&
                        !entry.name.contains("partition") &&
                        !entry.name.contains("boot_app0") &&
                        !entry.name.contains("console")
                )

        if (!isApplicationBinary) {
            return null
        }

        // Read entire binary into memory to access last 32 bytes
        val firmwareData = zip.readBytes()
        Log.d(TAG, "Read firmware binary: ${entry.name}, size=${firmwareData.size} bytes")

        return when (platform) {
            RNodePlatform.ESP32 -> {
                // ESP32 firmware has SHA256 hash embedded in last 32 bytes
                // This is what rnodeconf's get_partition_hash() extracts
                if (firmwareData.size < 32) {
                    Log.e(TAG, "Firmware binary too small to contain embedded hash")
                    return null
                }

                // Extract the embedded hash (last 32 bytes)
                val embeddedHash = firmwareData.takeLast(32).toByteArray()

                // Validate by calculating SHA256 of data minus the last 32 bytes
                val firmwareWithoutHash = firmwareData.dropLast(32).toByteArray()
                val calculatedHash = MessageDigest.getInstance("SHA-256").digest(firmwareWithoutHash)

                if (calculatedHash.contentEquals(embeddedHash)) {
                    Log.d(
                        TAG,
                        "ESP32 firmware hash validated: ${embeddedHash.joinToString("") { "%02x".format(it) }}",
                    )
                    embeddedHash
                } else {
                    // Hash mismatch - firmware may be corrupted or doesn't have embedded hash
                    Log.w(
                        TAG,
                        "ESP32 firmware hash mismatch! Embedded hash doesn't match calculated. " +
                            "Embedded: ${embeddedHash.joinToString("") { "%02x".format(it) }}, " +
                            "Calculated: ${calculatedHash.joinToString("") { "%02x".format(it) }}",
                    )
                    // Fall back to returning embedded hash anyway (trust the file)
                    embeddedHash
                }
            }
            RNodePlatform.NRF52 -> {
                // nRF52 doesn't have embedded hash - calculate SHA256 of entire binary
                val hash = MessageDigest.getInstance("SHA-256").digest(firmwareData)
                Log.d(TAG, "nRF52 firmware hash calculated: ${hash.joinToString("") { "%02x".format(it) }}")
                hash
            }
            else -> {
                // For unknown platforms, calculate SHA256 of entire binary
                val hash = MessageDigest.getInstance("SHA-256").digest(firmwareData)
                Log.d(TAG, "Firmware hash calculated: ${hash.joinToString("") { "%02x".format(it) }}")
                hash
            }
        }
    }

    companion object {
        private const val TAG = "Columba:FirmwarePackage"

        /**
         * Calculate SHA256 hash of a file.
         */
        fun calculateSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Parse a firmware ZIP to extract metadata.
         */
        fun parseManifest(zipFile: File): FirmwareManifest? {
            try {
                ZipInputStream(zipFile.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "manifest.json") {
                            val json = zip.readBytes().decodeToString()
                            return parseFirmwareManifest(json)
                        }
                        entry = zip.nextEntry
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse manifest from ${zipFile.name}", e)
            }
            return null
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun parseFirmwareManifest(jsonString: String): FirmwareManifest? =
            try {
                json.decodeFromString<FirmwareManifest>(jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse manifest JSON", e)
                null
            }
    }
}

/**
 * Frequency band for RNode devices.
 */
enum class FrequencyBand(
    val displayName: String,
    val modelSuffix: String,
) {
    BAND_868_915("868/915 MHz", "_868"),
    BAND_433("433 MHz", "_433"),
    UNKNOWN("Unknown", ""),
    ;

    companion object {
        /**
         * Determine frequency band from the RNode model code stored in EEPROM.
         * Model codes are full bytes assigned per-board in rnodeconf — there is no
         * nibble-level pattern that works across all board families.
         */
        @Suppress("CyclomaticComplexMethod")
        fun fromModelCode(model: Byte): FrequencyBand =
            when (model.toInt() and 0xFF) {
                // Low band (420–525 MHz)
                0x04, 0x11, 0x13, 0x16, // TCXO 433, RAK4631 433, RAK4631+SX1280 433, T-Echo 433
                0xA1, 0xA2, 0xA3, 0xA4, 0xA5, // T3S3, NG21, NG20, RNode original, T3S3 SX127x
                0xB3, 0xB4, 0xBA, // LoRa32 v2.0, v2.1, v1.0
                0xC4, 0xC5, 0xC6, // Heltec v2, v3, T114
                0xD4, 0xDB, 0xDE, // T-Deck, T-Beam Supreme, Xiao ESP32S3
                0xE3, 0xE4, // T-Beam SX1262, T-Beam SX127x
                -> BAND_433
                // High band (779–1020 MHz)
                0x09, 0x12, 0x14, 0x17, 0x21, // TCXO 868, RAK4631 868, RAK4631+SX1280 868, T-Echo 868, OpenCom XL
                0xA6, 0xA7, 0xA8, 0xA9, 0xAA, // T3S3, NG21, NG20, RNode original, T3S3 SX127x
                0xB8, 0xB9, 0xBB, // LoRa32 v2.0, v2.1, v1.0
                0xC7, 0xC8, 0xC9, 0xCA, // Heltec T114, v4 PA, v2, v3
                0xD9, 0xDC, 0xDD, // T-Deck, T-Beam Supreme, Xiao ESP32S3
                0xE8, 0xE9, // T-Beam SX1262, T-Beam SX127x
                -> BAND_868_915
                else -> UNKNOWN
            }

        fun fromFilename(filename: String): FrequencyBand =
            when {
                filename.contains("_433") -> BAND_433
                filename.contains("_868") || filename.contains("_915") -> BAND_868_915
                else -> BAND_868_915 // Default to 868/915
            }
    }
}

/**
 * JSON manifest structure from firmware packages.
 */
@Serializable
data class FirmwareManifest(
    val manifest: ManifestContent? = null,
)

@Serializable
data class ManifestContent(
    val application: ApplicationManifest? = null,
    val softdevice: SoftdeviceManifest? = null,
    val bootloader: BootloaderManifest? = null,
)

@Serializable
data class ApplicationManifest(
    @kotlinx.serialization.SerialName("bin_file")
    val binFile: String? = null,
    @kotlinx.serialization.SerialName("dat_file")
    val datFile: String? = null,
    @kotlinx.serialization.SerialName("init_packet_data")
    val initPacketData: InitPacketData? = null,
)

@Serializable
data class SoftdeviceManifest(
    @kotlinx.serialization.SerialName("bin_file")
    val binFile: String? = null,
    @kotlinx.serialization.SerialName("dat_file")
    val datFile: String? = null,
)

@Serializable
data class BootloaderManifest(
    @kotlinx.serialization.SerialName("bin_file")
    val binFile: String? = null,
    @kotlinx.serialization.SerialName("dat_file")
    val datFile: String? = null,
)

@Serializable
data class InitPacketData(
    @kotlinx.serialization.SerialName("application_version")
    val applicationVersion: Int? = null,
    @kotlinx.serialization.SerialName("device_revision")
    val deviceRevision: Int? = null,
    @kotlinx.serialization.SerialName("device_type")
    val deviceType: Int? = null,
    @kotlinx.serialization.SerialName("firmware_crc16")
    val firmwareCrc16: Int? = null,
    @kotlinx.serialization.SerialName("softdevice_req")
    val softdeviceReq: List<Int>? = null,
)

/**
 * Information about available firmware for a board.
 */
data class FirmwareInfo(
    val board: RNodeBoard,
    val version: String,
    val frequencyBand: FrequencyBand,
    val downloadUrl: String,
    val sha256: String,
    val releaseDate: String,
    val releaseNotes: String?,
    val fileSize: Long,
)

/**
 * Repository for managing firmware packages.
 */
class FirmwareRepository(
    private val context: Context,
) {
    companion object {
        private const val TAG = "Columba:FirmwareRepo"
        private const val FIRMWARE_DIR = "firmware"

        // Bundled firmware versions (update when new firmware is released)
        val BUNDLED_FIRMWARE =
            mapOf(
                RNodeBoard.RAK4631 to "1.78",
                RNodeBoard.HELTEC_V3 to "1.78",
                RNodeBoard.TBEAM to "1.78",
            )
    }

    private val firmwareDir: File by lazy {
        File(context.filesDir, FIRMWARE_DIR).also { it.mkdirs() }
    }

    /**
     * Get all downloaded firmware packages.
     */
    fun getDownloadedFirmware(): List<FirmwarePackage> =
        firmwareDir.listFiles()?.filter { it.extension == "zip" }?.mapNotNull { file ->
            parseFirmwareFile(file)
        } ?: emptyList()

    /**
     * Get firmware packages for a specific board.
     */
    fun getFirmwareForBoard(board: RNodeBoard): List<FirmwarePackage> = getDownloadedFirmware().filter { it.board == board }

    /**
     * Get the latest firmware package for a board.
     */
    fun getLatestFirmware(
        board: RNodeBoard,
        frequencyBand: FrequencyBand,
    ): FirmwarePackage? =
        getFirmwareForBoard(board)
            .filter { it.frequencyBand == frequencyBand }
            .maxByOrNull { it.version }

    /**
     * Check if firmware update is available for a device.
     */
    fun isUpdateAvailable(deviceInfo: RNodeDeviceInfo): Boolean {
        val currentVersion = deviceInfo.firmwareVersion ?: return false
        val latestPackage =
            getLatestFirmware(
                deviceInfo.board,
                FrequencyBand.fromModelCode(deviceInfo.model),
            ) ?: return false

        return compareVersions(latestPackage.version, currentVersion) > 0
    }

    /**
     * Save a downloaded firmware package.
     */
    fun saveFirmware(
        board: RNodeBoard,
        version: String,
        frequencyBand: FrequencyBand,
        data: ByteArray,
        sha256: String? = null,
    ): FirmwarePackage? {
        val filename = "${board.firmwarePrefix}${frequencyBand.modelSuffix}_v$version.zip"
        val file = File(firmwareDir, filename)

        return try {
            file.writeBytes(data)

            // Verify hash if provided
            if (sha256 != null) {
                val actualHash = FirmwarePackage.calculateSha256(file)
                if (!actualHash.equals(sha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA256 mismatch for $filename")
                    file.delete()
                    return null
                }
            }

            FirmwarePackage(
                board = board,
                version = version,
                frequencyBand = frequencyBand,
                platform = board.platform,
                zipFile = file,
                sha256 = sha256,
                releaseDate = null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save firmware", e)
            null
        }
    }

    /**
     * Delete all downloaded firmware.
     */
    fun clearCache() {
        firmwareDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Get total size of cached firmware.
     */
    fun getCacheSize(): Long = firmwareDir.listFiles()?.sumOf { it.length() } ?: 0

    private fun parseFirmwareFile(file: File): FirmwarePackage? {
        val name = file.nameWithoutExtension

        // Parse filename: {prefix}_{band}_v{version}
        val board =
            RNodeBoard.entries.find { name.startsWith(it.firmwarePrefix) }
                ?: return null

        val frequencyBand = FrequencyBand.fromFilename(name)

        val versionMatch = Regex("_v([\\d.]+)$").find(name)
        val version = versionMatch?.groupValues?.get(1) ?: "unknown"

        return FirmwarePackage(
            board = board,
            version = version,
            frequencyBand = frequencyBand,
            platform = board.platform,
            zipFile = file,
            sha256 = null,
            releaseDate = null,
        )
    }

    private fun compareVersions(
        v1: String,
        v2: String,
    ): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }
}
