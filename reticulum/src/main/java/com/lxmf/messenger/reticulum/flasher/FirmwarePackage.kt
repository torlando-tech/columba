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

        private fun parseFirmwareManifest(jsonString: String): FirmwareManifest? {
            return try {
                json.decodeFromString<FirmwareManifest>(jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse manifest JSON", e)
                null
            }
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
    UNKNOWN("Unknown", "");

    companion object {
        fun fromModelCode(model: Byte): FrequencyBand {
            return when (model.toInt() and 0x0F) {
                0x01, 0x04, 0x05 -> BAND_868_915
                0x02, 0x06, 0x07 -> BAND_433
                else -> UNKNOWN
            }
        }

        fun fromFilename(filename: String): FrequencyBand {
            return when {
                filename.contains("_433") -> BAND_433
                filename.contains("_868") || filename.contains("_915") -> BAND_868_915
                else -> BAND_868_915 // Default to 868/915
            }
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
    val bin_file: String? = null,
    val dat_file: String? = null,
    val init_packet_data: InitPacketData? = null,
)

@Serializable
data class SoftdeviceManifest(
    val bin_file: String? = null,
    val dat_file: String? = null,
)

@Serializable
data class BootloaderManifest(
    val bin_file: String? = null,
    val dat_file: String? = null,
)

@Serializable
data class InitPacketData(
    val application_version: Int? = null,
    val device_revision: Int? = null,
    val device_type: Int? = null,
    val firmware_crc16: Int? = null,
    val softdevice_req: List<Int>? = null,
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
        val BUNDLED_FIRMWARE = mapOf(
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
    fun getDownloadedFirmware(): List<FirmwarePackage> {
        return firmwareDir.listFiles()?.filter { it.extension == "zip" }?.mapNotNull { file ->
            parseFirmwareFile(file)
        } ?: emptyList()
    }

    /**
     * Get firmware packages for a specific board.
     */
    fun getFirmwareForBoard(board: RNodeBoard): List<FirmwarePackage> {
        return getDownloadedFirmware().filter { it.board == board }
    }

    /**
     * Get the latest firmware package for a board.
     */
    fun getLatestFirmware(board: RNodeBoard, frequencyBand: FrequencyBand): FirmwarePackage? {
        return getFirmwareForBoard(board)
            .filter { it.frequencyBand == frequencyBand }
            .maxByOrNull { it.version }
    }

    /**
     * Check if firmware update is available for a device.
     */
    fun isUpdateAvailable(deviceInfo: RNodeDeviceInfo): Boolean {
        val currentVersion = deviceInfo.firmwareVersion ?: return false
        val latestPackage = getLatestFirmware(
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
    fun getCacheSize(): Long {
        return firmwareDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    private fun parseFirmwareFile(file: File): FirmwarePackage? {
        val name = file.nameWithoutExtension

        // Parse filename: {prefix}_{band}_v{version}
        val board = RNodeBoard.entries.find { name.startsWith(it.firmwarePrefix) }
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

    private fun compareVersions(v1: String, v2: String): Int {
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
