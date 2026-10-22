package network.columba.app.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import network.columba.app.data.db.dao.ConversationDao
import network.columba.app.data.db.dao.DraftDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.PeerIdentityDao
import network.columba.app.data.storage.AttachmentStorageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [ConversationRepository.extractLargeAttachments] on received image
 * messages (LXMF field 6).
 *
 * Every Columba sender puts field 6 on the wire as `[format, bytes]`
 * (Columba-iOS `RnsLxmf`, Android `NativeMessageSender`), and both backends
 * serialize a received image field as the JSON array `["jpg", "<hex>"]`. When
 * such an array exceeds the extraction threshold it must be stored like audio
 * (field 7): only the hex payload goes to disk and the array shape is kept as
 * `["jpg", {"_file_ref": path}]`. Storing the whole array string instead makes
 * the message mapper hex-decode `["jpg",...` and the bubble renders
 * "not available".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConversationRepositoryImageExtractionTest {
    private lateinit var repository: ConversationRepository
    private lateinit var attachmentStorage: AttachmentStorageManager
    private lateinit var attachmentsRoot: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        attachmentStorage = AttachmentStorageManager(context)
        attachmentsRoot = File(context.filesDir, "attachments")
        repository =
            ConversationRepository(
                mockk<ConversationDao>(),
                mockk<MessageDao>(),
                mockk<PeerIdentityDao>(),
                mockk<LocalIdentityDao>(),
                attachmentStorage,
                mockk<DraftDao>(),
            )
    }

    @After
    fun tearDown() {
        attachmentsRoot.deleteRecursively()
    }

    @Test
    fun `large array-format image stores only the hex payload and keeps array shape`() {
        val hex = "ff".repeat(AttachmentStorageManager.SIZE_THRESHOLD + 10)
        val fieldsJson =
            JSONObject()
                .put("6", JSONArray().put("jpg").put(hex))
                .toString()

        val extracted = repository.extractLargeAttachments("msg-array", fieldsJson)

        assertNotNull("extraction should have modified the fields", extracted)
        val field6 = JSONObject(extracted!!).opt("6")
        assertTrue(
            "field 6 must keep the [format, payload] array shape, got: $field6",
            field6 is JSONArray,
        )
        val ref = (field6 as JSONArray).optJSONObject(1)
        assertNotNull("payload element must become a _file_ref object", ref)
        val stored = File(ref!!.getString(AttachmentStorageManager.FILE_REF_KEY)).readText()
        assertEquals("stored payload must be the pure hex string", hex, stored)
    }

    @Test
    fun `large hex-string image stores pure hex under a file ref`() {
        val hex = "ff".repeat(AttachmentStorageManager.SIZE_THRESHOLD + 10)
        val fieldsJson = JSONObject().put("6", hex).toString()

        val extracted = repository.extractLargeAttachments("msg-hex", fieldsJson)

        assertNotNull(extracted)
        val ref = JSONObject(extracted!!).optJSONObject("6")
        assertNotNull("hex string field should be replaced by a _file_ref object", ref)
        val stored = File(ref!!.getString(AttachmentStorageManager.FILE_REF_KEY)).readText()
        assertEquals(hex, stored)
    }

    @Test
    fun `small array-format image stays inline untouched`() {
        val hex = "ff".repeat(100)
        val fieldsJson =
            JSONObject()
                .put("6", JSONArray().put("jpg").put(hex))
                .toString()

        val extracted = repository.extractLargeAttachments("msg-small", fieldsJson)

        assertEquals("below-threshold fields must be returned unchanged", fieldsJson, extracted)
        assertNull(
            "no attachment files should be written",
            File(attachmentsRoot, "msg-small").listFiles()?.firstOrNull(),
        )
    }
}
