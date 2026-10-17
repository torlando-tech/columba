package network.columba.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoHardcodedUiStringRuleTest {

    private val rule = NoHardcodedUiString(Config.empty)

    @Test
    fun `Text with literal reports`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Text("Hello world")
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
        assertEquals("NoHardcodedUiString", findings[0].id)
    }

    @Test
    fun `Text with named text arg reports`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Text(text = "Cancel")
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun `Text with stringResource does not report`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Text(stringResource(R.string.call_answer))
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun `format-only templates do not report`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Text("${'$'}it dBm")
                    Text("#RRGGBB")
                    Text("host:port")
                    Text("SF${'$'}it")
                    Text("4/${'$'}it")
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun `interpolated literal with prose reports`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Text("Announce from ${'$'}peerName")
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun `positional Icon contentDescription reports`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Icon(Icons.Default.Close, "Close")
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun `named contentDescription reports`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun `named sinks on PascalCase callee report`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    MyDialog(title = "Delete?", message = "Are you sure?")
                    ListItem(headlineContent = "Saved", supportingContent = "Saved to device")
                }
                """.trimIndent(),
            )
        assertEquals(4, findings.size)
    }

    @Test
    fun `animation labels on lowercase callees are exempt`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    val a = animateFloatAsState(label = "alpha")
                    val t = rememberInfiniteTransition(label = "pulse")
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun `snackbar and toast sinks report`() {
        val findings =
            rule.lint(
                """
                suspend fun screen() {
                    showSnackbar("Message sent")
                    showSnackbar(message = "Message sent", actionLabel = "Undo")
                    Toast.makeText(context, "Saved", 0)
                    Snackbar.make(view, "Saved", 0)
                }
                """.trimIndent(),
            )
        assertEquals(5, findings.size)
    }

    @Test
    fun `notification channel and builder sinks report`() {
        val findings =
            rule.lint(
                """
                fun build() {
                    NotificationChannel("messages", "Messages", 3)
                    builder.setContentTitle("New message")
                    builder.setContentText("from peer")
                    channel.description = "Notifications for received messages"
                }
                """.trimIndent(),
            )
        assertEquals(4, findings.size)
    }

    @Test
    fun `semantics property assignments report`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Modifier.semantics {
                        contentDescription = "Active filters. Tap to edit."
                        stateDescription = "Sending message"
                    }
                }
                """.trimIndent(),
            )
        assertEquals(2, findings.size)
    }

    @Test
    fun `enum display fields report but wire keys do not`() {
        val findings =
            rule.lint(
                """
                enum class CodecProfile(val displayName: String, val description: String) {
                    ULTRA("Ultra low", "Smallest bandwidth"),
                }
                enum class Aspect(val key: String) {
                    CHAT("chat"),
                }
                """.trimIndent(),
            )
        assertEquals(2, findings.size)
    }

    @Test
    fun `preview sample data is exempt`() {
        val findings =
            rule.lint(
                """
                @Preview
                fun PreviewCard() {
                    Text("Sample photo.jpg")
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun `log calls and string comparisons are not sinks`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Log.d(TAG, "Janky frame happened")
                    if (state == "pending") {
                        Text("Waiting")
                    }
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size) // only the Text fires
    }

    @Test
    fun `concatenated literals report once per prose part`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Text("Total: " + "amount")
                }
                """.trimIndent(),
            )
        assertEquals(2, findings.size)
    }

    @Test
    fun `contentDescription null is fine`() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
