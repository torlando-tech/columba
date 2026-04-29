package network.columba.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscardedConcurrencyReturnRuleTest {
    private val rule = DiscardedConcurrencyReturnRule(Config.empty)

    // ===== awaitTermination =====

    @Test
    fun `flags discarded awaitTermination — the COLUMBA-8R archetype`() {
        val code =
            """
            package com.example

            import java.util.concurrent.ExecutorService
            import java.util.concurrent.TimeUnit

            fun shutdown(executor: ExecutorService) {
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                // bug: code below proceeds even if false (tasks still running)
                close()
            }

            fun close() {}
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "should flag the discarded awaitTermination")
        assertTrue(findings[0].message.contains("awaitTermination"))
        assertTrue(findings[0].message.contains("COLUMBA-8R"))
    }

    @Test
    fun `does not flag awaitTermination whose return is captured into a variable`() {
        val code =
            """
            package com.example

            import java.util.concurrent.ExecutorService
            import java.util.concurrent.TimeUnit

            fun shutdown(executor: ExecutorService) {
                executor.shutdown()
                val drained = executor.awaitTermination(5, TimeUnit.SECONDS)
                if (!drained) executor.shutdownNow()
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "captured-into-val should be allowed")
    }

    @Test
    fun `does not flag awaitTermination used as if condition`() {
        val code =
            """
            package com.example

            import java.util.concurrent.ExecutorService
            import java.util.concurrent.TimeUnit

            fun shutdown(executor: ExecutorService) {
                executor.shutdown()
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "if-condition usage consumes the value")
    }

    @Test
    fun `does not flag awaitTermination used as the last expression of a lambda`() {
        // Common pattern: runCatching { executor.awaitTermination(...) }
        // The value is the lambda's return — caller may chain on it.
        val code =
            """
            package com.example

            import java.util.concurrent.ExecutorService
            import java.util.concurrent.TimeUnit

            fun shutdown(executor: ExecutorService): Boolean {
                return runCatching { executor.awaitTermination(5, TimeUnit.SECONDS) }
                    .getOrDefault(false)
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "last-expr-of-lambda is the lambda's return")
    }

    // ===== tryLock =====

    @Test
    fun `flags discarded tryLock`() {
        val code =
            """
            package com.example

            import java.util.concurrent.locks.ReentrantLock

            fun bad(lock: ReentrantLock) {
                lock.tryLock()  // bug: doesn't check return
                doProtectedWork()
                lock.unlock()
            }

            fun doProtectedWork() {}
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size, "discarded tryLock must fire")
        assertTrue(findings[0].message.contains("tryLock"))
    }

    @Test
    fun `does not flag tryLock used as if condition`() {
        val code =
            """
            package com.example

            import java.util.concurrent.locks.ReentrantLock

            fun ok(lock: ReentrantLock) {
                if (lock.tryLock()) {
                    try {
                        doProtectedWork()
                    } finally {
                        lock.unlock()
                    }
                }
            }

            fun doProtectedWork() {}
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size)
    }

    // ===== offer =====

    @Test
    fun `flags discarded channel offer`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.channels.Channel

            fun produce(ch: Channel<Int>, value: Int) {
                ch.offer(value)  // bug: silently drops on full
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(1, findings.size)
        assertTrue(findings[0].message.contains("offer"))
    }

    @Test
    fun `does not flag offer used as boolean expression`() {
        val code =
            """
            package com.example

            import kotlinx.coroutines.channels.Channel

            fun produce(ch: Channel<Int>, value: Int) {
                if (!ch.offer(value)) {
                    handleBackpressure()
                }
            }

            fun handleBackpressure() {}
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag zero-arg offer (likely unrelated method)`() {
        // e.g. someone defined `fun offer() = 42` somewhere — out of scope.
        val code =
            """
            package com.example

            class Counter {
                private var n = 0
                fun offer() { n++ }
            }

            fun use(c: Counter) {
                c.offer()  // unrelated to Channel.offer — should not flag
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "0-arg offer is out of scope (heuristic exclusion)")
    }

    // ===== chained calls =====

    @Test
    fun `does not flag tryLock when its result is consumed by a chained call`() {
        // tryLock().also { ... } — the result IS consumed (passed to .also)
        val code =
            """
            package com.example

            import java.util.concurrent.locks.ReentrantLock

            fun chained(lock: ReentrantLock) {
                lock.tryLock().also { acquired ->
                    if (!acquired) error("blocked")
                }
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "result piped into .also IS consumed")
    }

    // ===== suppression =====

    @Test
    fun `respects @Suppress annotation`() {
        val code =
            """
            package com.example

            import java.util.concurrent.ExecutorService
            import java.util.concurrent.TimeUnit

            @Suppress("DiscardedConcurrencyReturn")
            fun deliberate(executor: ExecutorService) {
                executor.awaitTermination(0, TimeUnit.SECONDS)
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size, "@Suppress should silence the rule")
    }

    // ===== unrelated methods =====

    @Test
    fun `does not flag unrelated method names`() {
        val code =
            """
            package com.example

            fun unrelated() {
                println("hello")
                listOf(1, 2, 3).forEach { println(it) }
                ("foo").lowercase()
            }
            """.trimIndent()

        val findings = rule.lint(code)
        assertEquals(0, findings.size)
    }
}
