package network.columba.app.detekt.rules

import io.github.detekt.test.utils.compileContentForTest
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Finding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [NoCallCoordinatorGetInstanceOutsideHostRule].
 *
 * The rule's allowlist is path-based (`/rns-host/`, `/rns-backend-kt/`), so the
 * tests can't use the simple `rule.lint(code)` API — that doesn't let us set a
 * virtual file path. Instead we compile the snippet under a synthetic path via
 * `compileContentForTest` and invoke the visitor directly, then read `findings`.
 *
 * A fresh [NoCallCoordinatorGetInstanceOutsideHostRule] is constructed per
 * test because `Rule.findings` is mutable state across visits.
 */
class NoCallCoordinatorGetInstanceOutsideHostRuleTest {
    @Test
    fun `flags qualified CallCoordinator getInstance in app source`() {
        val source =
            """
            package network.columba.app.viewmodel
            import tech.torlando.lxst.core.CallCoordinator
            class CallViewModel {
                private val coordinator = CallCoordinator.getInstance()
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/path/to/repo/app/src/main/java/network/columba/app/viewmodel/CallViewModel.kt",
            )
        assertEquals(1, findings.size, "Should flag UI-side getInstance call")
        assertEquals("NoCallCoordinatorGetInstanceOutsideHost", findings[0].issue.id)
    }

    @Test
    fun `flags fully qualified CallCoordinator getInstance in app source`() {
        val source =
            """
            package network.columba.app.viewmodel
            class CallViewModel {
                private val coordinator = tech.torlando.lxst.core.CallCoordinator.getInstance()
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/path/to/repo/app/src/main/java/network/columba/app/viewmodel/CallViewModel.kt",
            )
        assertEquals(1, findings.size, "FQN form should also be flagged")
    }

    @Test
    fun `allows CallCoordinator getInstance in rns-host source`() {
        val source =
            """
            package network.columba.app.rns.host
            import tech.torlando.lxst.core.CallCoordinator
            class HostBackendModule {
                fun provideCallCoordinator() = CallCoordinator.getInstance()
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/path/to/repo/rns-host/src/kotlinBackend/kotlin/network/columba/app/rns/host/HostBackendModule.kt",
            )
        assertEquals(0, findings.size, "rns-host is allowlisted")
    }

    @Test
    fun `allows CallCoordinator getInstance in rns-backend-kt source`() {
        val source =
            """
            package network.columba.app.rns.backend.kt
            import tech.torlando.lxst.core.CallCoordinator
            class NativeCallManager {
                private val coordinator = CallCoordinator.getInstance()
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/path/to/repo/rns-backend-kt/src/main/kotlin/network/columba/app/rns/backend/kt/NativeCallManager.kt",
            )
        assertEquals(0, findings.size, "rns-backend-kt is allowlisted")
    }

    @Test
    fun `ignores unrelated singleton getInstance calls`() {
        val source =
            """
            package network.columba.app.foo
            object Foo {
                fun getInstance() = Foo
            }
            class Bar {
                private val foo = Foo.getInstance()
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/path/to/repo/app/src/main/java/network/columba/app/foo/Bar.kt",
            )
        assertEquals(0, findings.size, "Non-CallCoordinator getInstance must not be flagged")
    }

    @Test
    fun `flagged message names the allowlisted modules`() {
        val source =
            """
            package network.columba.app.viewmodel
            import tech.torlando.lxst.core.CallCoordinator
            class CallViewModel {
                private val coordinator = CallCoordinator.getInstance()
            }
            """.trimIndent()
        val findings =
            findingsFor(
                source,
                "/path/to/repo/app/src/main/java/network/columba/app/viewmodel/CallViewModel.kt",
            )
        assertEquals(1, findings.size)
        val message = findings[0].message
        assertTrue(message.contains(":rns-host"), "Message names the rns-host allowlist: $message")
        assertTrue(message.contains(":rns-backend-kt"), "Message names the rns-backend-kt allowlist: $message")
    }

    private fun findingsFor(source: String, path: String): List<Finding> {
        val rule = NoCallCoordinatorGetInstanceOutsideHostRule(Config.empty)
        val ktFile = compileContentForTest(source, path)
        rule.visitFile(ktFile)
        return rule.findings
    }
}
