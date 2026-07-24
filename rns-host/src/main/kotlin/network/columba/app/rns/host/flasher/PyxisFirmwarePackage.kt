package network.columba.app.rns.host.flasher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/** A fully validated Pyxis update package, retained in its original ZIP representation. */
class PyxisFirmwarePackage private constructor(
    archiveBytes: ByteArray,
    val manifest: PyxisManifest,
) {
    private val archiveBytes = archiveBytes.copyOf()

    val version: String
        get() = manifest.version

    /** Returns a new stream each time so ESPTool can consume the validated package. */
    fun openStream(): InputStream = ByteArrayInputStream(archiveBytes)

    /** Matches the existing firmware-package stream naming used by the flasher subsystem. */
    fun getInputStream(): InputStream = openStream()

    companion object {
        const val MAX_ARCHIVE_BYTES = 4 * 1024 * 1024
        const val MAX_FIRMWARE_BYTES = 0x300000
        const val MAX_BOOT_APP0_BYTES = 0x2000
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val ESP_IMAGE_MAGIC = 0xE9
        private const val ESP32_S3_CHIP_ID = 9
        private const val ESP_IMAGE_CHIP_ID_OFFSET = 12
        private const val ESP_IMAGE_MIN_HEADER_BYTES = 24

        private const val MANIFEST_NAME = "manifest.json"
        private const val FIRMWARE_NAME = "firmware.bin"
        private const val BOOT_APP0_NAME = "boot_app0.bin"
        private val REQUIRED_ENTRIES = setOf(MANIFEST_NAME, FIRMWARE_NAME, BOOT_APP0_NAME)
        private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        private val json = Json { isLenient = false }

        /** Reads, validates, and retains a bounded Pyxis ZIP package. */
        fun parse(input: InputStream): PyxisFirmwarePackage {
            val archive = input.use { it.readBounded(MAX_ARCHIVE_BYTES, "Archive") }
            val entries = readEntries(archive)
            val manifest = parseManifest(entries.getValue(MANIFEST_NAME))
            validateDescriptor(manifest.firmware, FIRMWARE_NAME, 0x10000, MAX_FIRMWARE_BYTES, entries.getValue(FIRMWARE_NAME))
            validateDescriptor(manifest.bootApp0, BOOT_APP0_NAME, 0xE000, MAX_BOOT_APP0_BYTES, entries.getValue(BOOT_APP0_NAME))
            return PyxisFirmwarePackage(archive, manifest)
        }

        private fun readEntries(archive: ByteArray): Map<String, ByteArray> {
            val entries = linkedMapOf<String, ByteArray>()
            try {
                ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        require(!entry.isDirectory) { "Directories are not allowed in a Pyxis package" }
                        require(isBasename(name)) { "ZIP entries must be basenames" }
                        require(name in REQUIRED_ENTRIES) { "Unexpected ZIP entry: $name" }
                        require(name !in entries) { "Duplicate ZIP entry: $name" }
                        val limit = when (name) {
                            MANIFEST_NAME -> MAX_MANIFEST_BYTES
                            FIRMWARE_NAME -> MAX_FIRMWARE_BYTES
                            BOOT_APP0_NAME -> MAX_BOOT_APP0_BYTES
                            else -> error("Entry was already checked")
                        }
                        require(entry.size < 0 || entry.size <= limit) { "$name is oversized" }
                        entries[name] = zip.readBounded(limit, name)
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed Pyxis ZIP package", e)
            }
            require(entries.keys == REQUIRED_ENTRIES) { "Package must contain exactly $REQUIRED_ENTRIES" }
            return entries
        }

        private fun isBasename(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

        private fun parseManifest(data: ByteArray): PyxisManifest {
            val root = try {
                json.parseToJsonElement(data.decodeToString()).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed Pyxis manifest JSON", e)
            }
            val schemaVersion = root.requiredInt("schemaVersion")
            val product = root.requiredString("product")
            val board = root.requiredString("board")
            val chip = root.requiredString("chip")
            val version = root.requiredString("version")
            val firmware = root.requiredDescriptor("firmware")
            val bootApp0 = root.requiredDescriptor("bootApp0")

            require(schemaVersion == 1) { "Unsupported Pyxis manifest schema" }
            require(product == "pyxis") { "Wrong product identifier" }
            require(board == "t-deck-plus") { "Wrong board identifier" }
            require(chip == "esp32-s3") { "Wrong chip identifier" }
            require(version.isNotBlank()) { "Firmware version must not be blank" }
            return PyxisManifest(schemaVersion, product, board, chip, version, firmware, bootApp0)
        }

        private fun JsonObject.requiredDescriptor(key: String): PyxisImageDescriptor {
            val value = this[key] ?: throw IllegalArgumentException("Missing manifest field: $key")
            val objectValue = try {
                value.jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("Manifest field $key must be an object", e)
            }
            return PyxisImageDescriptor(
                name = objectValue.requiredImageName(),
                offset = objectValue.requiredInt("offset"),
                size = objectValue.requiredInt("size"),
                sha256 = objectValue.requiredString("sha256"),
            )
        }

        private fun JsonObject.requiredImageName(): String {
            val supportedKeys = listOf("name", "file", "filename").filter { containsKey(it) }
            require(supportedKeys.size == 1) { "Image descriptor must contain exactly one name field" }
            return requiredString(supportedKeys.single())
        }

        private fun JsonObject.requiredString(key: String): String {
            val primitive = this[key] as? JsonPrimitive ?: throw IllegalArgumentException("Missing or invalid manifest field: $key")
            require(primitive.isString) { "Manifest field $key must be a string" }
            return primitive.content
        }

        private fun JsonObject.requiredInt(key: String): Int {
            val primitive = this[key] as? JsonPrimitive ?: throw IllegalArgumentException("Missing or invalid manifest field: $key")
            require(!primitive.isString) { "Manifest field $key must be an integer" }
            return primitive.intOrNull ?: throw IllegalArgumentException("Manifest field $key must be an integer")
        }

        private fun validateDescriptor(
            descriptor: PyxisImageDescriptor,
            expectedName: String,
            expectedOffset: Int,
            maxSize: Int,
            image: ByteArray,
        ) {
            require(descriptor.name == expectedName) { "Wrong image name for $expectedName" }
            require(descriptor.offset == expectedOffset) { "Wrong flash offset for $expectedName" }
            require(descriptor.size in 1..maxSize) { "Invalid declared size for $expectedName" }
            require(descriptor.size == image.size) { "Declared size mismatch for $expectedName" }
            require(SHA256_PATTERN.matches(descriptor.sha256)) { "Invalid SHA-256 for $expectedName" }
            val actualHash = MessageDigest.getInstance("SHA-256").digest(image).toHex()
            require(actualHash.equals(descriptor.sha256, ignoreCase = true)) { "SHA-256 mismatch for $expectedName" }
            if (expectedName == FIRMWARE_NAME) {
                require(image.firstOrNull()?.toInt()?.and(0xff) == ESP_IMAGE_MAGIC) { "Invalid ESP image magic for $expectedName" }
                require(image.size >= ESP_IMAGE_MIN_HEADER_BYTES) { "ESP application header is truncated" }
                val chipId =
                    (image[ESP_IMAGE_CHIP_ID_OFFSET].toInt() and 0xff) or
                        ((image[ESP_IMAGE_CHIP_ID_OFFSET + 1].toInt() and 0xff) shl 8)
                require(chipId == ESP32_S3_CHIP_ID) { "Firmware is not an ESP32-S3 image" }
            } else if (expectedName == BOOT_APP0_NAME) {
                require(image.size == MAX_BOOT_APP0_BYTES) { "boot_app0.bin must fill the OTA data partition" }
                require(image.take(4) == listOf<Byte>(0x01, 0x00, 0x00, 0x00)) { "Invalid boot_app0 OTA selector" }
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }

        private fun InputStream.readBounded(maxBytes: Int, label: String): ByteArray {
            val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = read(buffer)
                if (count == -1) {
                    break
                }
                if (count > 0) {
                    total += count
                    require(total <= maxBytes) { "$label exceeds the $maxBytes byte limit" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }
}

data class PyxisManifest(
    val schemaVersion: Int,
    val product: String,
    val board: String,
    val chip: String,
    val version: String,
    val firmware: PyxisImageDescriptor,
    val bootApp0: PyxisImageDescriptor,
)

data class PyxisImageDescriptor(
    val name: String,
    val offset: Int,
    val size: Int,
    val sha256: String,
)
