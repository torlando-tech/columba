package network.columba.app.detekt.dump

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end dump output for the sink-equivalence gate: the diff must reflect
 * displayed text only, never file presence, so ordinary file adds/deletes
 * cannot fail the gate.
 */
class DumpMainTest {
    @TempDir
    lateinit var root: File

    private fun dumpMain(vararg files: String): String {
        val out = root.resolve("dump.txt")
        main(arrayOf("--root", root.path, "--out", out.path, "--files", files.joinToString(",")))
        return out.readText()
    }

    @Test
    fun `missing file emits nothing`() {
        assertEquals("", dumpMain("a/src/main/Missing.kt"))
    }

    @Test
    fun `file without text emits no heading`() {
        val src = root.resolve("a/src/main").apply { mkdirs() }
        src.resolve("Plain.kt").writeText("package p\n\nfun plain() = 1")
        assertEquals("", dumpMain("a/src/main/Plain.kt"))
    }

    @Test
    fun `file with text emits heading and entry`() {
        val src = root.resolve("a/src/main").apply { mkdirs() }
        src.resolve("Ui.kt").writeText("package p\n\nfun ui() { Text(\"Save\") }")
        val dump = dumpMain("a/src/main/Ui.kt")
        assertTrue(dump.startsWith("### a/src/main/Ui.kt"), dump)
        assertTrue(dump.contains("| Save"), dump)
    }

    @Test
    fun `added text-free file dumps identically on both trees`() {
        // base tree: only A.kt; head tree: A.kt + New.kt, neither with text.
        val src = root.resolve("a/src/main").apply { mkdirs() }
        src.resolve("A.kt").writeText("package p\n\nfun a() = 1")
        val base = dumpMain("a/src/main/A.kt", "a/src/main/New.kt")
        src.resolve("New.kt").writeText("package p\n\nfun b() = 2")
        val head = dumpMain("a/src/main/A.kt", "a/src/main/New.kt")
        assertEquals(base, head)
    }
}
