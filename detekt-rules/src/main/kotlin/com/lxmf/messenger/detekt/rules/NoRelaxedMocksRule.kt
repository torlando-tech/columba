package com.lxmf.messenger.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
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
 * Exceptions:
 * - Android Context and system services (genuinely need mocking)
 * - Use @Suppress("NoRelaxedMocks") with a comment explaining why
 */
class NoRelaxedMocksRule(
    config: Config = Config.empty,
) : Rule(config) {
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
            val typeArg = expression.typeArguments.firstOrNull()?.text
            if (isAllowedRelaxedType(typeArg)) {
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
