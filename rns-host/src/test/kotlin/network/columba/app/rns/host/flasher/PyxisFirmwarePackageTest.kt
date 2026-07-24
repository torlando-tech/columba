package network.columba.app.rns.host.flasher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Suppress("LongParameterList", "MaxLineLength")
class PyxisFirmwarePackageTest {
    private val firmware = ByteArray(24).apply {
        this[0] = 0xE9.toByte()
        this[12] = 0x09
    }
    // Espressif's real boot_app0.bin starts with the OTA-select value 0x00000001,
    // not the 0xE9 application-image magic.
    private val bootApp0 =
        ByteArray(0x2000) { 0xff.toByte() }.apply {
            this[0] = 0x01
            this[1] = 0x00
            this[2] = 0x00
            this[3] = 0x00
        }

    @Test
    fun `valid package is retained and exposes a fresh stream`() {
        val bytes = packageBytes()
        val parsed = PyxisFirmwarePackage.parse(bytes.inputStream())

        assertEquals("1.2.3", parsed.version)
        assertEquals(firmware.size, parsed.manifest.firmware.size)
        assertEquals(bootApp0.size, parsed.manifest.bootApp0.size)
        assertNotSame(parsed.openStream(), parsed.openStream())
        assertArrayEquals(bytes, parsed.openStream().readBytes())
    }

    @Test
    fun `uppercase hashes are accepted`() {
        PyxisFirmwarePackage.parse(packageBytes(uppercaseHashes = true).inputStream())
    }

    @Test
    fun `rejects archive input over limit`() {
        val oversized = ByteArray(PyxisFirmwarePackage.MAX_ARCHIVE_BYTES + 1)
        assertInvalid { PyxisFirmwarePackage.parse(oversized.inputStream()) }
    }

