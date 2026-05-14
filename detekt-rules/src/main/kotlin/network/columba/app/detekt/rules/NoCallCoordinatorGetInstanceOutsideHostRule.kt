package network.columba.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Flags direct `tech.torlando.lxst.core.CallCoordinator.getInstance()` calls
 * outside the two modules that legitimately own the LXST runtime
 * (`:rns-host` and `:rns-backend-kt`).
 *
 * Rationale: `CallCoordinator` is a JVM singleton inside lxst-kt. Today the
 * whole stack lives in the UI process and that works, but Phase A of the
 * RNS dual-build re-architecture moves protocol execution into the
 * `:reticulum` process. Once the AIDL boundary is wired (A.10), the UI
 * process's `CallCoordinator.getInstance()` returns a *different*
 * singleton from the one the call manager in `:reticulum` is driving —
 * the UI-process singleton becomes orphaned dead state with no
 * `Telephone` wired to it.
 *
 * The fix is to inject `CallCoordinator` (or its `RnsTelephony` view
 * once A.10 lands) from Hilt's [HostBackendModule.provideCallCoordinator]
 * provider, which is the single allowlisted call site for the
 * `getInstance()` factory. This rule prevents the bug from regressing
 * after A.9 cleans up the three legacy UI-side call sites.
 *
 * Allowed paths:
 * - Any source under `rns-host/`
 * - Any source under `rns-backend-kt/`
 */
class NoCallCoordinatorGetInstanceOutsideHostRule(
    config: Config = Config.empty,
) : Rule(config) {
    override val defaultRuleIdAliases: Set<String> = emptySet()

    override val issue =
        Issue(
            id = "NoCallCoordinatorGetInstanceOutsideHost",
            severity = Severity.Defect,
            description =
                "tech.torlando.lxst.core.CallCoordinator.getInstance() must only be " +
                    "called from :rns-host or :rns-backend-kt — the UI process's singleton " +
                    "diverges from the :reticulum-process one after A.10's AIDL boundary " +
                    "is wired. Inject CallCoordinator (or RnsTelephony) via Hilt instead.",
            debt = Debt.TWENTY_MINS,
        )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        // Path-based allowlist: legitimate owners can hold the singleton.
        val filePath = expression.containingKtFile.virtualFilePath
        if (isAllowedPath(filePath)) return

        if (!isCallCoordinatorGetInstance(expression)) return

        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(expression),
                message =
                    "Direct CallCoordinator.getInstance() outside :rns-host/:rns-backend-kt. " +
                        "Inject `tech.torlando.lxst.core.CallCoordinator` via Hilt (see " +
                        "HostBackendModule.provideCallCoordinator) or, once A.10 lands, " +
                        "consume the call surface through `RnsTelephony`.",
            ),
        )
    }

    private fun isAllowedPath(filePath: String): Boolean =
        filePath.contains("/rns-host/") || filePath.contains("/rns-backend-kt/")

    /**
     * Recognises three syntactic shapes for the disallowed call:
     *   1. `CallCoordinator.getInstance()` — receiver is a simple reference whose
     *      short name is `CallCoordinator`.
     *   2. `tech.torlando.lxst.core.CallCoordinator.getInstance()` — receiver is a
     *      dot-qualified FQN whose last segment is `CallCoordinator`.
     *   3. `getInstance()` with no receiver — only flagged if the call lives
     *      inside a file that imports `tech.torlando.lxst.core.CallCoordinator`,
     *      because bare `getInstance()` could refer to many singletons.
     */
    private fun isCallCoordinatorGetInstance(expression: KtCallExpression): Boolean {
        val calleeName = expression.calleeExpression?.text ?: return false
        if (calleeName != "getInstance") return false

        val parent = expression.parent as? KtDotQualifiedExpression
        if (parent != null && parent.selectorExpression === expression) {
            // Shape 1 + 2: receiver.getInstance()
            val receiverText = parent.receiverExpression.text
            // Match either the bare type name or any FQN ending in .CallCoordinator
            if (receiverText == "CallCoordinator") return true
            if (receiverText.endsWith(".CallCoordinator")) return true
            return false
        }

        // Shape 3: bare getInstance() — only flag if CallCoordinator is imported.
        val importedNames = expression.containingKtFile.importDirectives
            .mapNotNull { it.importedFqName?.asString() }
        return importedNames.any {
            it == "tech.torlando.lxst.core.CallCoordinator" ||
                it.endsWith(".CallCoordinator")
        }
    }
}
