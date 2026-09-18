package network.columba.app.rns.host.flasher

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PyxisDeviceDetectorTest {
    @Test
    fun `detects production VERSION response among serial logs`() = runTest {
        val detector =
            PyxisDeviceDetector {
                "[boot] USB connected\r\nPyxis v0.2.2-157-g9d5cc32\r\n[heap] ok\r\n"
            }

        assertEquals("0.2.2-157-g9d5cc32", detector.detect(42)?.version)
    }

    @Test
    fun `does not classify generic ESP32 or RNode output as Pyxis`() = runTest {
        listOf(
            null,
            "",
            "ESP-ROM:esp32s3-20210327\n",
            "RNode v1.80\n",
            "Pyxis\n",
            "Pyxis v   \n",
        ).forEach { transcript ->
            assertNull(PyxisDeviceDetector { transcript }.detect(7))
        }
    }

    @Test
    fun `queries the selected Android USB device id`() = runTest {
        var queriedId: Int? = null
        val detector =
            PyxisDeviceDetector { deviceId ->
                queriedId = deviceId
                "Pyxis vdev\n"
            }

        detector.detect(1234)

        assertEquals(1234, queriedId)
    }
}
