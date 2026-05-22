package network.columba.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Requires `@network.columba.app.rns.api.annotation.ReflectivelyKept` on the
 * Chaquopy bridge shapes, so R8 cannot rename/strip a class that Python invokes
 * by name in a minified release build.
 *
 * This is the enforcement half of the `@ReflectivelyKept` contract: the
 * annotation is class-level (keeps all current + future members), but nothing
 * in Kotlin can see that Python calls the class — so a new bridge added without
 * the annotation would compile, pass tests, and only fail in release. That is
 * exactly the inbound-voice `PyTwoArgCallback` regression that motivated this.
 *
 * **What it flags** (in `:rns-backend-py` / `:rns-host` only):
 *  1. a `fun interface` in the `network.columba.app.rns.backend.py` package —
 *     the Chaquopy callback SAM shape (`PyEventCallback`, `PyTwoArgCallback`);
 *  2. a class/object named `Kotlin*Bridge` — the host-bridge naming convention
 *     (`KotlinBLEBridge`, `KotlinRNodeBridge`, `KotlinUSBBridge`).
 *
 * These two shapes cover every Chaquopy bridge in the tree with no false
 * positives (Android callbacks like `GattCallback`/`ProgressCallback` and the
 * kotlin-backend `AndroidRNodeHostBridge` don't match). Bridges that fit
 * neither shape (e.g. `PythonEventBridge`, `StampGeneratorCallback`) still carry
 * the annotation but aren't auto-detectable here — keep new Chaquopy bridges to
 * one of the two shapes so this rule can guard them.
 */
class ReflectivelyKeptRequiredRule(
    config: Config = Config.empty,
) : Rule(config) {
    override val issue =
        Issue(
            id = "ReflectivelyKeptRequired",
            severity = Severity.Defect,
            description =
                "Chaquopy bridge classes invoked by name from Python must be annotated " +
                    "@ReflectivelyKept, or R8 renames/strips them in minified release builds " +
                    "and the by-name call fails silently at runtime.",
            debt = Debt.FIVE_MINS,
        )

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)

        if (!isBridgeModule(classOrObject.containingKtFile.virtualFilePath)) return
        if (!isChaquopyBridgeShape(classOrObject)) return
        if (hasReflectivelyKept(classOrObject)) return

        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(classOrObject),
                message =
                    "`${classOrObject.name}` is a Chaquopy bridge shape (a fun interface in " +
                        ":rns-backend-py, or a Kotlin*Bridge class) but is not annotated " +
                        "@ReflectivelyKept. Without it, R8 renames/strips it in release builds " +
                        "and the by-name Python call fails silently — add " +
                        "@network.columba.app.rns.api.annotation.ReflectivelyKept (class-level, " +
                        "so it keeps all current and future members).",
            ),
        )
    }

    private fun isBridgeModule(filePath: String): Boolean =
        filePath.contains("/rns-backend-py/") || filePath.contains("/rns-host/")

    private fun isChaquopyBridgeShape(classOrObject: KtClassOrObject): Boolean {
        if (isFunInterfaceInPyBackend(classOrObject)) return true
        val name = classOrObject.name ?: return false
        return name.startsWith("Kotlin") && name.endsWith("Bridge")
    }

    private fun isFunInterfaceInPyBackend(classOrObject: KtClassOrObject): Boolean {
        val ktClass = classOrObject as? KtClass ?: return false
        if (!ktClass.isInterface()) return false
        if (!ktClass.hasModifier(KtTokens.FUN_KEYWORD)) return false
        return classOrObject.containingKtFile.packageFqName.asString()
            .startsWith("network.columba.app.rns.backend.py")
    }

    private fun hasReflectivelyKept(classOrObject: KtClassOrObject): Boolean =
        classOrObject.annotationEntries.any { it.shortName?.asString() == "ReflectivelyKept" }
}
