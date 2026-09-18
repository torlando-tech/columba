package network.columba.app.rns.backend.py

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonRnsCoreIdentityRecoveryInstrumentedTest {
    @Test
    fun createAndImportIdentityWithoutInitializedTransport() =
        runBlocking {
            val runtime =
                PythonRnsRuntime(
                    ApplicationProvider.getApplicationContext(),
                )
            val transport = checkNotNull(runtime.rnsModule["Transport"])
            val hasTransportOwner =
                runtime.python.builtins
                    .callAttr("hasattr", transport, "owner")
                    .toJava(Boolean::class.javaObjectType)

            assertFalse("Test precondition: RNS.Transport must not be initialized", hasTransportOwner)
            assertFalse("Test must not start Reticulum", runtime.isRunning)

            val core = PythonRnsCore(runtime, PythonEventBridge())
            val created = core.createIdentityWithName("Recovery fixture")

            assertEquals(true, created["success"])
            val identityHash = created["identity_hash"] as? String
            val destinationHash = created["destination_hash"] as? String
            val keyData = created["key_data"] as? ByteArray
            assertNotNull(identityHash)
            assertEquals(32, destinationHash?.length)
            assertEquals(64, keyData?.size)

            val imported = core.importIdentityFile(checkNotNull(keyData), "Imported recovery fixture")

            assertEquals(true, imported["success"])
            assertEquals(identityHash, imported["identity_hash"])
            assertEquals(destinationHash, imported["destination_hash"])
            assertArrayEquals(keyData, imported["key_data"] as ByteArray)
            assertFalse("Identity recovery must not start Reticulum", runtime.isRunning)
        }
}
