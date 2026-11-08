package network.columba.app.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import network.columba.app.domain.model.SignatureStatus
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.AudioAttachmentMode
import network.columba.app.ui.model.AudioAttachmentUi
import network.columba.app.ui.model.FileAttachmentUi
import network.columba.app.ui.model.MessageUi
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageBubbleSignatureStatusTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val unknownIdentity =
        hasText("identity", substring = true, ignoreCase = true) and
            hasText("unknown", substring = true, ignoreCase = true)
    private val notVerified = hasText("not verified", substring = true, ignoreCase = true)
    private val invalidSignature = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    private fun message(status: SignatureStatus?) =
        MessageUi(
            id = "m1",
            destinationHash = "abc123",
            content = "hello",
            timestamp = 1_000L,
            isFromMe = false,
            status = "delivered",
            signatureStatus = status,
        )

    private fun render(initialMessage: MessageUi): MutableState<MessageUi> {
        val state = mutableStateOf(initialMessage)
        composeRule.setContent {
            MaterialTheme {
                MessageBubble(
                    message = state.value,
                    isFromMe = false,
                    clipboardManager = LocalClipboardManager.current,
                )
            }
        }
        return state
    }

    private fun payloadShapes(): List<MessageUi> {
        val base = message(SignatureStatus.SOURCE_UNKNOWN)
        return listOf(
            base,
            base.copy(
                content = "",
                hasImageAttachment = true,
                decodedImage = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap(),
            ),
            base.copy(
                content = "",
                isAnimatedImage = true,
                imageData = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT),
            ),
            base.copy(
                hasFileAttachments = true,
                fileAttachments = listOf(FileAttachmentUi("document.txt", 12, "text/plain", 0)),
            ),
            base.copy(
                content = "",
                audioAttachment = AudioAttachmentUi(mode = AudioAttachmentMode.AM_OPUS_OGG),
            ),
            base.copy(
                fieldsJson = """{"16":{"pending_file_notification":{"original_message_id":"file1","filename":"document.txt","file_count":1,"total_size":12}}}""",
            ),
        )
    }

    @Test
    fun `unknown identity warns without claiming failure for every payload shape`() {
        val shapes = payloadShapes()
        val state = render(shapes.first())

        shapes.forEach { shape ->
            composeRule.runOnIdle { state.value = shape }
            composeRule.onNode(unknownIdentity).assertIsDisplayed()
            composeRule.onAllNodes(invalidSignature).assertCountEquals(0)
        }
    }

    @Test
    fun `invalid signature exposes an accessible error for every payload shape`() {
        val shapes = payloadShapes().map { it.copy(signatureStatus = SignatureStatus.INVALID) }
        val state = render(shapes.first())

        shapes.forEach { shape ->
            composeRule.runOnIdle { state.value = shape }
            composeRule.onNode(invalidSignature).assertIsDisplayed()
            composeRule.onAllNodes(unknownIdentity).assertCountEquals(0)
        }
    }

    @Test
    fun `inconclusive verification warns without claiming failure for every payload shape`() {
        val shapes = payloadShapes().map { it.copy(signatureStatus = SignatureStatus.UNVERIFIED) }
        val state = render(shapes.first())

        shapes.forEach { shape ->
            composeRule.runOnIdle { state.value = shape }
            composeRule.onNode(notVerified).assertIsDisplayed()
            composeRule.onAllNodes(invalidSignature).assertCountEquals(0)
        }
    }

    @Test
    fun `known valid and legacy messages have no signature warning`() {
        val state = render(message(SignatureStatus.VERIFIED))

        listOf(SignatureStatus.VERIFIED, null).forEach { status ->
            composeRule.runOnIdle { state.value = message(status) }
            composeRule.onAllNodes(unknownIdentity or notVerified or invalidSignature).assertCountEquals(0)
        }
    }

    @Test
    fun `a newly verified sender removes the warning without reopening the conversation`() {
        val state = render(message(SignatureStatus.SOURCE_UNKNOWN))
        composeRule.onNode(unknownIdentity).assertIsDisplayed()

        composeRule.runOnIdle { state.value = state.value.copy(signatureStatus = SignatureStatus.VERIFIED) }

        composeRule.onAllNodes(unknownIdentity or notVerified or invalidSignature).assertCountEquals(0)
    }

    @Test
    fun `a newly resolved invalid signature replaces the unknown identity warning`() {
        val state = render(message(SignatureStatus.SOURCE_UNKNOWN))
        composeRule.onNode(unknownIdentity).assertIsDisplayed()

        composeRule.runOnIdle { state.value = state.value.copy(signatureStatus = SignatureStatus.INVALID) }

        composeRule.onAllNodes(unknownIdentity).assertCountEquals(0)
        composeRule.onNode(invalidSignature).assertIsDisplayed()
    }
}
