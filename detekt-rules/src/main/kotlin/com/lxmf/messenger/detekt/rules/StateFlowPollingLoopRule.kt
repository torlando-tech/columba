package com.lxmf.messenger.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Detekt rule to detect StateFlow polling loops that can cause infinite loops in tests.
 *
 * The pattern `while (stateFlow.value is X) { delay() }` is dangerous because:
 * 1. With UnconfinedTestDispatcher, delay() executes immediately without advancing time
 * 2. If test mocks don't properly update the StateFlow, the loop runs forever
 * 3. This causes CI timeouts that are hard to debug
 *
 * Example problematic pattern:
 * ```kotlin
 * while (callState.value is CallState.Active) {
 *     delay(1000)
 *     _duration.value += 1
 * }
 * ```
 *
 * Safer alternatives:
 * 1. Use `callState.collectLatest { if (it is Active) { ... } }` - automatically cancels
 * 2. Use a cancellable Job that's explicitly cancelled when state changes
 * 3. Ensure tests use `answers { }` to update StateFlow, not `just Runs`
 *
 * This rule warns but doesn't prevent the pattern - sometimes it's the right choice.
 * The warning reminds developers to ensure proper test coverage.
 */
class StateFlowPollingLoopRule(
    config: Config = Config.empty,
) : Rule(config) {
    override val issue =
        Issue(
            id = "StateFlowPollingLoop",
            severity = Severity.Warning,
            description =
                "while loop checking StateFlow.value with delay() can cause infinite loops in tests. " +
                    "Ensure test mocks update the StateFlow, or consider using collectLatest instead.",
            debt = Debt.TEN_MINS,
        )

    override fun visitWhileExpression(expression: KtWhileExpression) {
        super.visitWhileExpression(expression)

        // Skip test files - this pattern is only problematic when in production code
        val filePath = expression.containingKtFile.virtualFilePath
        if (filePath.contains("/test/") || filePath.contains("/androidTest/")) {
            return
        }

        // Check if condition references .value (likely StateFlow/MutableStateFlow)
        val condition = expression.condition ?: return
        val conditionText = condition.text

        // Look for patterns like: someFlow.value, _someFlow.value
        if (!conditionText.contains(".value")) {
            return
        }

        // Check if the loop body contains delay()
        val body = expression.body ?: return
        val hasDelay =
            body.collectDescendantsOfType<KtCallExpression>().any { call ->
                val callee = call.calleeExpression?.text
                callee == "delay"
            }

        if (!hasDelay) {
            return
        }

        // Also check for kotlinx.coroutines.delay via qualified expression
        val hasQualifiedDelay =
            body.collectDescendantsOfType<KtDotQualifiedExpression>().any { expr ->
                expr.text.contains("delay(")
            }

        if (!hasDelay && !hasQualifiedDelay) {
            return
        }

        // Found the dangerous pattern
        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(expression),
                message = buildMessage(conditionText),
            ),
        )
    }

    private fun buildMessage(condition: String): String =
        """
            |Polling loop with StateFlow detected: while ($condition) { delay() }
            |
            |This pattern can cause infinite loops in unit tests because:
            |  - UnconfinedTestDispatcher executes delay() immediately
            |  - If mocks use `just Runs` instead of updating the StateFlow, the loop never exits
            |
            |Recommended fixes:
            |  1. In tests: Use `answers { stateFlow.value = NewState }` instead of `just Runs`
            |  2. Refactor: Use `stateFlow.collectLatest { }` which auto-cancels on new emissions
            |  3. Refactor: Use a Job that's explicitly cancelled when state changes
            |
            |If this pattern is intentional, add @Suppress("StateFlowPollingLoop")
        """.trimMargin()
}
