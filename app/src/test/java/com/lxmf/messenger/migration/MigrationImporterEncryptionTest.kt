package com.lxmf.messenger.migration

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.lxmf.messenger.data.crypto.IdentityKeyEncryptor
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for encryption-related functionality in MigrationImporter.
 * Uses Robolectric for Context/ContentResolver access with mocked DB dependencies.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationImporterEncryptionTest {
    private lateinit var context: Context
    private lateinit var importer: MigrationImporter

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val testPassword = "test-password-12345"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        importer =
            MigrationImporter(
                context = context,
                database = mockk(),
                interfaceDatabase = mockk(),
                reticulumProtocol = mockk(),
                settingsRepository = mockk(),
                propagationNodeManager = mockk(),
                keyEncryptor = mockk(),
            )
    }

    // region Helper methods

    /**
     * Create a minimal valid MigrationBundle ZIP as raw bytes.
     */
    private fun createTestZipBytes(): ByteArray {
        val bundle =
            MigrationBundle(
                identities =
                    listOf(
                        IdentityExport(
                            identityHash = "abc123",
                            displayName = "Test User",
                            destinationHash = "dest456",
                            keyData = "dGVzdA==",
                            createdTimestamp = 1700000000000L,
                            lastUsedTimestamp = 1700001000000L,
                            isActive = true,
                        ),
                    ),
                conversations = emptyList(),
                messages = emptyList(),
                contacts = emptyList(),
                settings = SettingsExport(),
            )
        val manifestJson = json.encodeToString(bundle)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifestJson.toByteArray())
            zipOut.closeEntry()
        }
        return baos.toByteArray()
    }

    /**
     * Write bytes to a temp file and return a file:// Uri.
     */
    private fun writeTempFile(
        bytes: ByteArray,
        suffix: String = ".columba",
    ): Uri {
        val file = File.createTempFile("migration_test_", suffix, context.cacheDir)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return Uri.fromFile(file)
    }

    // endregion

    // region isEncryptedExport tests

    @Test
    fun `isEncryptedExport returns false for plaintext ZIP`() =
        runTest {
            val zipBytes = createTestZipBytes()
            val uri = writeTempFile(zipBytes)

            val result = importer.isEncryptedExport(uri)

            assertTrue(result.isSuccess)
            assertFalse(result.getOrThrow())
        }

    @Test
    fun `isEncryptedExport returns true for encrypted file`() =
        runTest {
            val zipBytes = createTestZipBytes()
            val encrypted = MigrationCrypto.encrypt(zipBytes, testPassword)
            val uri = writeTempFile(encrypted)

            val result = importer.isEncryptedExport(uri)

            assertTrue(result.isSuccess)
            assertTrue(result.getOrThrow())
        }

    @Test
    fun `isEncryptedExport returns failure for empty file`() =
        runTest {
            val uri = writeTempFile(ByteArray(0))

            val result = importer.isEncryptedExport(uri)

            assertTrue(result.isFailure)
        }

    @Test
    fun `isEncryptedExport returns failure for unrecognized format`() =
        runTest {
            val uri = writeTempFile(byteArrayOf(0x00, 0x01, 0x02, 0x03))

            val result = importer.isEncryptedExport(uri)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is InvalidExportFileException)
        }

    // endregion

    // region previewMigration tests

    @Test
    fun `previewMigration reads plaintext ZIP without password`() =
        runTest {
            val zipBytes = createTestZipBytes()
            val uri = writeTempFile(zipBytes)

            val result = importer.previewMigration(uri)

            assertTrue(result.isSuccess)
            val previewWithData = result.getOrThrow()
            assertEquals(1, previewWithData.preview.identityCount)
            assertEquals(listOf("Test User"), previewWithData.preview.identityNames)
        }

    @Test
    fun `previewMigration reads encrypted file with correct password`() =
        runTest {
            val zipBytes = createTestZipBytes()
            val encrypted = MigrationCrypto.encrypt(zipBytes, testPassword)
            val uri = writeTempFile(encrypted)

            val result = importer.previewMigration(uri, testPassword)

            assertTrue(result.isSuccess)
            val previewWithData = result.getOrThrow()
            assertEquals(1, previewWithData.preview.identityCount)
            assertEquals(listOf("Test User"), previewWithData.preview.identityNames)
        }

    @Test
    fun `previewMigration throws PasswordRequiredException for encrypted file without password`() =
        runTest {
            val zipBytes = createTestZipBytes()
            val encrypted = MigrationCrypto.encrypt(zipBytes, testPassword)
            val uri = writeTempFile(encrypted)

            val result = importer.previewMigration(uri, null)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is PasswordRequiredException)
        }

    @Test
    fun `previewMigration throws WrongPasswordException for encrypted file with wrong password`() =
        runTest {
            val zipBytes = createTestZipBytes()
            val encrypted = MigrationCrypto.encrypt(zipBytes, testPassword)
            val uri = writeTempFile(encrypted)

            val result = importer.previewMigration(uri, "wrong-password")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is WrongPasswordException)
        }

    // endregion
}