    @Test
    fun `rejects missing required entry`() {
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(exclude = "boot_app0.bin").inputStream()) }
    }

    @Test
    fun `rejects duplicate entries`() {
        val manifest = manifestJson().encodeToByteArray()
        val bytes = rawZip(listOf("manifest.json" to manifest, "firmware.bin" to firmware, "firmware.bin" to firmware, "boot_app0.bin" to bootApp0))
        assertInvalid { PyxisFirmwarePackage.parse(bytes.inputStream()) }
    }

    @Test
    fun `rejects traversal and non-basename entries`() {
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(rename = "firmware.bin" to "../firmware.bin").inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(rename = "firmware.bin" to "dir/firmware.bin").inputStream()) }
    }

    @Test
    fun `rejects unknown extra binary and other extra entries`() {
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(extra = "bootloader.bin" to firmware).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(extra = "notes.txt" to byteArrayOf(1)).inputStream()) }
    }

    @Test
    fun `rejects oversized entries`() {
        val tooLargeFirmware = ByteArray(PyxisFirmwarePackage.MAX_FIRMWARE_BYTES + 1) { 0xE9.toByte() }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(firmwareData = tooLargeFirmware).inputStream()) }
        val tooLargeBoot = ByteArray(PyxisFirmwarePackage.MAX_BOOT_APP0_BYTES + 1) { 0xE9.toByte() }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(bootData = tooLargeBoot).inputStream()) }
    }

    @Test
    fun `rejects malformed manifest JSON`() {
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = "{").inputStream()) }
    }

    @Test
    fun `rejects wrong package identifiers and blank version`() {
        listOf(
            manifestJson(schemaVersion = 2),
            manifestJson(product = "rnode"),
            manifestJson(board = "t-deck"),
            manifestJson(chip = "esp32"),
            manifestJson(version = "  "),
        ).forEach { manifest ->
            assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifest).inputStream()) }
        }
    }

    @Test
    fun `rejects wrong descriptor names and offsets`() {
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(firmwareName = "app.bin")).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(firmwareOffset = 0x20000)).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(bootName = "ota.bin")).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(bootOffset = 0xD000)).inputStream()) }
    }

    @Test
    fun `rejects mismatched declared sizes and hashes`() {
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(firmwareSize = firmware.size + 1)).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(bootSize = bootApp0.size + 1)).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(firmwareHash = "00".repeat(32))).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(bootHash = "00".repeat(32))).inputStream()) }
    }

    @Test
    fun `rejects malformed hashes and sizes beyond partition bounds`() {
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(firmwareHash = "xyz")).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(firmwareSize = PyxisFirmwarePackage.MAX_FIRMWARE_BYTES + 1)).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(manifest = manifestJson(bootSize = PyxisFirmwarePackage.MAX_BOOT_APP0_BYTES + 1)).inputStream()) }
    }

    @Test
    fun `rejects invalid ESP image magic`() {
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(firmwareData = byteArrayOf(0x00, 1)).inputStream()) }
    }

    @Test
    fun `rejects wrong ESP chip and invalid OTA selector image`() {
        val wrongChip = firmware.copyOf().apply { this[12] = 0x00 }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(firmwareData = wrongChip).inputStream()) }
        assertInvalid { PyxisFirmwarePackage.parse(packageBytes(bootData = bootApp0.copyOfRange(0, 16)).inputStream()) }
        assertInvalid {
            PyxisFirmwarePackage.parse(
                packageBytes(bootData = bootApp0.copyOf().apply { this[0] = 0x00 }).inputStream(),
            )
        }
    }

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    private fun packageBytes(
        manifest: String? = null,
        firmwareData: ByteArray = firmware,
        bootData: ByteArray = bootApp0,
        uppercaseHashes: Boolean = false,
        exclude: String? = null,
        rename: Pair<String, String>? = null,
        extra: Pair<String, ByteArray>? = null,
    ): ByteArray {
        val manifestData = (manifest ?: manifestJson(firmwareData = firmwareData, bootData = bootData, uppercaseHashes = uppercaseHashes)).encodeToByteArray()
        val entries = linkedMapOf("manifest.json" to manifestData, "firmware.bin" to firmwareData, "boot_app0.bin" to bootData)
        exclude?.let(entries::remove)
        rename?.let { (old, new) -> entries[new] = entries.remove(old)!! }
        extra?.let { entries[it.first] = it.second }
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun manifestJson(
        schemaVersion: Int = 1,
        product: String = "pyxis",
        board: String = "t-deck-plus",
        chip: String = "esp32-s3",
        version: String = "1.2.3",
        firmwareName: String = "firmware.bin",
        firmwareOffset: Int = 0x10000,
        firmwareSize: Int = firmware.size,
        firmwareHash: String = sha256(firmware),
        bootName: String = "boot_app0.bin",
        bootOffset: Int = 0xE000,
        bootSize: Int = bootApp0.size,
        bootHash: String = sha256(bootApp0),
        firmwareData: ByteArray = firmware,
        bootData: ByteArray = bootApp0,
        uppercaseHashes: Boolean = false,
    ): String {
        val actualFirmwareHash = if (firmwareHash == sha256(firmware)) sha256(firmwareData) else firmwareHash
        val actualBootHash = if (bootHash == sha256(bootApp0)) sha256(bootData) else bootHash
        return """{"schemaVersion":$schemaVersion,"product":"$product","board":"$board","chip":"$chip","version":"$version","firmware":{"name":"$firmwareName","offset":$firmwareOffset,"size":$firmwareSize,"sha256":"${if (uppercaseHashes) actualFirmwareHash.uppercase() else actualFirmwareHash}"},"bootApp0":{"name":"$bootName","offset":$bootOffset,"size":$bootSize,"sha256":"${if (uppercaseHashes) actualBootHash.uppercase() else actualBootHash}"}}"""
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun rawZip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        entries.forEach { (name, data) ->
            val nameBytes = name.encodeToByteArray()
            val crc = CRC32().apply { update(data) }.value
            fun le16(value: Int) { output.write(value and 0xff); output.write(value ushr 8 and 0xff) }
            fun le32(value: Long) { repeat(4) { output.write((value ushr (it * 8)).toInt() and 0xff) } }
            le32(0x04034b50)
            le16(20); le16(0); le16(0); le16(0); le16(0)
            le32(crc); le32(data.size.toLong()); le32(data.size.toLong())
            le16(nameBytes.size); le16(0)
            output.write(nameBytes); output.write(data)
        }
    }.toByteArray()
}
