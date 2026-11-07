package network.columba.app.ui.components

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.Locale

/**
 * Text-goldens harness for render-level before/after proof (issue #24).
 *
 * Renders a component under Robolectric, walks the unmerged semantics tree in
 * document order collecting every visible string (Text, EditableText and
 * ContentDescription semantics), and compares the result against a golden
 * JSON file under `app/src/test/resources/goldens/`.
 *
 * Record mode: run the unit-test task with `-PrecordTexts` (wired through
 * `app/build.gradle.kts` as the `recordTexts` system property) to (re)write
 * the golden files instead of asserting against them.
 */
object TextGoldens {
    /** 2024-06-01T12:00:00Z — best-effort clock anchor (see [freezeClock]). */
    const val FIXED_NOW_MILLIS: Long = 1_717_243_200_000L

    /**
     * Call from `@Before`: pins the locale to US and best-effort advances the
     * Robolectric clock. Components that read `System.currentTimeMillis()`
     * directly may still observe live wall-clock time, so any fake timestamp
     * that feeds relative-time formatting MUST be computed relative to
     * `System.currentTimeMillis()` at test time with a half-step margin.
     */
    fun freezeClock() {
        Locale.setDefault(Locale.US)
        ShadowSystemClock.advanceBy(
            Duration.ofMillis(FIXED_NOW_MILLIS - ShadowSystemClock.currentTimeMillis()),
        )
    }

    /**
     * Ordered list of every string a rendered component exposes: visible text,
     * editable text values, and content descriptions, in unmerged-tree
     * document order.
     */
    fun dumpTexts(rule: ComposeContentTestRule): List<String> {
        rule.waitForIdle()
        val out = mutableListOf<String>()
        // Dialogs/popups add extra semantics roots; walk every root in order.
        rule.onAllNodes(isRoot(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .forEach { root -> walk(root, out) }
        return out
    }

    /** Dumps [rule] and records or asserts the golden named [name]. */
    fun assertGolden(name: String, rule: ComposeContentTestRule) {
        assertOrRecord(name, dumpTexts(rule))
    }

    /** Records the golden [name] in record mode; otherwise asserts equality. */
    fun assertOrRecord(name: String, texts: List<String>) {
        val file = goldenFile(name)
        val json = toJson(texts)
        if (recording) {
            file.parentFile?.mkdirs()
            file.writeText(json, Charsets.UTF_8)
            return
        }
        assertTrue(
            "Golden file '${file.path}' is missing. Re-run with -PrecordTexts to generate it.",
            file.exists(),
        )
        assertEquals(
            "Rendered texts differ from golden '$name' (re-record with -PrecordTexts if the change is intentional)",
            file.readText(Charsets.UTF_8).trim(),
            json.trim(),
        )
    }

    private val recording: Boolean
        get() = System.getProperty("recordTexts").orEmpty()
            .let { it.isNotEmpty() && !it.equals("false", ignoreCase = true) }

    private fun walk(node: SemanticsNode, out: MutableList<String>) {
        val config = node.config
        if (config.contains(SemanticsProperties.Text)) {
            val joined = config[SemanticsProperties.Text].joinToString("") { it.text }
            if (joined.isNotEmpty()) out.add(joined)
        }
        if (config.contains(SemanticsProperties.EditableText)) {
            val value = config[SemanticsProperties.EditableText].text
            if (value.isNotEmpty()) out.add(value)
        }
        if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription].forEach { out.add(it) }
        }
        node.children.forEach { walk(it, out) }
    }

    private fun goldenFile(name: String): File = File(goldenDir(), "$name.json")

    private fun goldenDir(): File {
        val cwd = File("").absoluteFile
        val local = File(cwd, "src/test/resources/goldens")
        if (File(cwd, "settings.gradle.kts").exists()) return File(cwd, "app/src/test/resources/goldens")
        if (local.isDirectory || File(cwd, "src").isDirectory) return local
        var dir: File = cwd
        while (File(dir, "settings.gradle.kts").let { !it.exists() } && dir.parentFile != null) {
            dir = dir.parentFile
        }
        return File(dir, "app/src/test/resources/goldens")
    }

    private fun toJson(values: List<String>): String =
        buildString {
            append("[\n")
            values.forEachIndexed { index, value ->
                append("  ")
                append(quote(value))
                if (index != values.lastIndex) append(',')
                append('\n')
            }
            append("]\n")
        }

    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEach { ch ->
                when {
                    ch == '"' -> append("\\\"")
                    ch == '\\' -> append("\\\\")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch.code < 0x20 -> append("\\u").append(String.format(Locale.US, "%04x", ch.code))
                    else -> append(ch)
                }
            }
            append('"')
        }
}
