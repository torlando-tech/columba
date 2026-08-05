package network.columba.app.ui.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioAttachmentLoaderTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val loader = AudioAttachmentLoader(context)

    @Test
    fun `loads inline canonical audio payload`() = runTest {
        val attachment = attachment(fieldsJson = """{"7":[16,"4f676753"]}""")

        assertArrayEquals("OggS".encodeToByteArray(), loader.loadBytes(attachment))
    }

    @Test
    fun `loads payload level file reference containing hex`() = runTest {
        val payloadFile = tempFile("payload", "4f676753")
        val attachment =
            attachment(
                fieldsJson = """{"7":[16,{"_file_ref":${jsonString(payloadFile.absolutePath)}}]}""",
            )

        assertArrayEquals("OggS".encodeToByteArray(), loader.loadBytes(attachment))
    }

    @Test
    fun `loads legacy whole field reference containing canonical array`() = runTest {
        val fieldFile = tempFile("legacy", "[16,\"4f676753\"]")
        val attachment =
            attachment(
                fieldsJson = """{"7":{"_file_ref":${jsonString(fieldFile.absolutePath)}}}""",
            )

        assertArrayEquals("OggS".encodeToByteArray(), loader.loadBytes(attachment))
    }

    @Test
    fun `loads raw Ogg bytes from file reference`() = runTest {
        val expected = "OggSraw-payload".encodeToByteArray()
        val file = File.createTempFile("audio_raw", ".ogg", context.cacheDir).apply { writeBytes(expected) }
        val attachment =
            AudioAttachmentUi(
                mode = AudioAttachmentMode.AM_OPUS_OGG,
                payloadRef = AudioAttachmentPayloadRef.FileRef(file.absolutePath),
                isPlayable = true,
            )

        assertArrayEquals(expected, loader.loadBytes(attachment))
    }

    @Test
    fun `rejects malformed and missing payloads`() = runTest {
        assertNull(loader.loadBytes(attachment(fieldsJson = """{"7":[16,"not-hex"]}""")))
        assertNull(
            loader.loadBytes(
                AudioAttachmentUi(
                    mode = AudioAttachmentMode.AM_OPUS_OGG,
                    payloadRef = AudioAttachmentPayloadRef.FileRef("/missing/audio.ogg"),
                    isPlayable = true,
                ),
            ),
        )
    }

    private fun attachment(fieldsJson: String) =
        AudioAttachmentUi(
            mode = AudioAttachmentMode.AM_OPUS_OGG,
            fieldsJson = fieldsJson,
            isPlayable = true,
        )

    private fun tempFile(prefix: String, contents: String): File =
        File.createTempFile("audio_$prefix", ".data", context.cacheDir).apply { writeText(contents) }

    private fun jsonString(value: String): String = org.json.JSONObject.quote(value)
}
