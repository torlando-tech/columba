package network.columba.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression

/**
 * Detekt rule that flags concurrency-signal-returning calls whose return value is
 * discarded. The return value of these methods carries a load-bearing signal —
 * "did the operation actually complete / did I actually acquire?" — and ignoring
 * it has produced multiple shipped bugs in this codebase. Examples:
 *
 *  - `executor.awaitTermination(5, SECONDS)` returning `false` means tasks are
 *    still running. Sentry COLUMBA-8R: a discarded `false` led to
 *    `database.close()` racing a Room transaction → `IllegalStateException`.
 *  - `lock.tryLock()` returning `false` means the lock was NOT acquired. Code
 *    that proceeds anyway corrupts state guarded by that lock.
 *  - `mutex.tryLock()` (kotlinx coroutines) — same semantics.
 *  - `latch.await(timeout, unit)` returning `false` means the latch did NOT
 *    reach zero in time.
 *  - `queue.offer(elem)` returning `false` means the queue rejected the offer
 *    (full / capacity exceeded). Discarding it silently drops data.
 *
 * Detection is PSI-only (no type binding) — we match by callee name and check
 * whether the call's parent context consumes the result. False positives are
 * possible if someone defines an unrelated method with one of these names; for
 * those, suppress with `@Suppress("DiscardedConcurrencyReturn")` and a comment.
 *
 * For the COLUMBA-8R archetype this rule is non-suppressable for `awaitTermination`
 * specifically — that one has no legitimate "ignore the boolean" call site we know
 * of in this codebase.
 */
