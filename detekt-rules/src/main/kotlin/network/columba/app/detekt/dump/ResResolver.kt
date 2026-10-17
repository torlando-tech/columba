package network.columba.app.detekt.dump

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Resolves `R.string.*` / `R.plurals.*` references to their English source text
 * from each module's XML files under `src/main/res/values`.
 *
 * `nonTransitiveRClass` keeps `R` references package-qualified in source, so the
 * package in front of `R` identifies the owning module; an unqualified `R`
 * belongs to the module that owns the file being dumped. Module -> namespace
 * comes from each module's `build.gradle.kts` `namespace = "…"` line.
 */
class ResResolver(root: File) {
    private val moduleDirs: Map<String, File>
    private val moduleByPackage: Map<String, String>
    private val stringCache = HashMap<String, Map<String, String>>()
    private val pluralsCache = HashMap<String, Map<String, Map<String, String>>>()

    init {
        val dirs = HashMap<String, File>()
        val byPackage = HashMap<String, String>()
        root.listFiles { f -> f.isDirectory }
            ?.forEach { dir ->
                val gradle = dir.resolve("build.gradle.kts")
                if (!gradle.isFile) return@forEach
                dirs[dir.name] = dir
                NAMESPACE_REGEX.find(gradle.readText())
                    ?.groupValues?.get(1)
                    ?.let { byPackage[it] = dir.name }
            }
        moduleDirs = dirs
        moduleByPackage = byPackage
    }

    /** English text of `R.string.[pkg.]name`, or null when unresolvable. */
    fun resolveString(
        pkg: String?,
        ownModule: String,
        name: String,
    ): String? {
        val module = moduleFor(pkg, ownModule) ?: return null
        return stringsOf(module)[name]
    }

    /** quantity -> text for `R.plurals.[pkg.]name`, or null when unresolvable. */
    fun resolvePlurals(
        pkg: String?,
        ownModule: String,
        name: String,
    ): Map<String, String>? {
        val module = moduleFor(pkg, ownModule) ?: return null
        return pluralsOf(module)[name]
    }

    private fun moduleFor(
        pkg: String?,
        ownModule: String,
    ): String? = if (pkg == null) ownModule.takeIf { it in moduleDirs } else moduleByPackage[pkg]

    private fun stringsOf(module: String): Map<String, String> =
        stringCache.getOrPut(module) {
            val map = HashMap<String, String>()
            valueFiles(module).forEach { xml ->
                val root = fileRoot(xml) ?: return@forEach
                children(root, "string").forEach { el ->
                    map[el.getAttribute("name")] = androidUnescape(el.textContent)
                }
            }
            map
        }

    private fun pluralsOf(module: String): Map<String, Map<String, String>> =
        pluralsCache.getOrPut(module) {
            val map = HashMap<String, HashMap<String, String>>()
            valueFiles(module).forEach { xml ->
                val root = fileRoot(xml) ?: return@forEach
                children(root, "plurals").forEach { pl ->
                    val quantities = HashMap<String, String>()
                    children(pl, "item").forEach { item ->
                        quantities[item.getAttribute("quantity")] = androidUnescape(item.textContent)
                    }
                    map[pl.getAttribute("name")] = quantities
                }
            }
            map
        }

    private fun valueFiles(module: String): List<File> =
        moduleDirs.getValue(module)
            .resolve("src/main/res/values")
            .listFiles { f -> f.isFile && f.name.endsWith(".xml") }
            ?.toList()
            .orEmpty()

    private fun fileRoot(
        xml: File,
    ): Element? =
        runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val doc = factory.newDocumentBuilder().parse(xml)
            doc.documentElement?.takeIf { it.tagName == "resources" }
        }.getOrNull()

    private fun children(
        scope: Element,
        tag: String,
    ): List<Element> {
        val out = ArrayList<Element>()
        val nodes = scope.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == tag) out.add(node)
        }
        return out
    }
    /** Undo Android resource escapes (`\\'`, `\\"`, `\\\\`, `\\n`, `\\@`, …). */
    private fun androidUnescape(text: String): String = ESCAPE_REGEX.replace(text) { it.groupValues[1] }

    private companion object {
        val NAMESPACE_REGEX = Regex("""namespace\s*=\s*"([^"]+)"""")
        val ESCAPE_REGEX = Regex("""\\(.)""")
    }
}
