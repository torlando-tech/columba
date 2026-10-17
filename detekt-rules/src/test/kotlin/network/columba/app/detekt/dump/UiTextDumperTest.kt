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
}
