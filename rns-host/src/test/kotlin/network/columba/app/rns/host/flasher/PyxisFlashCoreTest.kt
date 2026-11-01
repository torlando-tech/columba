package network.columba.app.rns.host.flasher

import kotlinx.coroutines.test.runTest
import network.columba.app.rns.host.R
import network.columba.app.rns.host.usb.UsbDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Suppress("MaxLineLength")
class PyxisFlashCoreTest {
    @Test
    fun `uses TDECK actual VID PID and null console with only validated images`() = runTest {
        val states = mutableListOf<RNodeFlasher.FlashState>()
        var captured: PyxisFlashRequest? = null
        val core =
            PyxisFlashCore(
                findDevice = { id -> if (id == 42) usbDevice(id, 0x303A, 0x1001) else null },
                transport = PyxisEspToolTransport { request ->
                    captured = request
                    request.progressCallback.onProgress(
                        50,
                        ESPToolFlasher.FlashStage.FLASHING_APPLICATION,
                        FlashText.Raw("writing"),
                    )
                    request.progressCallback.onComplete()
                    true
                },
                emitState = states::add,
            )

        assertTrue(core.flash(42, validPackage()))

        val request = requireNotNull(captured)
        assertEquals(42, request.deviceId)
        assertEquals(RNodeBoard.TDECK, request.board)
        assertEquals(0x303A, request.vendorId)
        assertEquals(0x1001, request.productId)
        assertNull(request.consoleImageStream)
        assertTrue(request.verifyFlashWrites)
        assertFalse(request.performBackupHardReset)
        assertEquals(8 * 1024 * 1024, request.flashSizeBytes)
        assertEquals(setOf("manifest.json", "firmware.bin", "boot_app0.bin"), zipEntryNames(request.firmwareZipStream.readBytes()))
        assertTrue(states.any { it == RNodeFlasher.FlashState.Progress(50, FlashText.Raw("writing")) })
        assertTrue(states.last() is RNodeFlasher.FlashState.Complete)
        assertEquals(1, states.count { it is RNodeFlasher.FlashState.Complete })
    }

    @Test
    fun `does not emit complete when transport reports failure even after callback complete`() = runTest {
        val states = mutableListOf<RNodeFlasher.FlashState>()
        val core =
            PyxisFlashCore(
                findDevice = { usbDevice(it, 0x1A86, 0x55D4) },
                transport = PyxisEspToolTransport { request ->
                    request.progressCallback.onComplete()
                    false
                },
                emitState = states::add,
            )

        assertFalse(core.flash(7, validPackage()))
        assertFalse(states.any { it is RNodeFlasher.FlashState.Complete })
        assertTrue(states.last() is RNodeFlasher.FlashState.Error)
    }

    @Test
    fun `missing requested USB device fails without invoking transport`() = runTest {
        val states = mutableListOf<RNodeFlasher.FlashState>()
        var invoked = false
        val core =
            PyxisFlashCore(
                findDevice = { null },
                transport = PyxisEspToolTransport { invoked = true; true },
                emitState = states::add,
            )

        assertFalse(core.flash(99, validPackage()))
        assertFalse(invoked)
        assertTrue(states.last() is RNodeFlasher.FlashState.Error)
        assertFalse(states.any { it is RNodeFlasher.FlashState.Complete })
    }

    @Test
    fun `transport exception emits error and never complete`() = runTest {
        val states = mutableListOf<RNodeFlasher.FlashState>()
        val core =
            PyxisFlashCore(
                findDevice = { usbDevice(it, 1, 2) },
                transport = PyxisEspToolTransport { throw IllegalStateException("USB gone") },
                emitState = states::add,
            )

        assertFalse(core.flash(1, validPackage()))
        assertEquals(
            FlashText.Resource(R.string.pyxis_flash_failed_error, listOf("USB gone")),
            (states.last() as RNodeFlasher.FlashState.Error).message,
        )
        assertFalse(states.any { it is RNodeFlasher.FlashState.Complete })
    }

    @Test
    fun `suppresses absent RNode region progress and rewrites manual boot error`() = runTest {
        val states = mutableListOf<RNodeFlasher.FlashState>()
        val core =
            PyxisFlashCore(
                findDevice = { usbDevice(it, 0x303A, 0x1001) },
                transport = PyxisEspToolTransport { request ->
                    request.progressCallback.onProgress(
                        10,
                        ESPToolFlasher.FlashStage.FLASHING_BOOTLOADER,
                        flashText(R.string.esptool_progress_bootloader, 1, 10),
                    )
                    request.progressCallback.onProgress(
                        20,
                        ESPToolFlasher.FlashStage.FLASHING_PARTITION_TABLE,
                        flashText(R.string.esptool_progress_partition_table, 1, 10),
                    )
                    throw ESPToolFlasher.ManualBootModeRequired(R.string.esptool_manual_boot_instructions)
                },
                emitState = states::add,
            )

        assertFalse(core.flash(1, validPackage()))
        val suppressedStageTexts =
            setOf(
                R.string.esptool_progress_bootloader,
                R.string.esptool_progress_partition_table,
            )
        val messages =
            states.mapNotNull {
                when (it) {
                    is RNodeFlasher.FlashState.Progress -> it.message
                    is RNodeFlasher.FlashState.Error -> it.message
                    else -> null
                }
            }
        assertTrue(messages.none { it is FlashText.Resource && it.id in suppressedStageTexts })
        assertEquals(
            FlashText.Resource(R.string.pyxis_manual_boot),
            (states.last() as RNodeFlasher.FlashState.Error).message,
        )
        assertTrue((states.last() as RNodeFlasher.FlashState.Error).recoverable)
    }

    private fun usbDevice(deviceId: Int, vendorId: Int, productId: Int) =
        UsbDeviceInfo(deviceId, vendorId, productId, "usb", null, null, null, "test")

    private fun validPackage(): PyxisFirmwarePackage {
        val firmware = ByteArray(24).apply {
            this[0] = 0xE9.toByte()
            this[12] = 0x09
        }
        val boot =
            ByteArray(0x2000) { 0xff.toByte() }.apply {
                this[0] = 0x01
                this[1] = 0x00
                this[2] = 0x00
                this[3] = 0x00
            }
        val manifest =
            """{"schemaVersion":1,"product":"pyxis","board":"t-deck-plus","chip":"esp32-s3","version":"test","firmware":{"name":"firmware.bin","offset":65536,"size":${firmware.size},"sha256":"${sha256(firmware)}"},"bootApp0":{"name":"boot_app0.bin","offset":57344,"size":${boot.size},"sha256":"${sha256(boot)}"}}"""
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                listOf("manifest.json" to manifest.encodeToByteArray(), "firmware.bin" to firmware, "boot_app0.bin" to boot).forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        return PyxisFirmwarePackage.parse(bytes.inputStream())
    }

    private fun zipEntryNames(bytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
