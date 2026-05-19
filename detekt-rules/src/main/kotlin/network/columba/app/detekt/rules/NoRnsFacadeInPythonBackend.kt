package network.columba.app.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

/**
 * Flags any `rns_*.py` "facade" file under `:rns-backend-py/src/main/python/`.
 *
 * Rationale: the dual-build plan's slim-Python design is explicit — the Python
 * tree contains *only* upstream RNS/LXMF wheels, the architecturally-forced
 * `RNS.Interface` adapters, the Chaquopy env stubs, and the ~150-line
 * `event_bridge.py` callback receiver. The Kotlin sub-impls in
 * `:rns-backend-py` call upstream RNS/LXMF directly via `PyObject.callAttr(...)`.
 *
 * Re-introducing a Python facade layer (`rns_api.py`, `reticulum_wrapper.py`,
 * any `rns_*.py`) is the regression this rule prevents: it was the v0.10.x
 * antipattern the re-architecture deleted. The sanctioned escape valve for
 * "needs to run Python-side for perf" is extending `event_bridge.py`, never a
 * new facade file.
 *
 * Detekt only analyses Kotlin files, so this rule can't *visit* a `.py` file.
 * Instead it anchors on `:rns-backend-py`'s root impl ([ANCHOR_FILE]) — visited
 * exactly once per detekt run — and from there scans the sibling
 * `src/main/python/` tree for forbidden files. The scan logic is extracted
 * into [findRnsFacadeFiles] so it is unit-testable without a detekt harness.
 */
class NoRnsFacadeInPythonBackend(
    config: Config = Config.empty,
) : Rule(config) {
    override val issue =
        Issue(
            id = "NoRnsFacadeInPythonBackend",
            severity = Severity.Defect,
            description =
                "No rns_*.py facade may exist under :rns-backend-py/src/main/python/. " +
                    "The slim-Python design has Kotlin sub-impls call upstream RNS/LXMF " +
                    "directly via PyObject — a Python facade layer is the v0.10.x " +
                    "antipattern the dual-build re-architecture removed. Extend " +
                    "event_bridge.py if something genuinely must run Python-side.",
            debt = Debt.TWENTY_MINS,
        )

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)

        val path = file.virtualFilePath
        // Anchor on the python backend's root impl so the filesystem scan runs
        // once per detekt invocation, not once per Kotlin file in the module.
        if (!path.replace('\\', '/').endsWith(ANCHOR_FILE)) return

        val moduleRoot = path.replace('\\', '/').substringBefore("/src/")
        val pythonDir = File("$moduleRoot/src/main/python")

        findRnsFacadeFiles(pythonDir).forEach { facadeName ->
            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(file),
                    message =
                        "Forbidden Python facade file '$facadeName' under " +
                            ":rns-backend-py/src/main/python/. The slim-Python design " +
                            "forbids rns_*.py facades — Kotlin sub-impls call upstream " +
                            "RNS/LXMF directly via PyObject. See :rns-backend-py/CLAUDE.md.",
                ),
            )
        }
    }

    companion object {
        /**
         * The anchor file whose `visitKtFile` triggers the python-tree scan.
         * `ChaquopyRnsBackend.kt` is `:rns-backend-py`'s documented root impl;
         * renaming it is a deliberate act that would also touch this rule.
         */
        private const val ANCHOR_FILE =
            "/rns-backend-py/src/main/kotlin/network/columba/app/rns/backend/py/ChaquopyRnsBackend.kt"

        private val FACADE_PATTERN = Regex("^rns_.*\\.py$")

        /**
         * Return the names of any `rns_*.py` files anywhere under [pythonDir].
         * Pure + filesystem-only so it can be unit-tested with a temp dir.
         */
        fun findRnsFacadeFiles(pythonDir: File): List<String> {
            if (!pythonDir.isDirectory) return emptyList()
            return pythonDir.walkTopDown()
                .filter { it.isFile && FACADE_PATTERN.matches(it.name) }
                .map { it.name }
                .sorted()
                .toList()
        }
    }
}
