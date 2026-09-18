package network.columba.app.rns.host.ble.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.content.Context
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.rns.host.ble.util.BleOperationQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleGattClientTeardownTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `manual disconnect closes GATT and reports disconnection when OEM disconnect throws`() =
        runTest {
            val gatt = mockk<BluetoothGatt>()
            every { gatt.disconnect() } throws SecurityException("BLUETOOTH_PRIVILEGED required")
            every { gatt.close() } just runs
            val client = createClientWithConnection(ADDRESS, gatt)
            val disconnections = mutableListOf<Pair<String, String?>>()
            client.onDisconnected = { address, reason -> disconnections += address to reason }

            client.disconnect(ADDRESS)

            verify(exactly = 1) { gatt.disconnect() }
            verify(exactly = 1) { gatt.close() }
            assertEquals(listOf(ADDRESS to null), disconnections)
        }

    private fun createClientWithConnection(
        address: String,
        gatt: BluetoothGatt,
    ): BleGattClient {
        val client =
            BleGattClient(
                context = mockk<Context>(),
                bluetoothAdapter = mockk<BluetoothAdapter>(),
                operationQueue = mockk<BleOperationQueue>(),
            )
        val connectionDataClass =
            BleGattClient::class.java.declaredClasses.single { it.simpleName == "ConnectionData" }
        val constructor = connectionDataClass.declaredConstructors.single { it.parameterCount == 11 }
        constructor.isAccessible = true
        val connectionData =
            constructor.newInstance(
                gatt,
                address,
                20,
                null,
                null,
                null,
                0,
                null,
                false,
                null,
                0,
            )
        val connectionsField = BleGattClient::class.java.getDeclaredField("connections")
        connectionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val connections = connectionsField.get(client) as MutableMap<String, Any>
        connections[address] = connectionData
        return client
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
