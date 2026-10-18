package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import network.columba.app.micron.MicronElement
import network.columba.app.micron.MicronParser
import network.columba.app.nomadnet.PageImageState
import network.columba.app.nomadnet.PageImageStatus
import network.columba.app.viewmodel.NomadNetBrowserViewModel.RenderingMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for Micron page rendering: `MicronPageContent` fed a fixed Micron
 * markup document parsed with [MicronParser] (rendered in every [RenderingMode]),
 * and `MicronImageBlock` in its null-state placeholder and mid-download loading states.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MicronTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    @Test
    fun `micron page content proportional wrap`() {
        composeRule.setContent {
            MaterialTheme {
                MicronPageContent(
                    document = MicronParser.parse(FIXED_MARKUP),
                    formFields = FIXED_FORM_FIELDS,
                    renderingMode = RenderingMode.PROPORTIONAL_WRAP,
                    onLinkClick = { _, _ -> },
                    onFieldUpdate = { _, _ -> },
                )
            }
        }
        TextGoldens.assertGolden("micron_page_content_proportional_wrap", composeRule)
    }

    @Test
    fun `micron page content monospace scroll`() {
        composeRule.setContent {
            MaterialTheme {
                MicronPageContent(
                    document = MicronParser.parse(FIXED_MARKUP),
                    formFields = FIXED_FORM_FIELDS,
                    renderingMode = RenderingMode.MONOSPACE_SCROLL,
                    onLinkClick = { _, _ -> },
                    onFieldUpdate = { _, _ -> },
                )
            }
        }
        TextGoldens.assertGolden("micron_page_content_monospace_scroll", composeRule)
    }

    @Test
    fun `micron page content monospace zoom`() {
        composeRule.setContent {
            MaterialTheme {
                MicronPageContent(
                    document = MicronParser.parse(FIXED_MARKUP),
                    formFields = FIXED_FORM_FIELDS,
                    renderingMode = RenderingMode.MONOSPACE_ZOOM,
                    onLinkClick = { _, _ -> },
                    onFieldUpdate = { _, _ -> },
                )
            }
        }
        TextGoldens.assertGolden("micron_page_content_monospace_zoom", composeRule)
    }

    @Test
    fun `micron image block placeholder state null`() {
        composeRule.setContent {
            MaterialTheme {
                MicronImageBlock(element = FIXED_IMAGE_ELEMENT, state = null)
            }
        }
        TextGoldens.assertGolden("micron_image_block_placeholder", composeRule)
    }

    @Test
    fun `micron image block loading mid download`() {
        composeRule.setContent {
            MaterialTheme {
                MicronImageBlock(element = FIXED_IMAGE_ELEMENT, state = LOADING_IMAGE_STATE)
            }
        }
        TextGoldens.assertGolden("micron_image_block_loading", composeRule)
    }

    private companion object {
        /**
         * Fixed Micron fixture exercising every text-bearing line kind: page directive +
         * comment (no text), level-1/2 headings, bold/italic toggles, a Micron link inline
         * in text, a centered line, a divider, a blank line (line break), text/masked
         * fields, a prechecked checkbox and radio, and a submit link with field names.
         */
        val FIXED_MARKUP =
            """
            #!bg=1a2
            # Fixture comment, not rendered
            >Nightly Digest
            >>Issue 42
            `!Breaking`! change in the wire format.
            `*Italic note`* end
            Read the `[changelog`:/page/changelog.mu] for details.
            `c~~ Centered banner ~~

            -
            `<|username`alice>
            `<!|pin`4242>
            `<?|subscribe`yes`Subscribe to digest`*>
            `<^|color`blue`Blue`*>
            `[Send`/page/submit`username|pin|subscribe|color]
            """.trimIndent()

        /** Fixed form state so field values, checkbox and radio selection are deterministic. */
        val FIXED_FORM_FIELDS =
            mapOf(
                "username" to "alice",
                "pin" to "1234",
                "subscribe" to "yes",
                "color" to "blue",
            )

        val FIXED_IMAGE_ELEMENT =
            MicronElement.Image(
                alt = "Node map",
                url = ":/media/node_map.webp",
                width = "40",
                height = "10",
                align = "c",
            )

        /** Mid-download fake: renders "42% (43.0 KiB of 102.4 KiB) - 5.0 kbps". */
        val LOADING_IMAGE_STATE =
            PageImageState(
                status = PageImageStatus.LOADING,
                progress = 0.42f,
                speedBps = 5_000,
                receivedBytes = 44_000,
                totalBytes = 104_857,
            )
    }
}
