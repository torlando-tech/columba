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

    @Test
    fun `wouldExceedSizeLimit returns false when within limit`() {
        val maxSize = FileUtils.DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE
        assertFalse(FileUtils.wouldExceedSizeLimit(0, 100, maxSize))
        assertFalse(FileUtils.wouldExceedSizeLimit(256 * 1024, 256 * 1024, maxSize))
        assertFalse(FileUtils.wouldExceedSizeLimit(400 * 1024, 112 * 1024, maxSize))
    }

    @Test
    fun `wouldExceedSizeLimit returns false at exactly the limit`() {
        val maxSize = FileUtils.DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE
        assertFalse(FileUtils.wouldExceedSizeLimit(maxSize - 100, 100, maxSize))
        assertFalse(FileUtils.wouldExceedSizeLimit(0, maxSize, maxSize))
    }

    @Test
    fun `wouldExceedSizeLimit returns true when exceeding limit`() {
        val maxSize = FileUtils.DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE
        assertTrue(FileUtils.wouldExceedSizeLimit(maxSize, 1, maxSize))
        assertTrue(FileUtils.wouldExceedSizeLimit(maxSize - 100, 101, maxSize))
        assertTrue(FileUtils.wouldExceedSizeLimit(400 * 1024, 200 * 1024, 500 * 1024))
    }

    // ========== Constants Tests ==========

    @Test
    fun `DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE is 8MB`() {
        assertEquals(8 * 1024 * 1024, FileUtils.DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE)
    }

    @Test
    fun `DEFAULT_MAX_SINGLE_FILE_SIZE is 8MB`() {
        assertEquals(8 * 1024 * 1024, FileUtils.DEFAULT_MAX_SINGLE_FILE_SIZE)
    }

    @Test
    fun `SLOW_TRANSFER_WARNING_THRESHOLD is 1MB`() {
        assertEquals(1024 * 1024, FileUtils.SLOW_TRANSFER_WARNING_THRESHOLD)
    }

    @Test
    fun `wouldExceedSizeLimit with zero limit allows any size`() {
        // 0 means unlimited
        assertFalse(FileUtils.wouldExceedSizeLimit(100 * 1024 * 1024, 100 * 1024 * 1024, 0))
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
}
