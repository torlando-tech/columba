package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for QR-code components: [QrCodeBottomSheet], [QrCodeImage],
 * [HashSection] and [IdentityQrCodeDialogContent] (with and without QR data).
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class QrTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    @Test
    fun `qr code bottom sheet`() {
        composeRule.setContent {
            MaterialTheme {
                QrCodeBottomSheet(
                    onDismiss = {},
                    onScanQrCode = {},
                    onShowQrCode = {},
                )
            }
        }
        TextGoldens.assertGolden("qr_code_bottom_sheet", composeRule)
    }

    @Test
    fun `qr code image loaded`() {
        composeRule.setContent {
            MaterialTheme {
                QrCodeImage(data = QR_DATA)
            }
        }
        waitForQrBitmap()
        TextGoldens.assertGolden("qr_code_image_loaded", composeRule)
    }

    @Test
    fun `qr code image blank data`() {
        composeRule.setContent {
            MaterialTheme {
                QrCodeImage(data = "")
            }
        }
        TextGoldens.assertGolden("qr_code_image_blank", composeRule)
    }

    @Test
    fun `hash section`() {
        composeRule.setContent {
            MaterialTheme {
                HashSection(
                    title = "Public Key Hash",
                    hash = PUBLIC_KEY_HASH,
                    onCopy = {},
                )
            }
        }
        TextGoldens.assertGolden("hash_section", composeRule)
    }

    @Test
    fun `identity qr code dialog with qr data`() {
        composeRule.setContent {
            MaterialTheme {
                IdentityQrCodeDialogContent(
                    displayName = DISPLAY_NAME,
                    qrCodeData = QR_DATA,
                    onDismiss = {},
                    actionsContent = {
                        HashSection(
                            title = "Public Key Hash",
                            hash = PUBLIC_KEY_HASH,
                            onCopy = {},
                        )
                    },
                )
            }
        }
        waitForQrBitmap()
        TextGoldens.assertGolden("identity_qr_code_dialog_with_qr", composeRule)
    }

    @Test
    fun `identity qr code dialog without qr data`() {
        composeRule.setContent {
            MaterialTheme {
                IdentityQrCodeDialogContent(
                    displayName = DISPLAY_NAME,
                    qrCodeData = null,
                    onDismiss = {},
                    actionsContent = {},
                )
            }
        }
        TextGoldens.assertGolden("identity_qr_code_dialog_no_qr", composeRule)
    }

    /**
     * QR bitmaps are generated on [kotlinx.coroutines.Dispatchers.Default],
     * outside the Compose test clock; wait for the loaded image so the golden
     * never captures the (text-free) spinner frame.
     */
    private fun waitForQrBitmap() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule
                .onAllNodes(hasContentDescription("QR Code"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private companion object {
        const val QR_DATA = "columba:identity:0123456789abcdef"
        const val PUBLIC_KEY_HASH = "0123456789abcdef0123456789abcdef"
        const val DISPLAY_NAME = "Alice"
    }
}
