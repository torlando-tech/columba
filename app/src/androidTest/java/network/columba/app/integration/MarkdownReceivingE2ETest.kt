package network.columba.app.integration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.Message
import network.columba.app.data.storage.AttachmentStorageManager
import network.columba.app.ui.model.MessageRenderer
import network.columba.app.ui.model.toMessageUi
import network.columba.app.ui.screens.MessageBubble
import network.columba.app.ui.theme.ColumbaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Emulator E2E coverage for received LXMF Markdown.
 *
 * The test follows the inbound application path after backend decoding: save the
 * received message and its raw LXMF fields through the real repository/Room
 * database, observe it back, map it to MessageUi, then render the production
 * message bubble. The captured production Markdown node is compared with an
 * androidTest golden image, or recorded when recordGoldens=true is supplied.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownReceivingE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var database: ColumbaDatabase
    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() =
        runBlocking {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            database =
                Room
                    .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            repository =
                ConversationRepository(
                    conversationDao = database.conversationDao(),
                    messageDao = database.messageDao(),
                    peerIdentityDao = database.peerIdentityDao(),
                    localIdentityDao = database.localIdentityDao(),
                    attachmentStorage = AttachmentStorageManager(context),
                    draftDao = database.draftDao(),
                )
            database.localIdentityDao().insert(
                LocalIdentityEntity(
                    identityHash = TEST_IDENTITY_HASH,
                    displayName = "Golden Test Identity",
                    destinationHash = TEST_DESTINATION_HASH,
                    filePath = "/test/identity",
                    keyData = null,
                    createdTimestamp = FIXED_TIMESTAMP,
                    lastUsedTimestamp = FIXED_TIMESTAMP,
                    isActive = true,
                ),
            )
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun receivedMarkdown_formatsHeadingsEmphasisQuotesListsAndLinks() {
        val markdown =
            """
            # Welcome to Columba

            **Secure mesh messaging** with *Markdown*.

            > LXMF renderer field 15 is active.

            - Direct delivery
            - Propagated fallback

            [Read the LXMF docs](https://github.com/markqvist/LXMF)
            """.trimIndent()

        val message = persistAndReadInboundMessage("markdown-formatting", markdown)
        renderReceivedMessage(message, readyText = "Welcome to Columba")

        composeRule.onNodeWithTag(MARKDOWN_TAG, useUnmergedTree = true).assertExists()
        assertOrRecordGolden("received-markdown-formatting.png")
    }

    @Test
    fun receivedMarkdown_formatsCodeAndOrderedListsWithoutLoadingRemoteImages() {
        val kotlinCode = "val renderer = 2\nreceive(message)"
        val markdown =
            """
            ## Code and tasks

            Use `fields[0x0F] = 0x02`.

            ```kotlin
            val renderer = 2
            receive(message)
            ```

            1. Parse the field
            2. Render safely
            3. Fall back to plain text

            ![Remote image blocked](https://example.com/tracker.png)

            *Remote images are disabled.*
            """.trimIndent()

        val message = persistAndReadInboundMessage("markdown-code", markdown)
        renderReceivedMessage(message, readyText = "Code and tasks")
        waitForSyntaxColors(kotlinCode)

        composeRule.onNodeWithTag(MARKDOWN_TAG, useUnmergedTree = true).assertExists()
        assertOrRecordGolden("received-markdown-code.png")
    }

    private fun waitForSyntaxColors(code: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText(code, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .flatMap { it.config[SemanticsProperties.Text] }
                .flatMap { it.spanStyles }
                .map { it.item.color }
                .filter { it != Color.Unspecified }
                .toSet()
                .size >= 2
        }
    }

    private fun persistAndReadInboundMessage(
        id: String,
        content: String,
    ): Message =
        runBlocking {
            repository.saveMessage(
                peerHash = TEST_PEER_HASH,
                peerName = "LXMF Markdown Peer",
                message =
                    Message(
                        id = id,
                        destinationHash = TEST_PEER_HASH,
                        content = content,
                        timestamp = FIXED_TIMESTAMP,
                        isFromMe = false,
                        status = "delivered",
                        fieldsJson = "{\"15\":2}",
                    ),
                peerPublicKey = null,
            )

            val persisted = repository.getMessages(TEST_PEER_HASH).first { it.isNotEmpty() }.single()
            assertEquals("Renderer field must survive repository persistence", "{\"15\":2}", persisted.fieldsJson)
            assertEquals(MessageRenderer.MARKDOWN, persisted.toMessageUi().renderer)
            persisted
        }

    private fun renderReceivedMessage(
        message: Message,
        readyText: String,
    ) {
        composeRule.setContent {
            ColumbaTheme(darkTheme = false) {
                val clipboardManager = LocalClipboardManager.current
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp)
                            .testTag(ROOT_TAG),
                ) {
                    MessageBubble(
                        message = message.toMessageUi(),
                        isFromMe = false,
                        clipboardManager = clipboardManager,
                        peerName = "LXMF Markdown Peer",
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodesWithText(readyText, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size == 1
        }
    }

    private fun assertOrRecordGolden(name: String) {
        val actual =
            composeRule
                .onNodeWithTag(MARKDOWN_TAG, useUnmergedTree = true)
                .captureToImage()
                .asAndroidBitmap()
        val outputDirectory = File(context.filesDir, "goldens").apply { mkdirs() }
        val outputFile = File(outputDirectory, name)
        FileOutputStream(outputFile).use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recordGoldens = InstrumentationRegistry.getArguments().getString("recordGoldens")?.toBoolean() == true
        if (recordGoldens) {
            android.util.Log.i(TAG, "Recorded golden: ${outputFile.absolutePath}")
            return
        }

        val expected =
            instrumentation.context.assets.open("goldens/$name").use { stream ->
                requireNotNull(BitmapFactory.decodeStream(stream)) { "Unable to decode golden $name" }
            }
        assertBitmapsSimilar(expected, actual, name)
    }

    private fun assertBitmapsSimilar(
        expected: Bitmap,
        actual: Bitmap,
        name: String,
    ) {
        assertEquals("Golden width changed for $name", expected.width, actual.width)
        assertEquals("Golden height changed for $name", expected.height, actual.height)

        var materiallyDifferentPixels = 0
        val pixelCount = expected.width * expected.height
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val expectedPixel = expected.getPixel(x, y)
                val actualPixel = actual.getPixel(x, y)
                if (
                    abs(android.graphics.Color.red(expectedPixel) - android.graphics.Color.red(actualPixel)) > CHANNEL_TOLERANCE ||
                    abs(android.graphics.Color.green(expectedPixel) - android.graphics.Color.green(actualPixel)) > CHANNEL_TOLERANCE ||
                    abs(android.graphics.Color.blue(expectedPixel) - android.graphics.Color.blue(actualPixel)) > CHANNEL_TOLERANCE ||
                    abs(android.graphics.Color.alpha(expectedPixel) - android.graphics.Color.alpha(actualPixel)) > CHANNEL_TOLERANCE
                ) {
                    materiallyDifferentPixels++
                }
            }
        }

        val differenceRatio = materiallyDifferentPixels.toDouble() / pixelCount
        assertTrue(
            "$name differs from its golden by ${(differenceRatio * 100).formatPercent()}% " +
                "($materiallyDifferentPixels of $pixelCount pixels)",
            differenceRatio <= MAX_DIFFERENT_PIXEL_RATIO,
        )
    }

    private fun Double.formatPercent(): String = String.format(java.util.Locale.US, "%.3f", this)

    companion object {
        private const val TAG = "MarkdownReceivingE2E"
        private const val ROOT_TAG = "markdown-golden-root"
        private const val MARKDOWN_TAG = "markdown-message-content"
        private const val TEST_IDENTITY_HASH = "11111111111111111111111111111111"
        private const val TEST_DESTINATION_HASH = "22222222222222222222222222222222"
        private const val TEST_PEER_HASH = "33333333333333333333333333333333"
        private const val FIXED_TIMESTAMP = 1_700_000_000_000L
        private const val CHANNEL_TOLERANCE = 3
        private const val MAX_DIFFERENT_PIXEL_RATIO = 0.005
    }
}
