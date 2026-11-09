package network.columba.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Flags hardcoded English string literals passed to user-facing UI sinks
 * (Compose `Text`/labels/content descriptions, Toasts, Snackbars, notification
 * builders and channels, clipboard labels, project row helpers, enum display
 * fields). All user-facing text must come from string resources so the app can
 * be translated.
 *
 * Detection is syntactic (the CI `detekt` task has no type resolution):
 * callee-name + argument-name matching against [UiSinkTable]. Sink arguments are
 * descended through `+`-chains, `if`/`else` branches, `?:` (elvis) branches,
 * `when`-branch results, and braced branch blocks (final expression of each
 * block) — but only when the `if`/`when` expression is itself the
 * sink argument, so comparisons (`== "pending"`) in conditions and `when`
 * conditions remain non-sinks (wire keys). String comparisons are intentionally
 * NOT sinks. `@Preview` sample data is exempt.
 *
 * Ratified DO-NOT-TRANSLATE literals (telemetry breadcrumbs, animation labels,
 * wire/persisted display names, hardware product names) are listed in the
 * `doNotTranslateLiterals` config option (exact source text of the literal) in
 * `detekt-config.yml` — the config-level allowlist keeps the rule at zero
 * baseline without `@Suppress` (which CI ratchets to zero).
 *
 * False positives on same-named functions (e.g. a non-UI `message =` on a
 * telemetry constructor) take `@Suppress("NoHardcodedUiString")` with a
 * comment; suppressions are ratcheted in CI.
 */
class NoHardcodedUiString(config: Config = Config.empty) : Rule(config) {

    override val issue =
        Issue(
            id = "NoHardcodedUiString",
            severity = Severity.Style,
            description =
                "User-facing text must come from string resources (strings.xml + stringResource) " +
                    "so it can be translated.",
            debt = Debt.FIVE_MINS,
        )

    private val wordRegex = Regex("\\p{L}+")

    /**
     * Ratified DO-NOT-TRANSLATE literals (see `detekt-config.yml`
     * `columba.NoHardcodedUiString.doNotTranslateLiterals`): exact source text
     * of each literal, e.g. `"flasher_step"` including quotes. Config-level
     * allowlist so the rule keeps a zero detekt baseline and a zero `@Suppress`
     * count (both ratcheted by CI).
     */
    private val doNotTranslateLiterals: List<String> by config(emptyList<String>())

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (isInPreview(expression)) return
        val callee = expression.calleeExpression?.text ?: return
        val args = expression.valueArguments

        UiSinkTable.positionalSinks[callee]?.let { sinkIndices ->
            args.forEachIndexed { index, arg ->
                if (index in sinkIndices && arg.getArgumentName() == null) {
                    checkSink(arg.getArgumentExpression(), "$callee arg$index")
                }
            }
        }

        val namedSinksActive =
            callee.firstOrNull()?.isUpperCase() == true || callee in UiSinkTable.lowercaseNamedSinkCallers
        if (namedSinksActive) {
            args.forEach { arg ->
                val name = arg.getArgumentName()?.asName?.identifier ?: return@forEach
                if (name in UiSinkTable.namedSinks) {
                    checkSink(arg.getArgumentExpression(), "$callee.$name")
                }
            }
        }
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationToken != KtTokens.EQ) return
        if (isInPreview(expression)) return
        val propertyName =
            when (val left = expression.left) {
                is KtNameReferenceExpression -> left.getReferencedName()
                is KtDotQualifiedExpression -> (left.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
                else -> null
            } ?: return
        if (propertyName in UiSinkTable.assignmentSinks) {
            checkSink(expression.right, "$propertyName assignment")
        }
    }

    override fun visitEnumEntry(enumEntry: KtEnumEntry) {
        super.visitEnumEntry(enumEntry)
        if (isInPreview(enumEntry)) return
        val enumClass = enumEntry.parents.filterIsInstance<KtClass>().firstOrNull() ?: return
        val paramNames = enumClass.primaryConstructor?.valueParameters?.map { it.name ?: "" } ?: return
        val entryArgs = PsiTreeUtil.findChildOfType(enumEntry, KtValueArgumentList::class.java)?.arguments.orEmpty()
        entryArgs.forEachIndexed { index, arg ->
            val paramName = paramNames.getOrNull(index)
            if (paramName != null && paramName in UiSinkTable.namedSinks && arg.getArgumentName() == null) {
                checkSink(arg.getArgumentExpression(), "${enumClass.name}.$paramName")
            }
        }
    }

    /**
     * Sealed-class `object Foo : Base(displayName = "…")` supertype constructor
     * calls are display-field sinks too (e.g. `FirmwareSource`), same as enum
     * entries — the arguments are not `KtCallExpression` args, so they need
     * their own visit.
     */
    override fun visitSuperTypeCallEntry(superTypeCallEntry: KtSuperTypeCallEntry) {
        super.visitSuperTypeCallEntry(superTypeCallEntry)
        if (isInPreview(superTypeCallEntry)) return
        val callee = superTypeCallEntry.calleeExpression?.text ?: return
        if (callee.firstOrNull()?.isUpperCase() != true) return
        superTypeCallEntry.valueArguments.forEach { arg ->
            val name = arg.getArgumentName()?.asName?.identifier ?: return@forEach
            if (name in UiSinkTable.namedSinks) {
                checkSink(arg.getArgumentExpression(), "$callee.$name")
            }
        }
    }

    /**
     * Report string literals that contain English prose, descending through
     * `+`-chains, `if`/`else` branches, `?:` (elvis) branches, and `when`-branch
     * results. Conditions are never descended into, so wire-key comparisons such
     * as `state == "pending"` stay non-sinks.
     */
    private fun checkSink(
        expression: KtExpression?,
        sink: String,
    ) {
        when (expression) {
            is KtStringTemplateExpression -> {
                if (expression.text in doNotTranslateLiterals) return
                val staticText =
                    expression.entries.joinToString("") { entry ->
                        when (entry) {
                            is KtLiteralStringTemplateEntry -> entry.text
                            is KtEscapeStringTemplateEntry -> entry.unescapedValue
                            else -> "" // interpolation: not static text
                        }
                    }
                if (containsUserFacingText(staticText)) {
                    report(
                        CodeSmell(
                            issue = issue,
                            entity = Entity.from(expression),
                            message =
                                "Hardcoded user-facing string in '$sink'. Move it to strings.xml " +
                                    "and render via stringResource(R.string.…).",
                        ),
                    )
                }
            }
            is KtBinaryExpression ->
                when (expression.operationToken) {
                    KtTokens.PLUS, KtTokens.ELVIS -> {
                        checkSink(expression.left, sink)
                        checkSink(expression.right, sink)
                    }
                    else -> Unit
                }
            is KtIfExpression -> {
                checkSink(expression.then, sink)
                checkSink(expression.`else`, sink)
            }
            is KtWhenExpression ->
                expression.entries.forEach { entry -> checkSink(entry.expression, sink) }
            is KtBlockExpression ->
                checkSink(expression.statements.lastOrNull(), sink)
        }
    }

    private fun containsUserFacingText(text: String): Boolean =
        wordRegex.findAll(text).any { match -> match.value.lowercase() !in UiSinkTable.technicalTokens }

    private fun isInPreview(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement): Boolean =
        element.parents
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull()
            ?.annotationEntries
            ?.any { it.shortName?.asString() == "Preview" }
            ?: false
}
