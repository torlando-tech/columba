package com.lxmf.messenger.data.util

/**
 * Centralized text sanitization logic.
 *
 * This utility provides a single source of truth for sanitizing user-generated
 * text content, used by both repositories (app process) and ServicePersistenceManager
 * (service process).
 *
 * Sanitization removes:
 * - Leading/trailing whitespace
 * - Control characters (except newlines)
 * - Excessive spaces/tabs (normalized to single space)
 *
 * This prevents:
 * - UI rendering issues from control characters
 * - Database bloat from excessive whitespace
 * - Inconsistent display across different text renderers
 */
object TextSanitizer {
    /**
     * Maximum length for message content.
     */
    const val MAX_MESSAGE_LENGTH = 10_000

    /**
     * Maximum length for peer display names.
     */
    const val MAX_PEER_NAME_LENGTH = 100

    /**
     * Maximum length for message previews (in conversations list, notifications).
     */
    const val MAX_PREVIEW_LENGTH = 100

    // Pre-compiled regex patterns for performance
    // Note: Excludes \n, \r (newlines) and \t (tabs) from control char removal
    // so tabs can be normalized to spaces by WHITESPACE_PATTERN
    private val CONTROL_CHARS_PATTERN = Regex("[\\p{C}&&[^\n\r\t]]")
    private val WHITESPACE_PATTERN = Regex("[ \\t]+")

    /**
     * Sanitize text content by removing control characters and normalizing whitespace.
     *
     * @param text The text to sanitize
     * @param maxLength Maximum length to truncate to
     * @return Sanitized text
     */
    fun sanitize(
        text: String,
        maxLength: Int,
    ): String =
        text
            .trim()
            .replace(CONTROL_CHARS_PATTERN, "") // Remove control chars except newlines
            .replace(WHITESPACE_PATTERN, " ") // Normalize spaces/tabs, preserve newlines
            .take(maxLength)

    /**
     * Sanitize message content with default max length.
     */
    fun sanitizeMessage(text: String): String = sanitize(text, MAX_MESSAGE_LENGTH)

    /**
     * Sanitize peer name with default max length.
     */
    fun sanitizePeerName(text: String): String = sanitize(text, MAX_PEER_NAME_LENGTH)

    /**
     * Sanitize message preview with default max length.
     */
    fun sanitizePreview(text: String): String = sanitize(text, MAX_PREVIEW_LENGTH)
}
