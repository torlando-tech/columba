package network.columba.app.migration

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles encryption and decryption of migration export files.
 *
 * Version 0x02 (legacy, whole-file GCM):
 * ```
 * [1 byte:  version (0x02)]
 * [16 bytes: PBKDF2 salt]
 * [12 bytes: AES-GCM IV/nonce]
 * [N bytes:  AES-256-GCM ciphertext (includes 16-byte auth tag)]
 * ```
 *
 * Version 0x03 (current, chunked GCM):
 * ```
 * [1 byte:  version (0x03)]
 * [16 bytes: PBKDF2 salt]
 * [12 bytes: base IV (random 4 bytes + 64-bit counter start)]
 * [8 bytes:  chunk size in bytes, big-endian]
 * [8 bytes:  total plaintext length in bytes, big-endian]
 * repeated per chunk:
 * [8 bytes:  chunk index, big-endian]
 * [12 bytes: chunk IV (base IV with index XORed into bytes 4..11)]
 * [chunk:    AES-256-GCM ciphertext (includes 16-byte auth tag)]
 * ```
 *
 * One PBKDF2-derived key covers every chunk; each chunk carries its own
 * 128-bit GCM auth tag, so a wrong password is detected on the first
 * chunk. The chunk size is capped at [CHUNK_SIZE_BYTES] because the
 * platform GCM *decryptor* buffers an entire ciphertext buffer internally
 * (verified empirically against SunJCE): whole-file decryption of a
 * ~100 MiB export OOMs, while per-chunk decryption stays bounded to one
 * chunk plus small buffers.
 *
 * Unencrypted (legacy) files start with the ZIP magic bytes (0x50 0x4B)
 * and are detected automatically during import.
 */
@Suppress("TooManyFunctions") // Chunked + legacy crypto paths; helpers kept private
object MigrationCrypto {
    /** Version byte written at the start of encrypted export files (current format). */
    const val ENCRYPTED_VERSION: Byte = 0x03

    /** Legacy whole-file GCM version; still accepted on import. */
    const val LEGACY_ENCRYPTED_VERSION: Byte = 0x02

    /** Maximum plaintext bytes per 0x03 chunk (bounds decrypt-side memory). */
    const val CHUNK_SIZE_BYTES = 8 * 1024 * 1024

    /** First two bytes of a ZIP file (PK). */
    private const val ZIP_MAGIC_BYTE_1: Byte = 0x50 // 'P'
    private const val ZIP_MAGIC_BYTE_2: Byte = 0x4B // 'K'

    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val KEY_LENGTH_BITS = 256
    private const val PBKDF2_ITERATIONS = 600_000
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"

    /** 0x03 fixed header size: version + salt + base IV + chunk size + total length. */
    private const val CHUNKED_HEADER_SIZE = 1 + SALT_LENGTH + IV_LENGTH + 8 + 8
    /** 0x03 per-chunk header size: chunk index + chunk IV. */
    private const val CHUNK_RECORD_HEADER_SIZE = 8 + IV_LENGTH

    /** Minimum password length enforced at the UI layer. */
    const val MIN_PASSWORD_LENGTH = 8

