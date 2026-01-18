package com.lxmf.messenger.detekt.rules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Detekt rule to enforce hierarchical BLE logging tags.
 *
 * All Kotlin classes in the BLE package that perform logging must define a TAG constant
 * following the pattern: Columba:BLE:K:<Component>
 *
 * Excluded from checking:
 * - Data classes (typically don't log)
 * - Enum classes (typically don't log)
 * - Test classes (in test/ directories or ending in Test)
 * - Exception classes (typically don't log)
 * - Sealed class subtypes (inner classes)
 *
 * This enables consistent log filtering:
 * - `adb logcat | grep "Columba:BLE"` - All BLE logs
 * - `adb logcat | grep "Columba:BLE:K"` - All Kotlin BLE logs
 * - `adb logcat | grep "Columba:BLE:K:Client"` - Specific component
 */
class BleLoggingTagRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "BleLoggingTag",
        severity = Severity.Maintainability,
        description = "BLE components must use hierarchical logging tags (Columba:BLE:K:<Component>)",
        debt = Debt.FIVE_MINS,
    )

    private val tagPattern = Regex("""^Columba:BLE:K:[A-Za-z]+$""")
    private val blePackagePattern = Regex("""com\.lxmf\.messenger\.reticulum\.ble\.""")

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)

        // Only check files in the BLE package
        val packageName = file.packageFqName.asString()
        if (!blePackagePattern.containsMatchIn(packageName)) {
            return
        }

        // Skip test files
        val filePath = file.virtualFilePath
        if (filePath.contains("/test/") || filePath.contains("/androidTest/")) {
            return
        }

        // Find all classes in this file
        file.declarations.filterIsInstance<KtClass>().forEach { ktClass ->
            checkClassForTag(ktClass)
        }
    }

    private fun checkClassForTag(ktClass: KtClass) {
        // Skip classes that typically don't need logging
        if (shouldSkipClass(ktClass)) {
            return
        }

        // Look for companion object
        val companionObject = ktClass.companionObjects.firstOrNull()
        if (companionObject == null) {
            // BLE classes should have a companion object with TAG
            reportMissingTag(ktClass)
            return
        }

        // Look for TAG property in companion object
        val tagProperty = findTagProperty(companionObject)
        if (tagProperty == null) {
            reportMissingTag(ktClass)
            return
        }

        // Check TAG value matches pattern
        val tagValue = extractStringValue(tagProperty)
        if (tagValue == null || !tagPattern.matches(tagValue)) {
            report(
                CodeSmell(
                    issue = issue,
                    entity = Entity.from(tagProperty),
                    message = "TAG must follow pattern 'Columba:BLE:K:<Component>' but was: $tagValue",
                ),
            )
        }
    }

    private fun shouldSkipClass(ktClass: KtClass): Boolean {
        val className = ktClass.name ?: return true

        // Skip data classes (models, DTOs)
        if (ktClass.isData()) return true

        // Skip enum classes
        if (ktClass.isEnum()) return true

        // Skip sealed classes (the sealed class itself may not log, subclasses are checked separately)
        if (ktClass.isSealed()) return true

        // Skip interfaces
        if (ktClass.isInterface()) return true

        // Skip exception classes
        if (className.endsWith("Exception")) return true

        // Skip test classes
        if (className.endsWith("Test")) return true

        // Skip inner/nested classes (they use parent's TAG)
        if (ktClass.isInner()) return true

        // Skip classes in model/dto packages (typically data containers)
        val packageName = ktClass.containingKtFile.packageFqName.asString()
        if (packageName.contains(".model") || packageName.contains(".dto")) return true

        return false
    }

    private fun findTagProperty(companionObject: KtObjectDeclaration): KtProperty? {
        return companionObject.declarations
            .filterIsInstance<KtProperty>()
            .find { it.name == "TAG" }
    }

    private fun extractStringValue(property: KtProperty): String? {
        val initializer = property.initializer?.text ?: return null
        // Remove quotes from string literal
        return initializer.trim('"')
    }

    private fun reportMissingTag(ktClass: KtClass) {
        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(ktClass),
                message = "BLE class '${ktClass.name}' must have a TAG constant in companion object",
            ),
        )
    }
}
