package network.columba.app.rns.host.call.rnode

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class BluetoothLeConnectionTest {
    @Test
    fun `runtime connection resolves paired RNode by configured name`() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<BluetoothManager>(relaxed = true)
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        val device = mockk<BluetoothDevice>()
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        every { manager.adapter } returns adapter
        every { device.name } returns "RNode 1234"
        every { device.address } returns "AA:BB:CC:DD:EE:FF"
        every { adapter.bondedDevices } returns setOf(device)

        val connection = BluetoothLeConnection(context, "RNode 1234")
        val findDevice = connection.javaClass.getDeclaredMethod("findDevice").apply { isAccessible = true }

        assertSame(device, findDevice.invoke(connection))
        verify(exactly = 0) { adapter.bluetoothLeScanner }
    }

    @Test
    fun `runtime connection rejects unbonded RNode without scanning`() {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<BluetoothManager>(relaxed = true)
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { context.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        every { manager.adapter } returns adapter
        every { adapter.bondedDevices } returns emptySet()

        val connection = BluetoothLeConnection(context, "AA:BB:CC:DD:EE:FF")

        assertThrows(IOException::class.java) { connection.connect() }
        verify(exactly = 0) { adapter.bluetoothLeScanner }
    }
}