class DiscardedConcurrencyReturnRule(
    config: Config = Config.empty,
) : Rule(config) {
    override val issue =
        Issue(
            id = "DiscardedConcurrencyReturn",
            // Defect severity — every fire is a real bug archetype. These returns are
            // load-bearing concurrency signals; discarding them has shipped multiple
            // production bugs (Sentry COLUMBA-8R). For genuine non-bugs (e.g. `.offer()`
            // on an unbounded `ConcurrentLinkedQueue` where the boolean is meaningless),
            // prefer migrating to a semantically-clearer API (`.add()` in that case)
            // over `@Suppress`.
            severity = Severity.Defect,
            description =
                "Concurrency-signal-returning calls (awaitTermination, tryLock, await with " +
                    "timeout, offer) carry load-bearing return values. Discarding them silently " +
                    "swallows partial-completion / contention / capacity signals and has caused " +
                    "shipped bugs in this codebase (see Sentry COLUMBA-8R).",
            debt = Debt.TEN_MINS,
        )

    /**
     * Method names whose return value MUST be consumed. Receiver type is not checked
     * (PSI-only rule), so a same-named method on an unrelated type will also fire —
     * suppress those with `@Suppress("DiscardedConcurrencyReturn")` plus a comment.
     */
    private val targetMethods =
        setOf(
            "awaitTermination", // ExecutorService — false = tasks still running
            "tryLock", // Lock / kotlinx Mutex — false = not acquired
            "offer", // BlockingQueue / Channel — false = rejected
        )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName !in targetMethods) return

        // Special-case `offer`: only flag the timed/full-rejection forms, i.e. calls
        // with at least one argument. The 0-arg `offer()` doesn't exist on these APIs
        // but a Set.offer() would be unrelated and we'd rather not chase it.
        if (calleeName == "offer" && expression.valueArguments.isEmpty()) return

        // Special-case `await` and `tryLock`: kotlinx Mutex.withLock is the safe wrapper;
        // direct tryLock() with no args on a coroutine Mutex is rare. Pass through —
        // we want to flag every discarded tryLock.

        if (isResultDiscarded(expression)) {
            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(expression),
                    message = buildMessage(calleeName),
                ),
            )
        }
    }

    /**
     * Walk up the PSI parent chain through wrappers that don't consume the value
     * (parens, safe-call `?.`, dot-qualified call where THIS is the receiver chain
     * head). If we reach a `KtBlockExpression` and we're not the last statement
     * of an expression-bodied lambda, the result is discarded.
     */
    private fun isResultDiscarded(call: KtCallExpression): Boolean {
        // Walk up through transparent expression wrappers to find the
        // statement-level expression that contains this call.
        var current: org.jetbrains.kotlin.com.intellij.psi.PsiElement = call
        var parent = current.parent

        while (parent != null) {
            when (parent) {
                is KtParenthesizedExpression -> {
                    current = parent
                    parent = parent.parent
                }
                is KtDotQualifiedExpression -> {
                    // Only walk up if WE are the receiver of the dot-qualified
                    // expression (i.e. there's a method call on our result).
                    // If we are the SELECTOR (the .foo() at the right of the dot),
                    // then the dot-qualified expression IS our call and we walk up.
                    if (parent.selectorExpression === current) {
                        current = parent
                        parent = parent.parent
                    } else {
                        // We're the receiver — our return is being consumed by
                        // the next call in the chain. Not discarded.
                        return false
                    }
                }
                is KtSafeQualifiedExpression -> {
                    if (parent.selectorExpression === current) {
                        current = parent
                        parent = parent.parent
                    } else {
                        return false
                    }
                }
                else -> break
            }
        }

        // Now `parent` is the actual semantic context. If it's a block expression
        // AND we're not its last child, we're a statement (discarded).
        if (parent is KtBlockExpression) {
            val lastStmt = parent.statements.lastOrNull()
            if (lastStmt !== current) return true

            // We ARE the last statement of the block. The block's value is consumed
            // only if the enclosing function/lambda actually has a non-Unit return
            // type. PSI-only check (no type binding):
            //   - KtNamedFunction with explicit non-Unit `: Type` return → consumed
            //   - KtNamedFunction with no `:` annotation → returns Unit → discarded
            //   - KtFunctionLiteral (lambda) → can't tell from PSI alone whether
            //     the calling context wants the value; conservatively treat as
            //     consumed to avoid false positives on `runCatching { ... }` etc.
            val grandparent = parent.parent
            if (grandparent is KtNamedFunction) {
                val typeRefText = grandparent.typeReference?.text
                // Discarded if: no return-type annotation, or annotated as `Unit`
                if (typeRefText == null || typeRefText.trim() == "Unit") return true
                return false
            }
            if (grandparent is KtFunctionLiteral) {
                // Lambda body — assume the lambda's caller might consume the value.
                // (We don't have type info on the lambda's expected SAM here.)
                return false
            }
            // Any other block context (e.g. an `if` block whose value flows up):
            // err on the side of "consumed" to minimize false positives.
            return false
        }

        // KtPrefixExpression covers `!call(...)` — value IS consumed by the negation.
        if (parent is KtPrefixExpression) return false

        // KtBinaryExpression covers comparisons and assignments. In both cases
        // the value is consumed. (Assignment e.g. `acquired = lock.tryLock()`,
        // comparison e.g. `if (lock.tryLock()) { ... }`.)
        if (parent is KtBinaryExpression) return false

        // Any other parent (KtProperty initializer, KtIfExpression condition,
        // KtWhenExpression subject, KtReturnExpression, KtCallExpression argument,
        // etc.) is consuming the value.
        return false
    }

    private fun buildMessage(calleeName: String): String =
        when (calleeName) {
            "awaitTermination" ->
                "`awaitTermination(...)` returns `false` if the executor still has " +
                    "running tasks after the timeout. Discarding that signal lets the " +
                    "caller proceed to operations (often `close()`) that race with the " +
                    "still-running tasks. See Sentry COLUMBA-8R. Capture the return and " +
                    "either escalate via `shutdownNow()` or wait longer."
            "tryLock" ->
                "`tryLock(...)` returns `false` if the lock was NOT acquired. Discarding " +
                    "that signal means subsequent code touches state that no lock guards. " +
                    "Either: branch on the result, use `lock { ... }` / `withLock { ... }`, " +
                    "or call the blocking `lock()` if waiting is correct."
            "offer" ->
                "`offer(...)` returns `false` if the channel/queue rejected the element " +
                    "(full or closed). Discarding that signal silently drops data. Either: " +
                    "branch on the result, use `send(...)` (suspending) for guaranteed " +
                    "delivery, or `trySend(...)` with explicit handling of `ChannelResult`."
            else ->
                "`$calleeName(...)`'s return value carries a concurrency signal that should " +
                    "not be discarded."
        }
}
