package network.columba.app.rns.ipc.client

import android.os.ParcelFileDescriptor
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import network.columba.app.domain.model.SignatureStatus
import network.columba.app.rns.api.model.ReceivedMessage
import network.columba.app.rns.ipc.FieldsBlob
import network.columba.app.rns.ipc.IRnsLxmf
import network.columba.app.rns.ipc.callback.IRnsMessageCallback
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ClientRnsLxmfObserverTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `corrupt blob cannot emit a stripped message and later complete delivery still works`() =
        runTest {
            val callback = slot<IRnsMessageCallback>()
            val remote = mockk<IRnsLxmf>()
            every { remote.registerMessageObserver(capture(callback)) } just Runs
            every { remote.unregisterMessageObserver(any()) } just Runs
            every { remote.registerPropagationStateObserver(any()) } just Runs
            every { remote.unregisterPropagationStateObserver(any()) } just Runs
            val client = ClientRnsLxmf(remote, backgroundScope, tmp.root)
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { client.observeMessages().collect { received.add(it) } }
            runCurrent()

            val stripped =
                ReceivedMessage(
                    messageHash = "message",
                    content = "hello",
                    sourceHash = ByteArray(16),
                    destinationHash = ByteArray(16),
                    timestamp = 123L,
                    signatureStatus = SignatureStatus.SOURCE_UNKNOWN,
                )
            // Exercise the actual PFD decoder and production callback, not a mock echo.
            val corrupt = tmp.newFile("truncated.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            callback.captured.onMessage(stripped, ParcelFileDescriptor.open(corrupt, ParcelFileDescriptor.MODE_READ_ONLY))
            runCurrent()
            assertTrue("Unreadable blobs must not persist incomplete messages", received.isEmpty())

            val evidence = byteArrayOf(0, 1, -1, 42)
            callback.captured.onMessage(stripped, FieldsBlob.writeToPfd(tmp.root, "{}", evidence))
            runCurrent()
            assertEquals(1, received.size)
            assertEquals("{}", received.single().fieldsJson)
            assertEquals(SignatureStatus.SOURCE_UNKNOWN, received.single().signatureStatus)
            assertArrayEquals(evidence, received.single().signatureEvidence)
        }
}
