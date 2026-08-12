package network.columba.app.rns.host.call.rnode

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class BluetoothLeConnectionTest {
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
