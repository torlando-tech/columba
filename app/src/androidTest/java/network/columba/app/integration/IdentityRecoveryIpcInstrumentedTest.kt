package network.columba.app.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import network.columba.app.test.TestController
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentityRecoveryIpcInstrumentedTest {
    @Test
    fun createAndImportRoundTripAcrossProductionBinder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entryPoint =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                TestController.TestEntryPoint::class.java,
            )
        val core = entryPoint.rnsCore()

        val created =
            withTimeout(60_000) {
                core.createIdentityWithName("Disposable recovery IPC fixture")
            }
        val identityHash = created["identity_hash"] as? String
        val destinationHash = created["destination_hash"] as? String
        val keyData = created["key_data"] as? ByteArray
        assertEquals(true, created["success"])
        assertNotNull(identityHash)
        assertEquals(32, destinationHash?.length)
        assertEquals(64, keyData?.size)

        val imported =
            withTimeout(60_000) {
                core.importIdentityFile(checkNotNull(keyData), "Disposable imported fixture")
            }
        assertEquals(true, imported["success"])
        assertEquals(identityHash, imported["identity_hash"])
        assertEquals(destinationHash, imported["destination_hash"])
        assertArrayEquals(keyData, imported["key_data"] as ByteArray)
    }
}
