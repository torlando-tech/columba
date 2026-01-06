package com.lxmf.messenger.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FileUtils functions.
 */
class FileUtilsTest {
    // ========== formatFileSize Tests ==========

    @Test
    fun `formatFileSize returns bytes for sizes less than 1KB`() {
        assertEquals("0 B", FileUtils.formatFileSize(0))
        assertEquals("1 B", FileUtils.formatFileSize(1))
        assertEquals("512 B", FileUtils.formatFileSize(512))
        assertEquals("1023 B", FileUtils.formatFileSize(1023))
    }

    @Test
    fun `formatFileSize returns KB for sizes between 1KB and 1MB`() {
        assertEquals("1.0 KB", FileUtils.formatFileSize(1024))
        assertEquals("1.5 KB", FileUtils.formatFileSize(1536))
        assertEquals("10.0 KB", FileUtils.formatFileSize(10 * 1024))
        assertEquals("512.0 KB", FileUtils.formatFileSize(512 * 1024))
        assertEquals("1023.9 KB", FileUtils.formatFileSize(1024 * 1024 - 100))
    }

    @Test
    fun `formatFileSize returns MB for sizes 1MB and above`() {
        assertEquals("1.0 MB", FileUtils.formatFileSize(1024 * 1024))
        assertEquals("1.5 MB", FileUtils.formatFileSize((1.5 * 1024 * 1024).toInt()))
        assertEquals("10.0 MB", FileUtils.formatFileSize(10 * 1024 * 1024))
    }

    // ========== getFileIconForMimeType Tests ==========

    @Test
    fun `getFileIconForMimeType returns PDF icon for PDF files`() {
        assertEquals(Icons.Default.PictureAsPdf, FileUtils.getFileIconForMimeType("application/pdf"))
    }

    @Test
    fun `getFileIconForMimeType returns Description icon for text files`() {
        assertEquals(Icons.Default.Description, FileUtils.getFileIconForMimeType("text/plain"))
        assertEquals(Icons.Default.Description, FileUtils.getFileIconForMimeType("text/html"))
        assertEquals(Icons.Default.Description, FileUtils.getFileIconForMimeType("text/csv"))
    }

    @Test
    fun `getFileIconForMimeType returns AudioFile icon for audio files`() {
        assertEquals(Icons.Default.AudioFile, FileUtils.getFileIconForMimeType("audio/mpeg"))
        assertEquals(Icons.Default.AudioFile, FileUtils.getFileIconForMimeType("audio/wav"))
        assertEquals(Icons.Default.AudioFile, FileUtils.getFileIconForMimeType("audio/ogg"))
    }

    @Test
    fun `getFileIconForMimeType returns VideoFile icon for video files`() {
        assertEquals(Icons.Default.VideoFile, FileUtils.getFileIconForMimeType("video/mp4"))
        assertEquals(Icons.Default.VideoFile, FileUtils.getFileIconForMimeType("video/webm"))
        assertEquals(Icons.Default.VideoFile, FileUtils.getFileIconForMimeType("video/x-matroska"))
    }

    @Test
    fun `getFileIconForMimeType returns FolderZip icon for archives`() {
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/zip"))
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/x-tar"))
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/gzip"))
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/x-rar-compressed"))
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/x-7z-compressed"))
    }

    @Test
    fun `getFileIconForMimeType returns generic icon for unknown types`() {
        assertEquals(Icons.Default.InsertDriveFile, FileUtils.getFileIconForMimeType("application/octet-stream"))
        assertEquals(Icons.Default.InsertDriveFile, FileUtils.getFileIconForMimeType("unknown/type"))
        assertEquals(Icons.Default.InsertDriveFile, FileUtils.getFileIconForMimeType(""))
    }

