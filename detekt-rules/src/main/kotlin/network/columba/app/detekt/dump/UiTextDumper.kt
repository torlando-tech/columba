package network.columba.app.detekt.dump

import network.columba.app.detekt.rules.UiSinkTable
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Emits the ordered UI-text skeleton of a Kotlin file: one entry per user-facing
 * sink occurrence (same sink table as [UiSinkTable], so identical to what
 * `NoHardcodedUiString` guards).
 *
 * The sink-equivalence gate (i18n ticket 23) dumps base and head of every changed
 * file and fails CI on any diff — machine proof that a PR does not change
 * rendered text. For that to hold, both sides must normalize to the same
 * skeleton:
 *  - interpolation (`$x`, `${…}`) and format specifiers (`%s`, `%1$s`) become `{}`;
 *  - `stringResource`/`getString` calls resolve through [ResResolver] to their
 *    English resource text, so a pure extraction (literal -> identical resource)
 *    produces an identical skeleton;
 *  - plurals contribute `one` + `other` entries;
 *  - anything else (variables, function calls, conditionals) becomes `{}` —
 *    stable under renames; the gate cannot see inside without type resolution.
 */
class UiTextDumper(
    private val res: ResResolver,
    private val moduleName: String,
) {
    data class Entry(
        val ordinal: Int,
        val kind: String,
        val skeleton: String,
    )

    private val entries = ArrayList<Entry>()

    fun dump(file: KtFile): List<Entry> {
        entries.clear()
        file.accept(visitor())
        return entries.toList()
    }
    private fun visitor(): KtTreeVisitorVoid =
        object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                if (isInPreview(expression)) return
                val callee = expression.calleeExpression?.text ?: return
                val args = expression.valueArguments

                UiSinkTable.positionalSinks[callee]?.let { sinkIndices ->
                    args.forEachIndexed { index, arg ->
                        if (index in sinkIndices && arg.getArgumentName() == null) {
                            emit(arg.getArgumentExpression(), "$callee arg$index")
                        }
                    }
                }

                val namedSinksActive =
                    callee.firstOrNull()?.isUpperCase() == true || callee in UiSinkTable.lowercaseNamedSinkCallers
                if (namedSinksActive) {
                    args.forEach { arg ->
                        val name = arg.getArgumentName()?.asName?.identifier ?: return@forEach
                        if (name in UiSinkTable.namedSinks) {
                            emit(arg.getArgumentExpression(), "$callee.$name")
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
                    emit(expression.right, "$propertyName assignment")
                }
            }

            override fun visitEnumEntry(enumEntry: KtEnumEntry) {
                super.visitEnumEntry(enumEntry)
                if (isInPreview(enumEntry)) return
                val enumClass = enumEntry.parents.filterIsInstance<KtClass>().firstOrNull() ?: return
                val paramNames = enumClass.primaryConstructor?.valueParameters?.map { it.name ?: "" } ?: return
                val entryArgs =
                    PsiTreeUtil.findChildOfType(enumEntry, KtValueArgumentList::class.java)?.arguments.orEmpty()
                entryArgs.forEachIndexed { index, arg ->
                    val paramName = paramNames.getOrNull(index)
                    if (paramName != null && paramName in UiSinkTable.namedSinks && arg.getArgumentName() == null) {
                        emit(arg.getArgumentExpression(), "${enumClass.name}.$paramName")
                    }
                }
            }
        }

    private fun emit(
        expression: KtExpression?,
        kind: String,
    ) {
        when {
            expression == null -> Unit
            expression is KtStringTemplateExpression -> add(kind, templateSkeleton(expression))
            expression is KtBinaryExpression && expression.operationToken == KtTokens.PLUS ->
                add(kind, concatSkeleton(expression))
            else -> {
                val resolved = resolveExpr(expression)
                if (resolved != null) {
                    resolved.forEach { (subKind, skeleton) -> add("$kind$subKind", skeleton) }
                } else {
                    // Opaque interpolated text (variable, call, conditional):
                    // stable under renames; the gate cannot see inside without
                    // type resolution, exactly like the rule.
                    add(kind, "{}")
                }
            }
        }
    }

    private fun add(
        kind: String,
        skeleton: String,
    ) {
        entries.add(Entry(entries.size, kind, skeleton))
    }

    /**
     * Resource-derived skeletons for a sink argument — `stringResource`/`getString`
     * -> one entry, plurals -> `[one]` + `[other]` — or null when the argument is
     * not a resolvable resource reference. Receiver-qualified calls
     * (`ctx.getString(…)`) are unwrapped; the receiver never affects the text.
     */
    private fun resolveExpr(expression: KtExpression): List<Pair<String, String>>? {
        rRefOf(expression)?.let { (pkg, type, name) -> return resolveRef(pkg, type, name) }
        val call =
            when (expression) {
                is KtCallExpression -> expression
                is KtDotQualifiedExpression -> expression.selectorExpression as? KtCallExpression
                else -> null
            } ?: return null
        val callee = call.calleeExpression?.text ?: return null
        val ref =
            call.valueArguments
                .mapNotNull { rRefOf(it.getArgumentExpression()) }
                .firstOrNull() ?: return null
        val (pkg, type, name) = ref
        return when {
            callee in STRING_CALLERS && type == "string" -> resolveRef(pkg, type, name)
            callee in PLURAL_CALLERS && type == "plurals" -> resolveRef(pkg, type, name)
            else -> null
        }
    }

    private fun resolveRef(
        pkg: String?,
        type: String,
        name: String,
    ): List<Pair<String, String>>? =
        when (type) {
            "string" -> res.resolveString(pkg, moduleName, name)?.let { listOf("" to normalizeText(it)) }
            else ->
                res.resolvePlurals(pkg, moduleName, name)?.let { quantities ->
                    listOf("one", "other").mapNotNull { q ->
                        quantities[q]?.let { "[$q]" to normalizeText(it) }
                    }.ifEmpty { null }
                }
        }

    private fun rRefOf(expression: KtExpression?): Triple<String?, String, String>? {
        val text = expression?.text?.trim() ?: return null
        val match = R_REF_REGEX.matchEntire(text) ?: return null
        return Triple(match.groupValues[1].ifEmpty { null }, match.groupValues[2], match.groupValues[3])
    }

    private fun templateSkeleton(template: KtStringTemplateExpression): String {
        val raw =
            template.entries.joinToString("") { entry ->
                when (entry) {
                    is KtLiteralStringTemplateEntry -> entry.text
                    is KtEscapeStringTemplateEntry -> entry.unescapedValue
                    else -> "{}" // interpolation
                }
            }
        return normalizeText(raw)
    }

    private fun concatSkeleton(expression: KtBinaryExpression): String {
        fun part(expr: KtExpression?): String =
            when {
                expr == null -> ""
                expr is KtBinaryExpression && expr.operationToken == KtTokens.PLUS -> concatSkeleton(expr)
                expr is KtStringTemplateExpression -> templateSkeleton(expr)
                else -> resolveExpr(expr)?.joinToString("") { it.second } ?: "{}"
            }
        // Skeletons are whitespace-normalized; join with a space so
        // `"Hello " + name` and `"Hello" + " " + name` agree.
        return normalizeText(part(expression.left) + " " + part(expression.right))
    }

    private fun isInPreview(element: org.jetbrains.kotlin.com.intellij.psi.PsiElement): Boolean =
        element.parents
            .filterIsInstance<KtNamedFunction>()
            .firstOrNull()
            ?.annotationEntries
            ?.any { it.shortName?.asString() == "Preview" }
            ?: false

    private companion object {
        val STRING_CALLERS = setOf("stringResource", "getString")
        val PLURAL_CALLERS = setOf("pluralStringResource", "getQuantityString")
        val R_REF_REGEX = Regex("""^(?:(\w[\w.]*)\.)?R\.(string|plurals)\.(\w+)$""")
    }
}

/** Normalizes rendered text so literal and resource spellings compare equal. */
fun normalizeText(raw: String): String =
    raw.replace(FORMAT_SPEC_REGEX, "{}")
        .replace(TEMPLATE_PLACEHOLDER_REGEX, "{}")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

private val FORMAT_SPEC_REGEX = Regex("""%(\d+\$)?[-+ 0#]*\d*\.?\d*[dfscx]""")
private val TEMPLATE_PLACEHOLDER_REGEX = Regex("""\{[^{}]*}""")
private val WHITESPACE_REGEX = Regex("""\s+""")
