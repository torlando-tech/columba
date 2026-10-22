package network.columba.app.detekt.dump

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UiTextDumperTest {
    @TempDir
    lateinit var root: File

    private fun module(
        name: String,
        pkg: String,
        resXml: String? = null,
    ) {
        val dir = root.resolve(name).apply { mkdirs() }
        dir.resolve("build.gradle.kts").writeText("""android { namespace = "$pkg" }""")
        if (resXml != null) {
            val values = dir.resolve("src/main/res/values").apply { mkdirs() }
            values.resolve("strings.xml").writeText("<resources>$resXml</resources>")
        }
    }

    private fun dump(
        moduleName: String,
        code: String,
    ): List<UiTextDumper.Entry> = UiTextDumper(ResResolver(root), moduleName).dump(DumpEnv.parse("Test.kt", code))

    @Test
    fun `literal sink emits skeleton`() {
        val entries = dump("a", "fun s() { Text(\"Save changes?\") }")
        assertEquals(1, entries.size)
        assertEquals("Text arg0", entries[0].kind)
        assertEquals("Save changes?", entries[0].skeleton)
    }

    @Test
    fun `pure extraction produces identical skeleton`() {
        module("a", "com.test.a", """<string name="hello">Hello %1${'$'}s!</string>""")
        val literal = dump("a", "fun s(n: String) { Text(\"Hello \$n!\") }")
        val resource = dump("a", "fun s() { Text(stringResource(R.string.hello, name)) }")
        assertEquals(listOf("Hello {}!"), literal.map { it.skeleton })
        assertEquals(literal.map { it.kind to it.skeleton }, resource.map { it.kind to it.skeleton })
    }

    @Test
    fun `getString resolves like stringResource`() {
        module("a", "com.test.a", """<string name="hello">Hello %1${'$'}s!</string>""")
        val resource = dump("a", "fun s(ctx: android.content.Context) { Text(ctx.getString(R.string.hello, n)) }")
        assertEquals(listOf("Hello {}!"), resource.map { it.skeleton })
    }

    @Test
    fun `plurals emit one and other`() {
        module(
            "a",
            "com.test.a",
            """<plurals name="msgs"><item quantity="one">%d message</item><item quantity="other">%d messages</item></plurals>""",
        )
        val entries = dump("a", "fun s(n: Int) { Text(pluralStringResource(R.plurals.msgs, n)) }")
        assertEquals(listOf("[one]", "[other]"), entries.map { it.kind.removePrefix("Text arg0" ) })
        assertEquals(listOf("{} message", "{} messages"), entries.map { it.skeleton })
    }

    @Test
    fun `concatenation joins parts`() {
        val entries = dump("a", "fun s(name: String) { Text(\"Hello \" + name) }")
        assertEquals(listOf("Hello {}"), entries.map { it.skeleton })
    }

    @Test
    fun `cross-module R resolves via namespace`() {
        module("b", "com.test.b", """<string name="title">Shared Title</string>""")
        val entries = dump("a", "fun s() { Text(com.test.b.R.string.title) }")
        assertEquals(listOf("Shared Title"), entries.map { it.skeleton })
    }

    @Test
    fun `unresolvable resource emits opaque entry`() {
        val entries = dump("a", "fun s() { Text(stringResource(R.string.missing)) }")
        assertEquals(1, entries.size)
        assertEquals("{}", entries[0].skeleton)
    }

    @Test
    fun `preview functions are exempt`() {
        val code =
            """
            @Preview
            fun PreviewScreen() { Text("Preview sample") }

            fun RealScreen() { Text("Real text") }
            """.trimIndent()
        val entries = dump("a", code)
        assertEquals(listOf("Real text"), entries.map { it.skeleton })
    }

    @Test
    fun `assignment and enum sinks emit`() {
        val code =
            """
            enum class State(val displayName: String) {
                ON("Powered on"),
            }
            fun s(sem: SemanticsPropertyReceiver) { sem.contentDescription = "Power off" }
            """.trimIndent()
        val entries = dump("a", code)
        assertEquals(listOf("State.displayName", "contentDescription assignment"), entries.map { it.kind })
        assertEquals(listOf("Powered on", "Power off"), entries.map { it.skeleton })
    }

    @Test
    fun `named sink on composable emits, animation label does not`() {
        val code =
            """
            fun s() {
                Button(text = "Confirm")
                animateFloatAsState(label = "spin")
            }
            """.trimIndent()
        val entries = dump("a", code)
        assertEquals(listOf("Button.text"), entries.map { it.kind })
    }

    @Test
    fun `ordinals follow source order`() {
        val code =
            """
            fun s() {
                Text("First")
                Text("Second")
                Toast.makeText(ctx, "Third", len)
            }
            """.trimIndent()
        val entries = dump("a", code)
        assertEquals(listOf(0, 1, 2), entries.map { it.ordinal })
        assertEquals(listOf("First", "Second", "Third"), entries.map { it.skeleton })
    }

    @Test
    fun `backslash-n resource matches literal newline`() {
        module("a", "com.test.a", """<string name="two">Line one\nLine two</string>""")
        val literal = dump("a", "fun s() { Text(\"Line one\\nLine two\") }")
        val resource = dump("a", "fun s() { Text(stringResource(R.string.two)) }")
        assertEquals(listOf("Line one Line two"), literal.map { it.skeleton })
        assertEquals(literal.map { it.skeleton }, resource.map { it.skeleton })
    }

    @Test
    fun `double percent collapses only in format strings`() {
        module(
            "a",
            "com.test.a",
            """<string name="pct">Importing %1${'$'}d%%</string><string name="lit">Short-term (%)</string>""",
        )
        val fmt = dump("a", "fun s() { Text(stringResource(R.string.pct, n)) }")
        assertEquals(listOf("Importing {}%"), fmt.map { it.skeleton })
        val lit = dump("a", "fun s() { Text(stringResource(R.string.lit)) }")
        assertEquals(listOf("Short-term (%)"), lit.map { it.skeleton })
    }

    @Test
    fun `quoted resource strips wrapping quotes`() {
        module("a", "com.test.a", """<string name="f">"Filtering: "</string>""")
        val literal = dump("a", "fun s() { Text(\"Filtering: \") }")
        val resource = dump("a", "fun s() { Text(stringResource(R.string.f)) }")
        assertEquals(literal.map { it.skeleton }, resource.map { it.skeleton })
    }

    @Test
    fun `Res-suffixed enum param normalizes to base sink`() {
        module("a", "com.test.a", """<string name="band">902-928 MHz ISM band</string>""")
        val code =
            """
            enum class FrequencyRegion(val descriptionRes: Int) {
                US(R.string.band)
            }
            """.trimIndent()
        val entries = dump("a", code)
        assertEquals(listOf("FrequencyRegion.description"), entries.map { it.kind })
        assertEquals(listOf("902-928 MHz ISM band"), entries.map { it.skeleton })
    }

    @Test
    fun `local val holding a resource resolves at the sink`() {
        module("a", "com.test.a", """<string name="send">Send message</string>""")
        val code =
            """
            fun s() {
                val cd = stringResource(R.string.send)
                Icon(painter, contentDescription = cd)
            }
            """.trimIndent()
        val entries = dump("a", code)
        assertEquals(listOf("Send message"), entries.map { it.skeleton })
    }

    @Test
    fun `local val does not leak across functions`() {
        module("a", "com.test.a", """<string name="send">Send message</string>""")
        val code =
            """
            fun a() { val cd = stringResource(R.string.send) }
            fun b() { Icon(painter, contentDescription = cd) }
            """.trimIndent()
        val entries = dump("a", code)
        assertEquals(listOf("{}"), entries.map { it.skeleton })
    }

    @Test
    fun `import-aliased cross-module R resolves`() {
        module("a", "com.test.a")
        module("host", "com.test.host", """<string name="done">Flashing complete!</string>""")
        val code =
            """
            import com.test.host.R as HostR
            fun s() { Text(stringResource(HostR.string.done)) }
            """.trimIndent()
        assertEquals(listOf("Flashing complete!"), dump("a", code).map { it.skeleton })
    }

    @Test
    fun `literal format args substitute into resource text`() {
        module("a", "com.test.a", """<string name="step">Step %1${'$'}d: %2${'$'}s</string>""")
        val code = "fun s() { Text(stringResource(R.string.step, 1, \"Scan to connect\")) }"
        assertEquals(listOf("Step 1: Scan to connect"), dump("a", code).map { it.skeleton })
    }

    @Test
    fun `opaque format arg stays placeholder and percent collapses`() {
        module("a", "com.test.a", """<string name="n">Importing %1${'$'}d%%</string>""")
        val code = "fun s(n: Int) { Text(stringResource(R.string.n, n)) }"
        assertEquals(listOf("Importing {}%"), dump("a", code).map { it.skeleton })
    }

    @Test
    fun `swapped format args are detected`() {
        module("a", "com.test.a", """<string name="step">Step %1${'$'}d: %2${'$'}s</string>""")
        val swapped = dump("a", "fun s() { Text(stringResource(R.string.step, 2, \"Wrong\")) }")
        assertEquals(listOf("Step 2: Wrong"), swapped.map { it.skeleton })
    }

    @Test
    fun `percent-space-word prose is not a format specifier`() {
        module("a", "com.test.a", """<string name="duty">%1${'$'}d%% duty</string>""")
        val literal = dump("a", "fun s(d: Int) { Text(\"${'$'}{d}% duty\") }")
        val resource = dump("a", "fun s(d: Int) { Text(stringResource(R.string.duty, d)) }")
        assertEquals(listOf("{}% duty"), literal.map { it.skeleton })
        assertEquals(literal.map { it.skeleton }, resource.map { it.skeleton })
    }

    @Test
    fun `plural quantity is not a format argument`() {
        module(
            "a",
            "com.test.a",
            """<plurals name="shared"><item quantity="one">%1${'$'}s shared a file</item><item quantity="other">%1${'$'}s shared files</item></plurals>""",
        )
        val code = "fun s() { Text(pluralStringResource(R.plurals.shared, 2, \"Alice\")) }"
        assertEquals(
            listOf("Alice shared a file", "Alice shared files"),
            dump("a", code).map { it.skeleton },
        )
    }

    @Test
    fun `shadowed local val resolves opaquely`() {
        module("a", "com.test.a", """<string name="outer">Outer</string><string name="inner">Inner</string>""")
        val code =
            """
            fun s() {
                val label = stringResource(R.string.outer)
                run { val label = stringResource(R.string.inner) }
                Text(label)
            }
            """.trimIndent()
        assertEquals(listOf("{}"), dump("a", code).map { it.skeleton })
    }

    @Test
    fun `single local val still resolves`() {
        module("a", "com.test.a", """<string name="outer">Outer</string>""")
        val code =
            """
            fun s() {
                val label = stringResource(R.string.outer)
                run { doThing() }
                Text(label)
            }
            """.trimIndent()
        assertEquals(listOf("Outer"), dump("a", code).map { it.skeleton })
    }

    @Test
    fun `ui message text constructor emits resolved entry`() {
        module("a", "com.test.a", """<string name="bt_unavailable">Bluetooth unavailable</string>""")
        val entries = dump("a", "fun s() { val m = UiMessage.Text(R.string.bt_unavailable) }")
        assertEquals(listOf("UiMessage.Text" to "Bluetooth unavailable"), entries.map { it.kind to it.skeleton })
    }

    @Test
    fun `ui message text substitutes listOf args`() {
        module("a", "com.test.a", """<string name="failed">Failed: %1${'$'}s</string>""")
        val entries = dump("a", "fun s(d: String) { val m = UiMessage.Text(R.string.failed, listOf(d)) }")
        assertEquals(listOf("Failed: {}"), entries.map { it.skeleton })
    }

    @Test
    fun `ui message plural emits one and other`() {
        module(
            "a",
            "com.test.a",
            """<plurals name="msgs"><item quantity="one">%d message</item><item quantity="other">%d messages</item></plurals>""",
        )
        val entries = dump("a", "fun s(n: Int) { val m = UiMessage.Plural(R.plurals.msgs, n) }")
        assertEquals(
            listOf("UiMessage.Plural[one]", "UiMessage.Plural[other]"),
            entries.map { it.kind },
        )
        assertEquals(listOf("{} message", "{} messages"), entries.map { it.skeleton })
    }

    @Test
    fun `flash text resource emits entry`() {
        module("a", "com.test.a", """<string name="erasing">Erasing %1${'$'}d KB</string>""")
        val entries = dump("a", "fun s(k: Int) { val t = FlashText.Resource(R.string.erasing, listOf(k)) }")
        assertEquals(listOf("Erasing {} KB"), entries.map { it.skeleton })
    }

    @Test
    fun `nested ui message argument resolves inside wrapper`() {
        module(
            "a",
            "com.test.a",
            """<string name="wrapper">Something failed: %1${'$'}s</string><string name="inner">inner text</string>""",
        )
        val code =
            """
            fun s() {
                val m = UiMessage.Text(R.string.wrapper, listOf(UiMessage.Text(R.string.inner)))
            }
            """.trimIndent()
        // children visit first: inner entry, then the wrapper with it substituted
        assertEquals(listOf("inner text", "Something failed: inner text"), dump("a", code).map { it.skeleton })
    }

    @Test
    fun `plain Text call with non-resource first arg emits no carrier entry`() {
        module("a", "com.test.a", """<string name="outer">Outer</string>""")
        val entries = dump("a", "fun s() { val label = stringResource(R.string.outer); Text(label) }")
        assertEquals(1, entries.size)
        assertEquals("Text arg0", entries[0].kind)
    }
}
