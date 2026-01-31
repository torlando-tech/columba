package com.lxmf.messenger.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Detekt rule to flag test functions that only use verify/coVerify without assertions.
 *
 * Tests that only verify mock interactions without asserting on actual outcomes are often:
 * 1. Testing mock wiring rather than production behavior
 * 2. Brittle to refactoring (fail when implementation changes but behavior is preserved)
 * 3. Unable to catch real regressions
 *
 * This rule is NON-SUPPRESSABLE. The whole point of this rule is to prevent AI agents
 * and developers from writing useless tests that don't test production code.
 *
 * If you have a legitimate use case (UI event dispatch, side effect verification),
 * add an assertion that verifies the outcome, not just that the method was called.
 */
class NoVerifyOnlyTestsRule(
    config: Config = Config.empty,
) : Rule(config) {
    // Make this rule non-suppressable
    override val defaultRuleIdAliases: Set<String> = emptySet()

    // Override to prevent suppression via annotations
    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        // Check if this is a @Suppress or @file:Suppress trying to suppress this rule
        val annotationText = annotationEntry.text
        if (annotationText.contains("Suppress") && annotationText.contains("NoVerifyOnlyTests")) {
            report(
                CodeSmell(
                    issue = suppressionAttemptIssue,
                    entity = Entity.from(annotationEntry),
                    message =
                        "Cannot suppress NoVerifyOnlyTests rule. This rule exists to prevent " +
                            "useless tests that only verify mock calls. Add real assertions instead.",
                ),
            )
        }
    }

    private val suppressionAttemptIssue =
        Issue(
            id = "NoVerifyOnlyTestsSuppression",
            severity = Severity.CodeSmell,
            description = "Attempting to suppress the NoVerifyOnlyTests rule is not allowed.",
            debt = Debt.TEN_MINS,
        )
    override val issue =
        Issue(
            id = "NoVerifyOnlyTests",
            severity = Severity.Maintainability,
            description =
                "Test function uses verify/coVerify but has no assertions. " +
                    "Consider adding assertions on actual behavior, not just mock interactions.",
            debt = Debt.TEN_MINS,
        )

    // Track state for current function being analyzed
    private var currentFunctionHasVerify = false
    private var currentFunctionHasAssertion = false
    private var currentFunction: KtNamedFunction? = null

    override fun visitNamedFunction(function: KtNamedFunction) {
        // Only check test files
        val filePath = function.containingKtFile.virtualFilePath
        if (!filePath.contains("/test/") && !filePath.contains("/androidTest/")) {
            return
        }

        // Only check @Test functions
        if (!function.annotationEntries.any { it.shortName?.asString() == "Test" }) {
            super.visitNamedFunction(function)
            return
        }

        // Reset tracking for this function
        currentFunction = function
        currentFunctionHasVerify = false
        currentFunctionHasAssertion = false

        // Visit children to check for verify/assert calls
        super.visitNamedFunction(function)

        // Report if function has verify but no assertions
        if (currentFunctionHasVerify && !currentFunctionHasAssertion) {
            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.atName(function),
                    message = buildMessage(function.name ?: "test"),
                ),
            )
        }

        currentFunction = null
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        // Only process if we're inside a test function
        if (currentFunction == null) return

        val calleeName = expression.calleeExpression?.text ?: return

        // Check for verify calls
        if (calleeName in VERIFY_FUNCTIONS) {
            currentFunctionHasVerify = true
        }

        // Check for assertion calls
        if (calleeName in ASSERTION_FUNCTIONS) {
            currentFunctionHasAssertion = true
        }

        // Check for assert() call with parens
        if (calleeName == "assert") {
            currentFunctionHasAssertion = true
        }
    }

    private fun buildMessage(functionName: String): String =
        """
            |Test '$functionName' only uses verify/coVerify without assertions.
            |
            |This often indicates a test that:
            |  • Tests mock wiring rather than production behavior
            |  • Will break when implementation changes but behavior is preserved
            |  • Won't catch real regressions
            |
            |Consider:
            |  • Adding assertions on actual return values or state changes
            |  • Testing the outcome, not just that methods were called
            |
            |If this is a legitimate UI test or integration test verifying side effects,
            |use @Suppress("NoVerifyOnlyTests") with a comment explaining why.
        """.trimMargin()

    companion object {
        private val VERIFY_FUNCTIONS =
            setOf(
                "verify",
                "coVerify",
                "verifyAll",
                "coVerifyAll",
                "verifyOrder",
                "coVerifyOrder",
                "verifySequence",
                "coVerifySequence",
                "confirmVerified",
            )

        private val ASSERTION_FUNCTIONS =
            setOf(
                // JUnit assertions
                "assertEquals",
                "assertNotEquals",
                "assertTrue",
                "assertFalse",
                "assertNull",
                "assertNotNull",
                "assertSame",
                "assertNotSame",
                "assertArrayEquals",
                "assertThrows",
                "assertDoesNotThrow",
                "assertTimeout",
                "assertTimeoutPreemptively",
                "fail",
                // Kotlin test
                "expect",
                "expectThat",
                // Kotest/should matchers
                "shouldBe",
                "shouldEqual",
                "shouldNotBe",
                "shouldNotEqual",
                "shouldThrow",
                "shouldNotThrow",
                "shouldBeNull",
                "shouldNotBeNull",
                "shouldBeTrue",
                "shouldBeFalse",
                "shouldBeEmpty",
                "shouldNotBeEmpty",
                "shouldContain",
                "shouldNotContain",
                // AssertJ/Truth
                "assertThat",
                // Compose testing
                "assertIsDisplayed",
                "assertIsNotDisplayed",
                "assertExists",
                "assertDoesNotExist",
                "assertTextEquals",
                "assertTextContains",
                "assertIsEnabled",
                "assertIsNotEnabled",
                "assertIsSelected",
                "assertIsNotSelected",
                "assertIsToggleable",
                "assertIsOn",
                "assertIsOff",
                "assertContentDescriptionEquals",
                "assertContentDescriptionContains",
            )
    }
}