    /**
     * Encrypt a plaintext ZIP file in-place, replacing it with the encrypted format.
     *
     * Writes a temporary file beside the target and renames over it so the
     * export never holds the full plaintext or ciphertext in memory.
     *
     * @param plaintextZip the ZIP file to encrypt
     * @param password the user-chosen password
     * @return the same [File] reference, now containing encrypted data
     */
    fun encryptFile(
        plaintextZip: File,
        password: String,
    ): File {
        val tmp = File(plaintextZip.parentFile, plaintextZip.name + ".enc.tmp")
        try {
            encryptToFile(plaintextZip, password, tmp)
            if (!tmp.renameTo(plaintextZip)) {
                tmp.copyTo(plaintextZip, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return plaintextZip
    }

    /**
     * Encrypt a plaintext file into the chunked 0x03 format, streaming in
     * both directions (bounded memory for arbitrarily large exports).
     */
    fun encryptToFile(
        plaintext: File,
        password: String,
        destination: File,
    ) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val baseIv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)
        val totalLength = plaintext.length()
        val chunkSize = minOf(CHUNK_SIZE_BYTES, totalLength.coerceAtLeast(0).toInt()).coerceAtLeast(0)

        destination.outputStream().use { out ->
            out.write(ENCRYPTED_VERSION.toInt())
            out.write(salt)
            out.write(baseIv)
            writeLongBigEndian(out, chunkSize.toLong())
            writeLongBigEndian(out, totalLength)

            val numChunks =
                if (totalLength == 0L) 1L
                else ((totalLength + chunkSize - 1) / chunkSize)
            plaintext.inputStream().use { plainIn ->
                for (index in 0 until numChunks) {
                    val chunkLen =
                        if (index == numChunks - 1) (totalLength - (numChunks - 1) * chunkSize).toInt()
                        else chunkSize
                    val chunk = ByteArray(chunkLen)
                    if (chunkLen > 0) readFully(plainIn, chunk, chunkLen)
                    val chunkIv = chunkIv(baseIv, index)
                    val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
                    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
                    out.writeChunkRecord(index, chunkIv)
                    if (chunk.isNotEmpty()) {
                        // GCM streams: doFinal() over one chunk does not
                        // accumulate the ciphertext, and appends the 16-byte tag.
                        out.write(cipher.doFinal(chunk))
                    } else {
                        out.write(cipher.doFinal())
                    }
                }
            }
        }
    }

    /**
     * Decrypt an encrypted export file in-place, replacing it with the
     * plaintext ZIP.
     *
     * 0x03 files decrypt in per-chunk streaming passes (bounded memory).
     * 0x02 legacy files decrypt whole-file, as before.
     *
     * @param encryptedFile the encrypted file to decrypt
     * @param password the user-provided password
     * @return the same [File] reference, now containing plaintext ZIP data
     * @throws WrongPasswordException if the password is incorrect
     * @throws InvalidExportFileException if the format is not recognized
     */
    fun decryptFile(
        encryptedFile: File,
        password: String,
    ): File {
        val firstByte = encryptedFile.inputStream().use { it.read() }
        if (firstByte == -1) {
            throw InvalidExportFileException("Export file is empty")
        }
        val tmp = File(encryptedFile.parentFile, encryptedFile.name + ".dec.tmp")
        try {
            when (firstByte.toByte()) {
                ENCRYPTED_VERSION -> {
                    // Streaming: plaintext is written directly to the temp
                    // file in per-chunk passes.
                    decryptStream(encryptedFile.inputStream(), password).use { plain ->
                        tmp.outputStream().use { plain.copyTo(it) }
                    }
                }
                LEGACY_ENCRYPTED_VERSION -> {
                    val full = encryptedFile.readBytes()
                    tmp.writeBytes(decryptLegacy(full, password))
                }
                else -> throw InvalidExportFileException(
                    "Unrecognized export format (version byte: 0x${
                        String.format(java.util.Locale.ROOT, "%02X", firstByte)
                    })",
                )
            }
            if (!tmp.renameTo(encryptedFile)) {
                tmp.copyTo(encryptedFile, overwrite = true)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return encryptedFile
    }

    /**
     * Encrypt raw bytes with the chunked 0x03 format.
     *
     * @return the full encrypted payload including header and all chunk records
     */
    fun encrypt(
        plaintext: ByteArray,
        password: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.use { stream ->
            streamEncrypt(plaintext.inputStream(), stream, password, plaintext.size.toLong())
        }
        return out.toByteArray()
    }

    private fun streamEncrypt(
        plain: InputStream,
        out: OutputStream,
        password: String,
        totalLength: Long,
    ) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val baseIv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)
        val chunkSize = minOf(CHUNK_SIZE_BYTES, totalLength.coerceAtLeast(0).toInt()).coerceAtLeast(0)

        out.write(ENCRYPTED_VERSION.toInt())
        out.write(salt)
        out.write(baseIv)
        writeLongBigEndian(out, chunkSize.toLong())
        writeLongBigEndian(out, totalLength)