    // ========== getMimeTypeFromFilename Tests ==========

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for common document types`() {
        assertEquals("application/pdf", FileUtils.getMimeTypeFromFilename("document.pdf"))
        assertEquals("application/msword", FileUtils.getMimeTypeFromFilename("report.doc"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            FileUtils.getMimeTypeFromFilename("report.docx"),
        )
        assertEquals("application/vnd.ms-excel", FileUtils.getMimeTypeFromFilename("data.xls"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            FileUtils.getMimeTypeFromFilename("data.xlsx"),
        )
    }

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for text files`() {
        assertEquals("text/plain", FileUtils.getMimeTypeFromFilename("notes.txt"))
        assertEquals("text/csv", FileUtils.getMimeTypeFromFilename("data.csv"))
        assertEquals("application/json", FileUtils.getMimeTypeFromFilename("config.json"))
        assertEquals("text/html", FileUtils.getMimeTypeFromFilename("page.html"))
        assertEquals("text/html", FileUtils.getMimeTypeFromFilename("page.htm"))
    }

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for archives`() {
        assertEquals("application/zip", FileUtils.getMimeTypeFromFilename("files.zip"))
        assertEquals("application/x-rar-compressed", FileUtils.getMimeTypeFromFilename("files.rar"))
        assertEquals("application/x-7z-compressed", FileUtils.getMimeTypeFromFilename("files.7z"))
        assertEquals("application/x-tar", FileUtils.getMimeTypeFromFilename("files.tar"))
        assertEquals("application/gzip", FileUtils.getMimeTypeFromFilename("files.gz"))
    }

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for audio files`() {
        assertEquals("audio/mpeg", FileUtils.getMimeTypeFromFilename("song.mp3"))
        assertEquals("audio/wav", FileUtils.getMimeTypeFromFilename("sound.wav"))
        assertEquals("audio/ogg", FileUtils.getMimeTypeFromFilename("audio.ogg"))
        assertEquals("audio/flac", FileUtils.getMimeTypeFromFilename("music.flac"))
    }

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for video files`() {
        assertEquals("video/mp4", FileUtils.getMimeTypeFromFilename("video.mp4"))
        assertEquals("video/x-msvideo", FileUtils.getMimeTypeFromFilename("movie.avi"))
        assertEquals("video/x-matroska", FileUtils.getMimeTypeFromFilename("film.mkv"))
        assertEquals("video/webm", FileUtils.getMimeTypeFromFilename("clip.webm"))
    }

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for image files`() {
        assertEquals("image/jpeg", FileUtils.getMimeTypeFromFilename("photo.jpg"))
        assertEquals("image/jpeg", FileUtils.getMimeTypeFromFilename("photo.jpeg"))
        assertEquals("image/png", FileUtils.getMimeTypeFromFilename("screenshot.png"))
        assertEquals("image/gif", FileUtils.getMimeTypeFromFilename("animation.gif"))
        assertEquals("image/webp", FileUtils.getMimeTypeFromFilename("image.webp"))
    }

