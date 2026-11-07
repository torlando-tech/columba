package network.columba.app.detekt.dump

import network.columba.app.detekt.rules.UiSinkTable
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhenExpression
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
 *  - `if`/`when`/`?:` results are followed branch-by-branch (including a
 *    conditional resource id passed to `stringResource`/`getString`), so a text
 *    change in any branch surfaces as its own entry instead of hiding behind
 *    one opaque `{}`;
 *  - anything else (variables, function calls, unknown conditionals) becomes
 *    `{}` — stable under renames; the gate cannot see inside without type
 *    resolution.
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

    /**
     * Per-function `val name -> skeleton` for locals whose initializer is a
     * literal or a resolvable resource call. Lets `contentDescription = cd`
     * where `val cd = stringResource(…)` compare equal to the pre-extraction
     * literal. Conflicting re-declarations of one name (shadowing) poison the
     * entry to opaque; only the enclosing function's scope is consulted.
     */
    private val localVals = HashMap<KtNamedFunction, HashMap<String, String>>()

    /** Import alias -> owning package (`import a.b.c.R as XyzR`). */
    private val aliasPkg = HashMap<String, String>()

    fun dump(file: KtFile): List<Entry> {
        entries.clear()
        localVals.clear()
        aliasPkg.clear()
        file.importDirectives.forEach { dir ->
            val fq = dir.importedFqName?.asString() ?: return@forEach
            val alias = dir.aliasName ?: return@forEach
            if (fq.endsWith(".R")) aliasPkg[alias] = fq.removeSuffix(".R")
        }
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

                // ViewModel-side message carriers: `UiMessage.Text(R.string.x, …)`
                // constructed here is displayed later via `resolve(...)` at a
                // render site, so the construction site is the only place the
                // text is statically visible. Match on the qualified name:
                // the bare callee of `UiMessage.Text(…)` is just `Text`, which
                // would collide with the Compose `Text` sink.
                carrierName(expression)?.let { name ->
                    uiMessageSkeleton(name, expression)?.forEach { (subKind, skeleton) ->
                        add("$name$subKind", skeleton)
                    }
                    return
                }

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
                        // `labelRes = R.string.x` is the resource twin of
                        // `label = "x"`; normalize so base and head line up.
                        val sinkName = name.removeSuffix("Res")
                        if (sinkName in UiSinkTable.namedSinks) {
                            emit(arg.getArgumentExpression(), "$callee.$sinkName")
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
                    val paramName = arg.getArgumentName()?.asName?.identifier ?: paramNames.getOrNull(index)
                    val sinkName = paramName?.removeSuffix("Res")
                    if (sinkName != null && sinkName in UiSinkTable.namedSinks) {
                        emit(arg.getArgumentExpression(), "${enumClass.name}.$sinkName")
                    }
                }
            }

            override fun visitProperty(property: KtProperty) {
                super.visitProperty(property)
                val name = property.name ?: return
                val fn = property.parents.filterIsInstance<KtNamedFunction>().firstOrNull() ?: return
                val init = property.initializer ?: return
                val skeleton =
                    when {
                        init is KtStringTemplateExpression -> templateSkeleton(init)
                        else -> flattened(init)
                    }
                if (skeleton != null) {
                    val scope = localVals.getOrPut(fn) { HashMap() }
                    val existing = scope[name]
                    // A second, different binding for the same name (shadowing
                    // in a nested block) makes the reference ambiguous: resolve
                    // it opaquely rather than risk comparing against the wrong
                    // binding and missing a real text change.
                    scope[name] = if (existing == null || existing == skeleton) {
                        skeleton
                    } else {
                        AMBIGUOUS
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
            expression is KtNameReferenceExpression ->
                add(kind, localVal(expression) ?: "{}")
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

    private fun localVal(ref: KtNameReferenceExpression): String? =
        ref.parents.filterIsInstance<KtNamedFunction>().firstOrNull()?.let { fn ->
            localVals[fn]?.get(ref.getReferencedName())?.takeIf { it != AMBIGUOUS }
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
        resolveConditional(expression)?.let { return it }
        rRefOf(expression)?.let { (pkg, type, name) -> return resolveRef(pkg, type, name) }
        val call =
            when (expression) {
                is KtCallExpression -> expression
                is KtDotQualifiedExpression -> expression.selectorExpression as? KtCallExpression
                else -> null
            } ?: return null
        val callee = call.calleeExpression?.text ?: return null
        carrierName(call)?.let { return uiMessageSkeleton(it, call) }
        val idArg = call.valueArguments.firstOrNull { rRefOf(it.getArgumentExpression()) != null }
        if (idArg != null) {
            val args =
                call.valueArguments
                    .filter { it !== idArg }
                    .map { argSkeleton(it.getArgumentExpression()) }
            val (pkg, type, name) = rRefOf(idArg.getArgumentExpression()) ?: return null
            return resolveResourceCall(callee, pkg, type, name, args)
        }
        // Conditional resource id: `stringResource(if (…) R.string.a else R.string.b)`
        // — the id slot is positional, so format args are everything after it.
        val branches =
            conditionalBranches(unwrapBlock(call.valueArguments.firstOrNull()?.getArgumentExpression()))
                ?: return null
        val tailArgs = call.valueArguments.drop(1).map { argSkeleton(it.getArgumentExpression()) }
        return branches
            .mapNotNull { (suffix, branch) ->
                val ref = rRefOf(branch)
                val resolved =
                    when {
                        ref != null -> resolveResourceCall(callee, ref.first, ref.second, ref.third, tailArgs)
                        branch != null -> resolveExpr(branch)
                        else -> null
                    } ?: return@mapNotNull null
                resolved.map { (sub, skeleton) -> (suffix + sub) to skeleton }
            }.flatten()
            .ifEmpty { null }
    }

    /**
     * Text contributions of a resource call: `stringResource`/`getString` -> one
     * entry, plurals -> `[one]` + `[other]` (the quantity selects the form, it is
     * not a format argument). [args] are the skeletons of every other argument.
     */
    private fun resolveResourceCall(
        callee: String,
        pkg: String?,
        type: String,
        name: String,
        args: List<String>,
    ): List<Pair<String, String>>? =
        when {
            callee in STRING_CALLERS && type == "string" ->
                res.resolveString(pkg, moduleName, name)?.let { listOf("" to normalizeText(substituteFmt(it, args))) }
            callee in PLURAL_CALLERS && type == "plurals" -> {
                // `pluralStringResource(id, quantity, *args)` /
                // `getQuantityString(resId, quantity, *args)`: the quantity
                // selects the form; it is not a format argument.
                val pluralArgs = args.drop(1)
                res.resolvePlurals(pkg, moduleName, name)?.let { quantities ->
                    listOf("one", "other").mapNotNull { q ->
                        quantities[q]?.let { "[$q]" to normalizeText(substituteFmt(it, pluralArgs)) }
                    }.ifEmpty { null }
                }
            }
            else -> null
        }

    /**
     * Branch-wise text of an `if`/`when`/`?:` expression, or null when the
     * expression is not conditional. Each resolvable branch becomes its own
     * entry (`[then][when2]…`), so the gate sees a text change in either branch
     * instead of one opaque `{}`.
     */
    private fun resolveConditional(expression: KtExpression?): List<Pair<String, String>>? {
        val branches = conditionalBranches(expression) ?: return null
        val out = ArrayList<Pair<String, String>>()
        branches.forEach { (suffix, branch) ->
            branchSkeleton(branch)?.forEach { (sub, skeleton) -> out += (suffix + sub) to skeleton }
        }
        return out.ifEmpty { null }
    }

    /** Conditional shapes with stable, source-order labels; conditions never label. */
    private fun conditionalBranches(expression: KtExpression?): List<Pair<String, KtExpression?>>? =
        when (expression) {
            is KtIfExpression ->
                listOf("[then]" to expression.then, "[else]" to expression.`else`)
            is KtWhenExpression ->
                expression.entries.mapIndexed { index, entry -> "[when${index + 1}]" to entry.expression }
            is KtBinaryExpression ->
                if (expression.operationToken == KtTokens.ELVIS) {
                    listOf("[left]" to expression.left, "[right]" to expression.right)
                } else {
                    null
                }
            else -> null
        }

    /** Resolved skeleton of one conditional branch, including literal/concat/local-val forms. */
    private fun branchSkeleton(expression: KtExpression?): List<Pair<String, String>>? {
        val expr = unwrapBlock(expression) ?: return null
        return when {
            expr is KtStringTemplateExpression -> listOf("" to templateSkeleton(expr))
            expr is KtBinaryExpression && expr.operationToken == KtTokens.PLUS -> listOf("" to concatSkeleton(expr))
            expr is KtNameReferenceExpression -> localVal(expr)?.let { listOf("" to it) }
            else -> resolveExpr(expr)
        }
    }

    /** A branch written as `{ …; value }` contributes its final expression. */
    private fun unwrapBlock(expression: KtExpression?): KtExpression? =
        if (expression is KtBlockExpression) expression.statements.lastOrNull() as? KtExpression else expression

    /**
     * Single-line skeleton for a value that may carry plural or conditional
     * variants (format args, concatenation parts, local vals). Each variant
     * keeps its label and escapes `\` / `|`, so `"A|B"` + `"C"` can never encode
     * as `"A"` + `"B|C"`.
     */
    private fun flattened(expression: KtExpression): String? {
        val resolved = resolveExpr(expression) ?: return null
        return if (resolved.size == 1 && resolved[0].first.isEmpty()) {
            resolved[0].second
        } else {
            resolved.joinToString("|") { (kind, skeleton) ->
                if (kind.isEmpty()) escapeVariant(skeleton) else "$kind ${escapeVariant(skeleton)}"
            }
        }
    }

    private fun escapeVariant(skeleton: String): String = skeleton.replace("\\", "\\\\").replace("|", "\\|")

    /**
     * Qualified name of a message-carrier constructor call — `UiMessage.Text`,
     * `UiMessage.Plural`, `FlashText.Resource`, or the `flashText` factory —
     * or null. The bare callee of `UiMessage.Text(…)` is just `Text`, so the
     * dot-qualified receiver is required to tell carriers apart from Compose
     * sinks with colliding names (`Text`, `Resource`).
     */
    private fun carrierName(call: KtCallExpression): String? {
        val callee = call.calleeExpression?.text ?: return null
        if (callee == "flashText") return "flashText"
        val receiver = (call.parent as? KtDotQualifiedExpression)?.receiverExpression?.text ?: return null
        return "$receiver.$callee".takeIf { it in UIMESSAGE_CONSTRUCTORS }
    }

    /**
     * Resolved text of a carrier construction. Only fires when the first
     * positional argument is an `R.string`/`R.plurals` reference (or an
     * `if`/`when`/`?:` whose branches are), so an unrelated constructor with a
     * matching name never matches. Format args come from a `listOf(…)` literal;
     * anything else contributes none.
     */
    private fun uiMessageSkeleton(
        name: String,
        call: KtCallExpression,
    ): List<Pair<String, String>>? {
        val positional = call.valueArguments.filter { it.getArgumentName() == null }
        val plural = name in PLURAL_CONSTRUCTORS
        val idExpr = positional.firstOrNull()?.getArgumentExpression() ?: return null
        val fmtArgs =
            if (name == "flashText") {
                // `flashText(id, vararg args)` — format args are positional.
                positional.drop(1).map { argSkeleton(it.getArgumentExpression()) }
            } else {
                formatArgsOf(positional.getOrNull(if (plural) 2 else 1)?.getArgumentExpression())
            }
        rRefOf(idExpr)?.let { (pkg, type, resName) -> return resolveCarrier(plural, pkg, type, resName, fmtArgs) }
        val branches = conditionalBranches(unwrapBlock(idExpr)) ?: return null
        return branches
            .mapNotNull { (suffix, branch) ->
                val (pkg, type, resName) = rRefOf(branch) ?: return@mapNotNull null
                resolveCarrier(plural, pkg, type, resName, fmtArgs)
                    ?.map { (sub, skeleton) -> (suffix + sub) to skeleton }
            }.flatten()
            .ifEmpty { null }
    }

    private fun resolveCarrier(
        plural: Boolean,
        pkg: String?,
        type: String,
        resName: String,
        fmtArgs: List<String>,
    ): List<Pair<String, String>>? {
        if (type != if (plural) "plurals" else "string") return null
        return if (plural) {
            res.resolvePlurals(pkg, moduleName, resName)?.let { quantities ->
                listOf("one", "other").mapNotNull { q ->
                    quantities[q]?.let { "[$q]" to normalizeText(substituteFmt(it, fmtArgs)) }
                }.ifEmpty { null }
            }
        } else {
            res.resolveString(pkg, moduleName, resName)?.let { listOf("" to normalizeText(substituteFmt(it, fmtArgs))) }
        }
    }

    private fun formatArgsOf(expr: KtExpression?): List<String> {
        val call = expr as? KtCallExpression ?: return emptyList()
        return when (call.calleeExpression?.text) {
            "emptyList" -> emptyList()
            "listOf", "listOfNotNull" -> call.valueArguments.map { argSkeleton(it.getArgumentExpression()) }
            else -> emptyList()
        }
    }

    /**
     * Substitutes call arguments into a format string the way `String.format`
     * would, so `Step %1$d: %2$s` called with `(1, "Scan to connect")` dumps
     * the same skeleton as the pre-extraction literal `"Step 1: Scan to
     * connect"`. Opaque args stay `{}`; passing args means the string is
     * formatted, so `%%` collapses.
     */
    private fun substituteFmt(fmt: String, args: List<String>): String {
        if (args.isEmpty()) return fmt
        var next = 0
        val out =
            FORMAT_SPEC_REGEX.replace(fmt) { m ->
                val pos = m.groupValues[1].removeSuffix("$")
                if (pos.isNotEmpty()) args.getOrNull(pos.toInt() - 1) ?: "{}" else args.getOrNull(next++) ?: "{}"
            }
        return out.replace("%%", "%")
    }

    private fun argSkeleton(expr: KtExpression?): String =
        when {
            expr == null -> "{}"
            expr is KtStringTemplateExpression -> templateSkeleton(expr)
            expr is KtConstantExpression -> expr.text
            else -> flattened(expr) ?: "{}"
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
        R_REF_REGEX.matchEntire(text)?.let { m ->
            return Triple(m.groupValues[1].ifEmpty { null }, m.groupValues[2], m.groupValues[3])
        }
        // Import alias: `import a.b.c.R as XyzR` makes `XyzR.string.x` resolvable.
        ALIAS_REF_REGEX.matchEntire(text)?.let { m ->
            val pkg = aliasPkg[m.groupValues[1]] ?: return null
            return Triple(pkg, m.groupValues[2], m.groupValues[3])
        }
        return null
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
                else -> flattened(expr) ?: "{}"
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
        val UIMESSAGE_CONSTRUCTORS = setOf("UiMessage.Text", "UiMessage.Plural", "FlashText.Resource")
        val PLURAL_CONSTRUCTORS = setOf("UiMessage.Plural")
        val R_REF_REGEX = Regex("""^(?:(\w[\w.]*)\.)?R\.(string|plurals)\.(\w+)$""")
        val ALIAS_REF_REGEX = Regex("""^(\w+R)\.(string|plurals)\.(\w+)$""")
        val AMBIGUOUS = "\u0000"
    }
}

/** Normalizes rendered text so literal and resource spellings compare equal. */
fun normalizeText(raw: String): String {
    // A resource that contains a real format specifier is a format string, so
    // Android collapses `%%` to a literal `%` at render time. Kotlin literals
    // never contain `%%`, so collapsing here makes `"%1$d%%"` (resource) equal
    // `"${x}%"` (literal). Strings with no specifier keep `%%` untouched.
    val collapsed = if (FORMAT_SPEC_REGEX.containsMatchIn(raw)) raw.replace("%%", "%") else raw
    return collapsed
        .replace(FORMAT_SPEC_REGEX, "{}")
        .replace(TEMPLATE_PLACEHOLDER_REGEX, "{}")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
}

// No space in the flag class: `% duty` is prose ("% duty cycle"), not the
// Java space-flag specifier; allowing space mangled `10% duty` into `{}`.
private val FORMAT_SPEC_REGEX = Regex("""%(\d+\$)?[-+0#]*\d*\.?\d*[dfscx]""")
private val TEMPLATE_PLACEHOLDER_REGEX = Regex("""\{[^{}]*}""")
private val WHITESPACE_REGEX = Regex("""\s+""")