        val numChunks =
            if (totalLength == 0L) 1L else ((totalLength + chunkSize - 1) / chunkSize)
        for (index in 0 until numChunks) {
            val remaining = totalLength - index * chunkSize
            val chunk = ByteArray(if (remaining <= 0) 0 else minOf(chunkSize, remaining.toInt()))
            if (chunk.isNotEmpty()) readFully(plain, chunk, chunk.size)
            val chunkIv = chunkIv(baseIv, index)
            out.writeChunkRecord(index, chunkIv)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
            out.write(if (chunk.isEmpty()) cipher.doFinal() else cipher.doFinal(chunk))
        }
    }

    /**
     * Decrypt an encrypted export payload (either version).
     *
     * 0x02 legacy files are decrypted whole-file (the legacy behavior).
     * 0x03 files are decrypted chunk by chunk.
     *
     * @param encrypted the full encrypted payload (header + salt + IV + ciphertext)
     * @param password the user-provided password
     * @return the decrypted ZIP bytes
     * @throws WrongPasswordException if the password is incorrect (GCM auth tag mismatch)
     * @throws InvalidExportFileException if the file format is not recognized
     */
    @Suppress("ThrowsCount")
    fun decrypt(
        encrypted: ByteArray,
        password: String,
    ): ByteArray {
        if (encrypted.isEmpty()) {
            throw InvalidExportFileException("Export file is empty")
        }
        return when (encrypted[0]) {
            LEGACY_ENCRYPTED_VERSION -> decryptLegacy(encrypted, password)
            ENCRYPTED_VERSION -> {
                // Header must be fully present before we can trust anything.
                if (encrypted.size < CHUNKED_HEADER_SIZE) {
                    throw InvalidExportFileException("Export file is too small to be valid")
                }
                decryptStream(encrypted.inputStream(), password).use { it.readBytes() }
            }
            else -> throw InvalidExportFileException(
                "Unrecognized export format (version byte: 0x${
                    String.format(java.util.Locale.ROOT, "%02X", encrypted[0])
                })",
            )
        }
    }

    private fun decryptLegacy(
        encrypted: ByteArray,
        password: String,
    ): ByteArray {
        val headerSize = 1 + SALT_LENGTH + IV_LENGTH
        if (encrypted.size < headerSize + GCM_TAG_BYTES) {
            throw InvalidExportFileException("Export file is too small to be valid")
        }

        var offset = 1
        val salt = encrypted.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = encrypted.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
        val ciphertext = encrypted.copyOfRange(offset, encrypted.size)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        return try {
            cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPasswordException("Incorrect password", e)
        }
    }

    /**
     * Decrypt an encrypted export stream into an [InputStream] of the plaintext ZIP.
     *
     * 0x03 streams decrypt in per-chunk passes with bounded memory. 0x02
     * legacy streams fall back to whole-file decryption because the platform
     * GCM decryptor cannot stream.
     */
    @Suppress("ThrowsCount")
    fun decryptStream(
        encryptedStream: InputStream,
        password: String,
    ): InputStream {
        val first = encryptedStream.read()
        if (first == -1) {
            throw InvalidExportFileException("Export file is empty")
        }
        return when (first.toByte()) {
            LEGACY_ENCRYPTED_VERSION -> {
                val rest = encryptedStream.readBytes()
                val full = byteArrayOf(first.toByte()) + rest
                ByteArrayInputStream(decryptLegacy(full, password))
            }
            ENCRYPTED_VERSION -> {
                val header = ByteArray(CHUNKED_HEADER_SIZE - 1)
                readFully(encryptedStream, header, header.size)
                val headerAll = byteArrayOf(first.toByte()) + header
                val salt = headerAll.copyOfRange(1, 1 + SALT_LENGTH)
                val baseIv = headerAll.copyOfRange(1 + SALT_LENGTH, 1 + SALT_LENGTH + IV_LENGTH)
                val chunkSize = readLongBigEndian(headerAll, 1 + SALT_LENGTH + IV_LENGTH).toInt()
                val totalLength = readLongBigEndian(headerAll, 9 + SALT_LENGTH + IV_LENGTH)
                if (chunkSize < 0 || chunkSize > CHUNK_SIZE_BYTES) {
                    throw InvalidExportFileException("Invalid chunk size in export header")
                }
                if (totalLength < 0) {
                    throw InvalidExportFileException("Invalid plaintext length in export header")
                }
                val key = deriveKey(password, salt)
                ChunkDecryptInputStream(encryptedStream, key, baseIv, chunkSize, totalLength)
            }
            else -> throw InvalidExportFileException(
                "Unrecognized export format (version byte: 0x${
                    String.format(java.util.Locale.ROOT, "%02X", first)
                })",
            )
        }
    }

    /**
     * Check whether raw file bytes represent an encrypted export (vs. a legacy plaintext ZIP).
     *
     * @return `true` if the file starts with an encrypted version byte (0x02 or 0x03),
     *         `false` if it starts with ZIP magic bytes (legacy format)
     * @throws InvalidExportFileException if the format is not recognized at all
     */
    fun isEncrypted(header: ByteArray): Boolean {
        if (header.isEmpty()) {
            throw InvalidExportFileException("Export file is empty")
        }
        if (header[0] == ENCRYPTED_VERSION || header[0] == LEGACY_ENCRYPTED_VERSION) return true
        if (header.size >= 2 && header[0] == ZIP_MAGIC_BYTE_1 && header[1] == ZIP_MAGIC_BYTE_2) {
            return false
        }
        throw InvalidExportFileException(
            "Unrecognized export file format (starts with 0x${
                String.format(java.util.Locale.ROOT, "%02X", header[0])
            })",
        )
    }

    /**
     * Derive an AES-256 key from a password and salt using PBKDF2.
     */
    private fun deriveKey(
        password: String,
        salt: ByteArray,
    ): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            try {
                SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    /** 96-bit GCM IV for a chunk: random 4 bytes + 64-bit counter (base ^ index) in bytes 4..11. */
    private fun chunkIv(
        baseIv: ByteArray,
        index: Long,
    ): ByteArray {
        val iv = baseIv.copyOf()
        for (i in 0 until 8) {
            val counterByte = ((index ushr (56 - 8 * i)) and 0xFF).toInt()
            iv[4 + i] = (iv[4 + i].toInt() xor counterByte).toByte()
        }
        return iv
    }

    private fun OutputStream.writeChunkRecord(
        index: Long,
        chunkIv: ByteArray,
    ) {
        writeLongBigEndian(this, index)
        write(chunkIv)
    }

    private fun writeLongBigEndian(
        out: OutputStream,
        value: Long,
    ) {
        for (i in 7 downTo 0) {
            out.write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }

    private fun readLongBigEndian(
        buf: ByteArray,
        offset: Int,
    ): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (buf[offset + i].toInt() and 0xFF).toLong()
        }
        return value
    }

    private fun readFully(
        source: InputStream,
        buf: ByteArray,
        len: Int,
    ) {
        var got = 0
        while (got < len) {
            val r = source.read(buf, got, len - got)
            if (r < 0) throw InvalidExportFileException("Truncated export file")
            got += r
        }
    }

    /**
     * Streaming decryptor for the chunked 0x03 format.
     *
     * Buffers at most one plaintext chunk at a time; the underlying cipher
     * buffers at most one chunk of ciphertext, so peak memory is bounded by
     * ~2 x chunk size regardless of export size.
     */
    private class ChunkDecryptInputStream(
        private val src: InputStream,
        private val key: SecretKey,
        private val baseIv: ByteArray,
        private val chunkSize: Int,
        totalLength: Long,
    ) : InputStream() {
        private var plaintextRemaining = totalLength
        private var chunkIndex = 0L
        private var current: ByteArray? = null
        private var currentPos = 0

        override fun read(): Int {
            while (true) {
                val chunk = current
                if (chunk != null && currentPos < chunk.size) {
                    val b = chunk[currentPos++].toInt()
                    if (currentPos == chunk.size) current = null
                    return b and 0xFF
                }
                if (plaintextRemaining == 0L) return -1
                loadNextChunk()
            }
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            val first = read()
            if (first == -1) return -1
            b[off] = first.toByte()
            var got = 1
            while (got < len) {
                val chunk = current
                if (chunk != null && currentPos < chunk.size) {
                    val n = minOf(len - got, chunk.size - currentPos)
                    System.arraycopy(chunk, currentPos, b, off + got, n)
                    currentPos += n
                    got += n
                    if (currentPos == chunk.size) current = null
                } else {
                    if (plaintextRemaining == 0L) break
                    loadNextChunk()
                }
            }
            return got
        }

        @Suppress("ThrowsCount") // Corrupt/wrong-password chunks each fail fast
        private fun loadNextChunk() {
            val recordHeader = ByteArray(CHUNK_RECORD_HEADER_SIZE)
            readFully(src, recordHeader, recordHeader.size)
            val index = readLongBigEndian(recordHeader, 0)
            if (index != chunkIndex) {
                throw InvalidExportFileException("Corrupt export: chunk index $index, expected $chunkIndex")
            }
            val chunkIv = recordHeader.copyOfRange(8, 8 + IV_LENGTH)
            val expected = minOf(chunkSize, plaintextRemaining.toInt().coerceAtLeast(0))
            val ciphertext = ByteArray(expected + GCM_TAG_BYTES)
            readFully(src, ciphertext, ciphertext.size)

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkIv))
            val decrypted =
                try {
                    cipher.doFinal(ciphertext)
                } catch (e: javax.crypto.AEADBadTagException) {
                    throw WrongPasswordException("Incorrect password", e)
                }
            if (decrypted.size != expected) {
                throw InvalidExportFileException("Corrupt export: chunk $index size mismatch")
            }
            plaintextRemaining -= decrypted.size
            chunkIndex++
            current = decrypted
            currentPos = 0
        }
    }
}

/**
 * Thrown when the user provides an incorrect password during decryption.
 */
class WrongPasswordException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when the export file format is not recognized.
 */
class InvalidExportFileException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown when an encrypted export file is opened without providing a password.
 */
class PasswordRequiredException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