    @Test
    fun `getMimeTypeFromFilename returns octet-stream for unknown extensions`() {
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromFilename("file.unknown"))
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromFilename("data.xyz"))
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromFilename("binary"))
    }

    @Test
    fun `getMimeTypeFromFilename handles uppercase extensions`() {
        assertEquals("application/pdf", FileUtils.getMimeTypeFromFilename("DOCUMENT.PDF"))
        assertEquals("image/jpeg", FileUtils.getMimeTypeFromFilename("PHOTO.JPG"))
        assertEquals("application/zip", FileUtils.getMimeTypeFromFilename("ARCHIVE.ZIP"))
    }

    @Test
    fun `getMimeTypeFromFilename handles mixed case extensions`() {
        assertEquals("application/pdf", FileUtils.getMimeTypeFromFilename("document.Pdf"))
        assertEquals("image/png", FileUtils.getMimeTypeFromFilename("image.PNG"))
    }

    @Test
    fun `getMimeTypeFromFilename handles filenames with multiple dots`() {
        assertEquals("application/pdf", FileUtils.getMimeTypeFromFilename("report.2024.01.pdf"))
        assertEquals("application/zip", FileUtils.getMimeTypeFromFilename("backup.tar.zip"))
        assertEquals("application/gzip", FileUtils.getMimeTypeFromFilename("archive.tar.gz"))
    }

    // ========== wouldExceedSizeLimit Tests ==========
    // Note: With MAX_TOTAL_ATTACHMENT_SIZE = Int.MAX_VALUE, the limit is effectively unlimited.
    // Testing "exceeding" would require integer overflow, so we only test normal use cases.

    @Test
    fun `wouldExceedSizeLimit returns false when within limit`() {
        assertFalse(FileUtils.wouldExceedSizeLimit(0, 100))
        assertFalse(FileUtils.wouldExceedSizeLimit(256 * 1024, 256 * 1024))
        assertFalse(FileUtils.wouldExceedSizeLimit(400 * 1024, 112 * 1024))
    }

    @Test
    fun `wouldExceedSizeLimit returns false for large file sizes`() {
        // With Int.MAX_VALUE limit, realistic file sizes never exceed
        assertFalse(FileUtils.wouldExceedSizeLimit(0, 100 * 1024 * 1024)) // 100MB
        assertFalse(FileUtils.wouldExceedSizeLimit(500 * 1024 * 1024, 500 * 1024 * 1024)) // 1GB total
    }

    // ========== Constants Tests ==========

    @Test
    fun `MAX_TOTAL_ATTACHMENT_SIZE is Int MAX_VALUE`() {
        assertEquals(Int.MAX_VALUE, FileUtils.MAX_TOTAL_ATTACHMENT_SIZE)
    }

    // ========== Additional getMimeTypeFromFilename Tests ==========

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for PPT files`() {
        assertEquals("application/vnd.ms-powerpoint", FileUtils.getMimeTypeFromFilename("slides.ppt"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            FileUtils.getMimeTypeFromFilename("slides.pptx"),
        )
    }

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for XML files`() {
        assertEquals("application/xml", FileUtils.getMimeTypeFromFilename("config.xml"))
    }

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for SVG files`() {
        assertEquals("image/svg+xml", FileUtils.getMimeTypeFromFilename("icon.svg"))
    }

    @Test
    fun `getMimeTypeFromFilename returns correct MIME for APK files`() {
        assertEquals("application/vnd.android.package-archive", FileUtils.getMimeTypeFromFilename("app.apk"))
    }

    @Test
    fun `getMimeTypeFromFilename handles gzip extension`() {
        assertEquals("application/gzip", FileUtils.getMimeTypeFromFilename("archive.gzip"))
    }

    @Test
    fun `getMimeTypeFromFilename handles empty filename`() {
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromFilename(""))
    }

    @Test
    fun `getMimeTypeFromFilename handles filename with no extension`() {
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromFilename("Makefile"))
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromFilename("LICENSE"))
    }

    @Test
    fun `getMimeTypeFromFilename handles hidden files`() {
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromFilename(".gitignore"))
        assertEquals("application/json", FileUtils.getMimeTypeFromFilename(".eslintrc.json"))
    }

    // ========== Additional getFileIconForMimeType Tests ==========

    @Test
    fun `getFileIconForMimeType handles null-like and edge case MIME types`() {
        // Verify the else branch handles various edge cases
        assertEquals(Icons.Default.InsertDriveFile, FileUtils.getFileIconForMimeType("image/png"))
        assertEquals(Icons.Default.InsertDriveFile, FileUtils.getFileIconForMimeType("application/json"))
        assertEquals(Icons.Default.InsertDriveFile, FileUtils.getFileIconForMimeType("font/woff2"))
    }

    @Test
    fun `getFileIconForMimeType handles archive-like MIME types`() {
        // Test the "archive" substring match
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/x-archive"))
    }

    // ========== Additional formatFileSize Edge Case Tests ==========

    @Test
    fun `formatFileSize handles exactly 1KB boundary`() {
        assertEquals("1.0 KB", FileUtils.formatFileSize(1024))
    }

    @Test
    fun `formatFileSize handles exactly 1MB boundary`() {
        assertEquals("1.0 MB", FileUtils.formatFileSize(1024 * 1024))
    }

    @Test
    fun `formatFileSize handles large MB values`() {
        assertEquals("100.0 MB", FileUtils.formatFileSize(100 * 1024 * 1024))
        assertEquals("500.0 MB", FileUtils.formatFileSize(500 * 1024 * 1024))
    }

    @Test
    fun `formatFileSize handles fractional KB correctly`() {
        // 1500 bytes = 1.46484375 KB, should round to 1.5 KB
        assertEquals("1.5 KB", FileUtils.formatFileSize(1500))
    }

    @Test
    fun `formatFileSize handles fractional MB correctly`() {
        // 1.5 MB in bytes
        val oneAndHalfMB = (1.5 * 1024 * 1024).toInt()
        assertEquals("1.5 MB", FileUtils.formatFileSize(oneAndHalfMB))
    }

    @Test
    fun `formatFileSize uses US locale for decimal separator`() {
        // Verify the decimal separator is always a period (US locale)
        val result = FileUtils.formatFileSize(1536)
        assertTrue(result.contains("."))
        assertFalse(result.contains(","))
    }

    @Test
    fun `formatFileSize handles very small byte values`() {
        assertEquals("1 B", FileUtils.formatFileSize(1))
        assertEquals("2 B", FileUtils.formatFileSize(2))
        assertEquals("100 B", FileUtils.formatFileSize(100))
    }

    @Test
    fun `formatFileSize handles max KB value before MB`() {
        // Just under 1 MB (1024 * 1024 - 1)
        val justUnderMB = (1024 * 1024) - 1
        assertTrue(FileUtils.formatFileSize(justUnderMB).endsWith("KB"))
    }

    @Test
    fun `formatFileSize handles rounding at KB boundary`() {
        // Test rounding behavior at KB boundary
        assertEquals("1023 B", FileUtils.formatFileSize(1023))
        assertEquals("1.0 KB", FileUtils.formatFileSize(1024))
    }

    // ========== Additional Constants Tests ==========

    @Test
    fun `FILE_TRANSFER_THRESHOLD is 500KB`() {
        assertEquals(500 * 1024, FileUtils.FILE_TRANSFER_THRESHOLD)
    }

    @Test
    fun `MAX_SINGLE_FILE_SIZE is Int MAX_VALUE`() {
        assertEquals(Int.MAX_VALUE, FileUtils.MAX_SINGLE_FILE_SIZE)
    }

    // ========== Additional getFileIconForMimeType Edge Case Tests ==========

    @Test
    fun `getFileIconForMimeType handles PDF with additional parameters`() {
        // Some MIME types may have additional parameters
        assertEquals(Icons.Default.PictureAsPdf, FileUtils.getFileIconForMimeType("application/pdf; charset=utf-8"))
    }

    @Test
    fun `getFileIconForMimeType handles various text MIME types`() {
        assertEquals(Icons.Default.Description, FileUtils.getFileIconForMimeType("text/markdown"))
        assertEquals(Icons.Default.Description, FileUtils.getFileIconForMimeType("text/xml"))
        assertEquals(Icons.Default.Description, FileUtils.getFileIconForMimeType("text/javascript"))
    }

    @Test
    fun `getFileIconForMimeType handles all archive variants`() {
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/x-tar"))
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/x-compressed"))
        assertEquals(Icons.Default.FolderZip, FileUtils.getFileIconForMimeType("application/x-gzip"))
    }

    @Test
    fun `getFileIconForMimeType handles audio subtypes`() {
        assertEquals(Icons.Default.AudioFile, FileUtils.getFileIconForMimeType("audio/aac"))
        assertEquals(Icons.Default.AudioFile, FileUtils.getFileIconForMimeType("audio/midi"))
        assertEquals(Icons.Default.AudioFile, FileUtils.getFileIconForMimeType("audio/x-wav"))
    }

    @Test
    fun `getFileIconForMimeType handles video subtypes`() {
        assertEquals(Icons.Default.VideoFile, FileUtils.getFileIconForMimeType("video/quicktime"))
        assertEquals(Icons.Default.VideoFile, FileUtils.getFileIconForMimeType("video/mpeg"))
        assertEquals(Icons.Default.VideoFile, FileUtils.getFileIconForMimeType("video/3gpp"))
    }

    // ========== Additional getMimeTypeFromFilename Edge Case Tests ==========

    @Test
    fun `getMimeTypeFromFilename handles filename with only extension`() {
        assertEquals("application/pdf", FileUtils.getMimeTypeFromFilename(".pdf"))
    }

    @Test
    fun `getMimeTypeFromFilename handles filename ending with dot`() {
        // Filename ending in a dot has empty extension
        assertEquals("application/octet-stream", FileUtils.getMimeTypeFromFilename("file."))
    }

    @Test
    fun `getMimeTypeFromFilename handles filenames with spaces`() {
        assertEquals("application/pdf", FileUtils.getMimeTypeFromFilename("my document.pdf"))
        assertEquals("image/jpeg", FileUtils.getMimeTypeFromFilename("photo with spaces.jpg"))
    }

    @Test
    fun `getMimeTypeFromFilename handles filenames with special characters`() {
        assertEquals("application/zip", FileUtils.getMimeTypeFromFilename("archive-2024_01.zip"))
        assertEquals("text/plain", FileUtils.getMimeTypeFromFilename("notes (copy).txt"))
    }

    // ========== Additional wouldExceedSizeLimit Tests ==========

    @Test
    fun `wouldExceedSizeLimit returns false for zero values`() {
        assertFalse(FileUtils.wouldExceedSizeLimit(0, 0))
    }

    @Test
    fun `wouldExceedSizeLimit handles typical use cases`() {
        // Adding a 1MB file to empty list
        assertFalse(FileUtils.wouldExceedSizeLimit(0, 1024 * 1024))

        // Adding a small file to existing attachments
        assertFalse(FileUtils.wouldExceedSizeLimit(5 * 1024 * 1024, 100))
    }
}
