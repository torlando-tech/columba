package network.columba.app.migration

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Repro for COLUMBA-DS: the export manifest used to be serialized with
 * `json.encodeToString(bundle)`, which buffers the full manifest as a
 * char array, a String, and a byte array on top of the in-memory bundle.
 * A large bundle OOMs the process once the manifest outgrows the heap headroom.
 *
 * [MigrationExporterOomTest] forks this [MigrationExportOomChild] main in a
 * separate JVM capped at 256 MB (the production target footprint) and runs
 * the real [MigrationExporter.createExportZip]. The bundle is sized so the
 * whole-string encoding needs ~3x the manifest text (~300+ MB live) while
 * the streaming encoder stays bounded to the bundle plus small buffers.
 * Pre-fix the child dies with `OutOfMemoryError`; post-fix it completes and
 * the manifest round-trips through the import-side `decodeFromStream`.
 */
@Suppress("NoRelaxedMocks") // createExportZip only touches context.cacheDir/filesDir; collaborators are unused
object MigrationExportOomChild {
    private const val MESSAGE_COUNT = 200_000
    private const val CONTENT_LENGTH = 384
    private const val MANIFEST_FILENAME = "manifest.json"

    @JvmStatic
    fun main(args: Array<String>) {
        val workDir =
            File.createTempFile("columba-oom-repro", null).apply {
                delete()
                mkdirs()
            }
        try {
            run(workDir)
        } finally {
            // Never leave export archives behind, even on OOM.
            workDir.deleteRecursively()
        }
    }

    private fun run(workDir: File) {
        val json = Json { ignoreUnknownKeys = true }
        val context =
            mockk<Context>().apply {
                every { cacheDir } returns File(workDir, "cache").apply { mkdirs() }
                every { filesDir } returns File(workDir, "files").apply { mkdirs() }
            }

        val exporter =
            MigrationExporter(
                context,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
            )

        val bundle =
            MigrationBundle(
                identities = emptyList(),
                conversations = emptyList(),
                messages = buildMessages(),
                contacts = emptyList(),
                settings =
                    SettingsExport(
                        notificationsEnabled = true,
                        notificationReceivedMessage = true,
                        notificationReceivedMessageFavorite = false,
                        notificationHeardAnnounce = true,
                        notificationBleConnected = false,
                        notificationBleDisconnected = false,
                        autoAnnounceEnabled = true,
                        autoAnnounceIntervalMinutes = 5,
                        themePreference = "preset:VIBRANT",
                    ),
            )

        // createExportZip is private; invoke the production method directly.
        val method =
            MigrationExporter::class.java.getDeclaredMethod(
                "createExportZip",
                MigrationBundle::class.java,
                List::class.java,
                Function1::class.java,
            ).apply { isAccessible = true }
        val onProgress: Function1<Float, Unit> = { }
        val exportFile = method.invoke(exporter, bundle, emptyList<Any>(), onProgress) as File
        check(exportFile.exists()) { "export file missing: $exportFile" }

        // The streamed manifest must decode through the import-side path.
        ZipFile(exportFile).use { zip ->
            val entry = zip.getEntry(MANIFEST_FILENAME) ?: error("missing $MANIFEST_FILENAME")
            zip.getInputStream(entry).use { stream ->
                val decoded = json.decodeFromStream(MigrationBundle.serializer(), stream)
                check(decoded.messages.size == MESSAGE_COUNT) {
                    "manifest message count mismatch: ${decoded.messages.size}"
                }
            }
        }
        println("OK messages=$MESSAGE_COUNT size=${exportFile.length()}")
    }

    private fun buildMessages(): List<MessageExport> {
        val content = "x".repeat(CONTENT_LENGTH)
        val status = "SENT"
        val identityHash = "identity-1"
        return List(MESSAGE_COUNT) { i ->
            MessageExport(
                id = "message-$i",
                conversationHash = "conversation-${i % 1000}",
                identityHash = identityHash,
                content = content,
                timestamp = i.toLong(),
                isFromMe = i % 2 == 0,
                status = status,
                isRead = i % 3 == 0,
                fieldsJson = null,
            )
        }
    }
}

class MigrationExporterOomTest {
    /**
     * Runs the production export zip path under a 256 MB heap cap. Fails with
     * the pre-fix `encodeToString` implementation (child OOMs) and passes with
     * the streaming implementation.
     */
    @Test
    fun `export zip manifest stays bounded under a 256MB heap (COLUMBA-DS)`() {
        val javaExe = File(System.getProperty("java.home"), "bin/java").absolutePath
        // The Gradle test worker's own java.class.path is not reliable: test
        // classes load through a separate class loader, so the build exposes
        // the test task's runtime classpath explicitly.
        val classpath =
            System.getProperty("columba.test.runtimeClasspath")
                ?.takeIf { it.isNotBlank() }
                ?: System.getProperty("java.class.path")
        val process =
            ProcessBuilder(
                javaExe,
                "-Xmx256m",
                // mockk self-attaches a ByteBuddy agent in the forked JVM.
                "-Djdk.attach.allowAttachSelf=true",
                "-cp",
                classpath,
                "network.columba.app.migration.MigrationExportOomChild",
            )
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        assertEquals(
            "createExportZip must complete under a 256MB heap (COLUMBA-DS). Child output:\n$output",
            0,
            exitCode,
        )
        assertTrue(
            "child must report a verified manifest. Output:\n$output",
            output.contains("OK messages="),
        )
    }
}
