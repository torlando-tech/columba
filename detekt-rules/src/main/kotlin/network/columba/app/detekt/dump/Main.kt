package network.columba.app.detekt.dump

import java.io.File
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

/** Standalone PSI parser; the dumper needs no classpath, only syntax. */
object DumpEnv {
    private val disposable = Disposer.newDisposable("UiTextDump")

    private val env: KotlinCoreEnvironment by lazy {
        val config =
            CompilerConfiguration().apply {
                put(CommonConfigurationKeys.MODULE_NAME, "ui-text-dump")
                put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            }
        KotlinCoreEnvironment.createForProduction(disposable, config, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    }

    fun parse(
        name: String,
        text: String,
    ): KtFile = KtPsiFactory(env.project).createFile(name, text)
}

/**
 * Sink-equivalence dumper CLI (i18n ticket 23).
 *
 * Usage: `--root <repoRoot> --out <file> [--files <rel,rel,…>]`
 *
 * Dumps, for each file (all `src/main` Kotlin under each module by default), the ordered
 * UI-text skeleton. Output is deterministic and diff-friendly; see
 * [UiTextDumper] for normalization rules.
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val root = File(opts.getValue("root")).absoluteFile
    val out = File(opts.getValue("out"))
    val res = ResResolver(root)

    val files: List<File> =
        opts["files"]?.split(',')?.filter { it.isNotBlank() }?.map { File(root, it.trim()) }
            ?: root.listFiles { f -> f.isDirectory }
                .orEmpty()
                .flatMap { module ->
                    module.resolve("src/main")
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .toList()
                }
                .sortedBy { it.relativeTo(root).path }

    val sb = StringBuilder()
    for (file in files) {
        val rel = file.relativeTo(root).path
        if (!file.isFile) {
            sb.appendLine("### $rel")
            sb.appendLine("!! file missing")
            continue
        }
        val module = rel.substringBefore('/')
        val entries =
            try {
                UiTextDumper(res, module).dump(DumpEnv.parse(file.name, file.readText()))
            } catch (e: Exception) {
                sb.appendLine("### $rel")
                sb.appendLine("!! dump failed: ${e.message}")
                continue
            }
        sb.appendLine("### $rel")
        entries.forEach { sb.appendLine("%03d %-45s | %s".format(it.ordinal, it.kind, it.skeleton)) }
    }
    out.writeText(sb.toString())
    println("dumped ${files.size} files -> ${out.path}")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val map = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        require(args[i].startsWith("--")) { "unexpected argument: ${args[i]}" }
        map[args[i].removePrefix("--")] = args.getOrNull(i + 1) ?: error("missing value for ${args[i]}")
        i += 2
    }
    return map
}
