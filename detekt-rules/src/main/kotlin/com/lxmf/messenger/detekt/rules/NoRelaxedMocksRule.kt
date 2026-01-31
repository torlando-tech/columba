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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Detekt rule to prevent relaxed mocks in tests.
 *
 * Relaxed mocks (`mockk(relaxed = true)`) are dangerous because:
 * 1. They return default values for any unmocked method, hiding missing test setup
 * 2. Tests using them often verify mock interactions instead of actual behavior
 * 3. They don't fail when production code changes, making tests useless for catching regressions
 *
 * Instead of relaxed mocks:
 * - Use real implementations (in-memory databases, fake repositories)
 * - Mock only external dependencies with explicit `every { }` stubs
 * - Test actual behavior with assertions, not `verify { }` calls
 *
 * This rule is NON-SUPPRESSABLE for non-Android types. The only exception is Android
 * system types (Context, BluetoothManager, etc.) which are automatically allowed.
 *
 * If you need a relaxed mock for an Android type not in the allowed list, add it
 * to the allowedTypes set in this rule rather than suppressing.
 */
class NoRelaxedMocksRule(
    config: Config = Config.empty,
) : Rule(config) {
    // Make this rule non-suppressable
    override val defaultRuleIdAliases: Set<String> = emptySet()

    // Override to prevent suppression via annotations
    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        // Check if this is a @Suppress or @file:Suppress trying to suppress this rule
        val annotationText = annotationEntry.text
        if (annotationText.contains("Suppress") && annotationText.contains("NoRelaxedMocks")) {
            report(
                CodeSmell(
                    issue = suppressionAttemptIssue,
                    entity = Entity.from(annotationEntry),
                    message =
                        "Cannot suppress NoRelaxedMocks rule. This rule exists to prevent " +
                            "useless tests. Use explicit stubs instead of relaxed mocks, or add " +
                            "the Android type to the allowed list in NoRelaxedMocksRule.kt.",
                ),
            )
        }
    }

    private val suppressionAttemptIssue =
        Issue(
            id = "NoRelaxedMocksSuppression",
            severity = Severity.CodeSmell,
            description = "Attempting to suppress the NoRelaxedMocks rule is not allowed.",
            debt = Debt.TEN_MINS,
        )
    override val issue =
        Issue(
            id = "NoRelaxedMocks",
            severity = Severity.Maintainability,
            description =
                "Relaxed mocks hide missing test setup and lead to tests that verify mock " +
                    "behavior instead of production code. Use real implementations or explicit stubs.",
            debt = Debt.TWENTY_MINS,
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)

        // Only check test files
        val filePath = file.virtualFilePath
        if (!filePath.contains("/test/") && !filePath.contains("/androidTest/")) {
            return
        }
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        // Only check in test files
        val filePath = expression.containingKtFile.virtualFilePath
        if (!filePath.contains("/test/") && !filePath.contains("/androidTest/")) {
            return
        }

        // Check if this is a mockk() call
        val calleeName = expression.calleeExpression?.text ?: return
        if (calleeName != "mockk" && calleeName != "spyk" && calleeName != "mockkClass") {
            return
        }

        // Check for relaxed = true argument
        val relaxedArg =
            expression.valueArguments.find { arg ->
                isRelaxedTrueArgument(arg)
            }

        if (relaxedArg != null) {
            // Check if it's for an allowed type (Context, system services)
            // First check type arguments: mockk<Context>(relaxed = true)
            val typeArg = expression.typeArguments.firstOrNull()?.text
            if (isAllowedRelaxedType(typeArg)) {
                return
            }

            // Also check variable name patterns that suggest Android types
            // e.g., mockContext, mockWifiManager, context, etc.
            val variableName = getAssignedVariableName(expression)
            if (isAllowedVariableName(variableName)) {
                return
            }

            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(expression),
                    message = buildMessage(calleeName),
                ),
            )
        }
    }

    private fun getAssignedVariableName(expression: KtCallExpression): String? {
        // Try to get the variable name this mock is assigned to
        // Handles: val mockContext = mockk(...) and mockContext = mockk(...)
        val parent = expression.parent
        return when {
            parent is org.jetbrains.kotlin.psi.KtProperty -> parent.name
            parent is org.jetbrains.kotlin.psi.KtBinaryExpression -> {
                parent.left?.text
            }
            else -> null
        }
    }

    private fun isAllowedVariableName(name: String?): Boolean {
        if (name == null) return false
        val lowerName = name.lowercase()

        // Variable names that suggest Android system types
        val allowedPatterns =
            listOf(
                "context",
                "application",
                "activity",
                "service",
                "contentresolver",
                "sharedpreferences",
                "resources",
                "packagemanager",
                "wifimanager",
                "bluetoothmanager",
                "bluetoothadapter",
                "notificationmanager",
                "alarmmanager",
                "connectivitymanager",
                "locationmanager",
                "powermanager",
                "wifilock",
                "multicastlock",
                "wakelock",
            )

        return allowedPatterns.any { lowerName.contains(it) }
    }

    private fun isRelaxedTrueArgument(arg: KtValueArgument): Boolean {
        val argText = arg.text
        // Match: relaxed = true, relaxed=true, relaxed = true
        return argText.contains("relaxed") && argText.contains("true")
    }

    private fun isAllowedRelaxedType(typeArg: String?): Boolean {
        if (typeArg == null) return false

        // Android system types that genuinely need mocking
        val allowedTypes =
            setOf(
                "Context",
                "Application",
                "Activity",
                "Service",
                "ContentResolver",
                "SharedPreferences",
                "Resources",
                "PackageManager",
                "WifiManager",
                "BluetoothManager",
                "BluetoothAdapter",
                "NotificationManager",
                "AlarmManager",
                "ConnectivityManager",
                "LocationManager",
                "PowerManager",
                "WifiManager.WifiLock",
                "WifiManager.MulticastLock",
                "PowerManager.WakeLock",
            )

        return allowedTypes.any { typeArg.contains(it) }
    }

    private fun buildMessage(calleeName: String): String =
        """
            |Avoid $calleeName(relaxed = true). Relaxed mocks:
            |  • Hide missing test setup by returning defaults for unmocked methods
            |  • Lead to tests that verify mock calls instead of actual behavior
            |  • Don't catch regressions when production code changes
            |
            |Instead:
            |  • Use real implementations (in-memory Room database, fake repositories)
            |  • Use explicit every { } stubs for external dependencies
            |  • Assert on actual results, not verify { } calls
            |
            |For Android Context/system services, use @Suppress("NoRelaxedMocks").
        """.trimMargin()
}
