package network.columba.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import network.columba.app.data.config.InterfaceConfigCodec
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.viewmodel.InterfaceImportViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InterfaceConfigImportDialogTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val tcp =
        InterfaceConfig.TCPClient(
            name = "Central Oklahoma Reticulum Hub",
            enabled = true,
            targetHost = "reticulum.mcswain.dev",
            targetPort = 4242,
        )

    private fun item(
        config: InterfaceConfig = tcp,
        duplicate: Boolean = false,
        selected: Boolean = true,
        warnings: List<String> = emptyList(),
    ) = InterfaceImportViewModel.ImportItem(
        config = config,
        originalName = config.name,
        duplicate = duplicate,
        warnings = warnings,
        selected = selected,
    )

    @Test
    fun `parsed interface is shown with its endpoint and import is enabled`() {
        var imported = false
        composeRule.setContent {
            MaterialTheme {
                InterfaceConfigImportDialog(
                    state =
                        InterfaceImportViewModel.State(
                            isOpen = true,
                            text = "[[x]]",
                            items = listOf(item()),
                        ),
                    onDismiss = {},
                    onTextChange = {},
                    onToggle = {},
                    onImport = { imported = true },
                )
            }
        }

        composeRule.onNodeWithText("Central Oklahoma Reticulum Hub").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("TCP client · reticulum.mcswain.dev:4242").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Add interface").assertIsEnabled().performClick()
        assertTrue(imported)
    }

    @Test
    fun `duplicate item is described and add is disabled until selected`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceConfigImportDialog(
                    state =
                        InterfaceImportViewModel.State(
                            isOpen = true,
                            text = "[[x]]",
                            items = listOf(item(duplicate = true, selected = false)),
                        ),
                    onDismiss = {},
                    onTextChange = {},
                    onToggle = {},
                    onImport = {},
                )
            }
        }

        composeRule.onNodeWithText("Already configured").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Add interface").assertIsNotEnabled()
    }

    @Test
    fun `parse errors are displayed`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceConfigImportDialog(
                    state =
                        InterfaceImportViewModel.State(
                            isOpen = true,
                            text = "[[Broken]]",
                            errors = listOf("Broken: Unsupported interface type 'AndroidBLE'"),
                        ),
                    onDismiss = {},
                    onTextChange = {},
                    onToggle = {},
                    onImport = {},
                )
            }
        }

        composeRule
            .onNodeWithText("Broken: Unsupported interface type 'AndroidBLE'")
            .assertIsDisplayed()
    }

    @Test
    fun `suggestion card shows the parsed interface and fires on add`() {
        var added = false
        val parsed =
            InterfaceConfigCodec
                .parseSnippet(
                    """
                    [[Central Oklahoma Reticulum Hub]]
                      type = BackboneInterface
                      enabled = yes
                      remote = reticulum.mcswain.dev
                      target_port = 4242
                    """.trimIndent(),
                ).interfaces

        composeRule.setContent {
            MaterialTheme {
                InterfaceConfigSuggestionCard(parsed = parsed, onAdd = { added = true })
            }
        }

        composeRule.onNodeWithText("Reticulum interface").assertIsDisplayed()
        composeRule.onNodeWithText("Central Oklahoma Reticulum Hub").assertIsDisplayed()
        composeRule.onNodeWithText("Add to interfaces").performClick()
        assertTrue(added)
    }

    @Test
    fun `type labels and endpoint summaries cover supported types`() {
        assertEquals("TCP client", tcp.importTypeLabel())
        assertEquals("reticulum.mcswain.dev:4242", tcp.endpointSummary())
        assertEquals(
            "TCP server",
            InterfaceConfig.TCPServer(name = "S", listenIp = "0.0.0.0", listenPort = 4242).importTypeLabel(),
        )
    }
}
