package network.columba.app.detekt.rules

import io.github.detekt.test.utils.compileContentForTest
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Finding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ReflectivelyKeptRequiredRule].
 *
 * The rule is path-scoped (`/rns-backend-py/`, `/rns-host/`) and package-scoped
 * (the `fun interface` shape), so — like [NoCallCoordinatorGetInstanceOutsideHostRuleTest]
 * — tests compile the snippet under a synthetic virtual path via
 * `compileContentForTest` and invoke the visitor directly.
 */
class ReflectivelyKeptRequiredRuleTest {
    @Test
    fun `flags a fun interface in rns-backend-py without the annotation`() {
        val source =
            """
            package network.columba.app.rns.backend.py
            import com.chaquo.python.PyObject
            fun interface PyThreeArgCallback {
                fun onEvent(a: PyObject, b: PyObject, c: PyObject)
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/repo/rns-backend-py/src/main/kotlin/network/columba/app/rns/backend/py/PyThreeArgCallback.kt",
            )
        assertEquals(1, findings.size, "Unannotated Chaquopy SAM should be flagged")
        assertEquals("ReflectivelyKeptRequired", findings[0].issue.id)
    }

    @Test
    fun `allows a fun interface in rns-backend-py with the annotation`() {
        val source =
            """
            package network.columba.app.rns.backend.py
            import com.chaquo.python.PyObject
            import network.columba.app.rns.api.annotation.ReflectivelyKept
            @ReflectivelyKept
            fun interface PyThreeArgCallback {
                fun onEvent(a: PyObject, b: PyObject, c: PyObject)
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/repo/rns-backend-py/src/main/kotlin/network/columba/app/rns/backend/py/PyThreeArgCallback.kt",
            )
        assertEquals(0, findings.size, "Annotated SAM must not be flagged")
    }

    @Test
    fun `flags a Kotlin Bridge class without the annotation`() {
        val source =
            """
            package network.columba.app.rns.host.thing
            class KotlinThingBridge {
                fun doThing() = Unit
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/repo/rns-host/src/main/kotlin/network/columba/app/rns/host/thing/KotlinThingBridge.kt",
            )
        assertEquals(1, findings.size, "Unannotated Kotlin*Bridge should be flagged")
    }

    @Test
    fun `allows a Kotlin Bridge class with the annotation`() {
        val source =
            """
            package network.columba.app.rns.host.thing
            import network.columba.app.rns.api.annotation.ReflectivelyKept
            @ReflectivelyKept
            class KotlinThingBridge {
                fun doThing() = Unit
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/repo/rns-host/src/main/kotlin/network/columba/app/rns/host/thing/KotlinThingBridge.kt",
            )
        assertEquals(0, findings.size, "Annotated Kotlin*Bridge must not be flagged")
    }

    @Test
    fun `does not flag an Android callback class that is not a Chaquopy bridge`() {
        // AndroidRNodeHostBridge / GattCallback / ProgressCallback shapes — not
        // Python-invoked, must not trip the rule.
        val source =
            """
            package network.columba.app.rns.host.rnode
            internal class AndroidRNodeHostBridge {
                interface ProgressCallback { fun onProgress(p: Int) }
                inner class GattCallback
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/repo/rns-host/src/kotlinBackend/kotlin/network/columba/app/rns/host/rnode/AndroidRNodeHostBridge.kt",
            )
        assertEquals(0, findings.size, "Non-Chaquopy callbacks/bridges must not be flagged")
    }

    @Test
    fun `does not flag a fun interface outside the python backend package`() {
        val source =
            """
            package network.columba.app.viewmodel
            fun interface ClickHandler {
                fun onClick()
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/repo/app/src/main/java/network/columba/app/viewmodel/ClickHandler.kt",
            )
        assertEquals(0, findings.size, "fun interfaces outside :rns-backend-py are unrelated")
    }

    @Test
    fun `flagged message names the annotation`() {
        val source =
            """
            package network.columba.app.rns.backend.py
            import com.chaquo.python.PyObject
            fun interface PyThreeArgCallback {
                fun onEvent(a: PyObject, b: PyObject, c: PyObject)
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/repo/rns-backend-py/src/main/kotlin/network/columba/app/rns/backend/py/PyThreeArgCallback.kt",
            )
        assertEquals(1, findings.size)
        assertTrue(
            findings[0].message.contains("ReflectivelyKept"),
            "Message should name the annotation: ${findings[0].message}",
        )
    }

    private fun findingsFor(
        source: String,
        path: String,
    ): List<Finding> {
        val rule = ReflectivelyKeptRequiredRule(Config.empty)
        val ktFile = compileContentForTest(source, path)
        rule.visitFile(ktFile)
        return rule.findings
    }
}
