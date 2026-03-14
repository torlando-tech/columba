package com.lxmf.messenger.micron

/**
 * Parser for Micron markup language used by NomadNet.
 *
 * Ported from NomadNet's MicronParser.py and validated against micron-parser-js.
 *
 * Micron is a line-oriented markup format with inline formatting via backtick commands.
 * Block-level elements are determined by the first character(s) of each line:
 * - `#` comment (or page directive like `#!bg=`)
 * - `>` heading (levels 1-3 by count)
 * - `-` divider
 * - `<` reset section depth
 * - `` `= `` toggle literal mode
 *
 * Inline formatting uses backtick + command character:
 * - `` `! `` bold, `` `* `` italic, `` `_ `` underline
 * - `` `Fxxx `` foreground color, `` `Bxxx `` background color
 * - `` `f `` reset foreground, `` `b `` reset background
 * - `` `c `` center, `` `l `` left, `` `r `` right, `` `a `` default alignment
 * - `` ` `` (backtick + backtick or end of token) reset all formatting
 */
object MicronParser {
    private const val DEFAULT_FIELD_WIDTH = 24
    private const val MAX_FIELD_WIDTH = 256
    private const val MAX_HEADING_LEVEL = 3

    @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    fun parse(
        markup: String,
        isDark: Boolean = true,
    ): MicronDocument {
        var pageBackground: MicronColor? = null
        var pageForeground: MicronColor? = null
        var cacheTime: Int? = null

        // Parser state that persists across lines
        var currentStyle = MicronStyle()
        var currentAlignment = MicronAlignment.LEFT
        var sectionDepth = 0
        var literalMode = false

        val outputLines = mutableListOf<MicronLine>()
        val inputLines = markup.lines()

        for (rawLine in inputLines) {
            val line = rawLine

            // Empty lines → line break
            if (line.isEmpty()) {
                outputLines.add(
                    MicronLine(
                        elements = listOf(MicronElement.LineBreak),
                        alignment = currentAlignment,
                        indentLevel = sectionDepth,
                    ),
                )
                continue
            }

            // Literal mode toggle: `= (exact match, no leading whitespace)
            if (line == "`=") {
                literalMode = !literalMode
                continue
            }

            // In literal mode, output line as-is (no formatting)
            if (literalMode) {
                outputLines.add(
                    MicronLine(
                        elements = listOf(MicronElement.Text(line, currentStyle)),
                        alignment = currentAlignment,
                        indentLevel = sectionDepth,
                    ),
                )
                continue
            }

            // Comments and page directives
            if (line.startsWith("#")) {
                if (line.startsWith("#!")) {
                    val directive = line.substring(2)
                    when {
                        directive.startsWith("bg=") -> {
                            pageBackground = MicronColor.parse(directive.substring(3))
                        }
                        directive.startsWith("fg=") -> {
                            pageForeground = MicronColor.parse(directive.substring(3))
                        }
                        directive.startsWith("c=") -> {
                            cacheTime = directive.substring(2).toIntOrNull()
                        }
                    }
                }
                // Comments are not rendered
                continue
            }

            // Section depth reset: < resets depth then re-parses remainder
            if (line.startsWith("<")) {
                sectionDepth = 0
                val remainder = line.drop(1)
                if (remainder.isEmpty()) continue
                // Re-parse the remainder as a regular content line
                val (elements, updatedStyle, updatedAlignment) =
                    parseInline(remainder, currentStyle, currentAlignment)
                currentStyle = updatedStyle
                currentAlignment = updatedAlignment
                outputLines.add(
                    MicronLine(
                        elements = elements,
                        alignment = currentAlignment,
                        indentLevel = sectionDepth,
                    ),
                )
                continue
            }

            // Headings: >, >>, >>>
            // D7: If line starts with > but contains `<, strip leading > chars
            // to prevent heading formatting from interfering with field rendering
            if (line.startsWith(">") && "`<" in line) {
                val stripped = line.trimStart('>')
                val (elements, updatedStyle, updatedAlignment) =
                    parseInline(stripped, currentStyle, currentAlignment)
                currentStyle = updatedStyle
                currentAlignment = updatedAlignment
                outputLines.add(
                    MicronLine(
                        elements = elements,
                        alignment = currentAlignment,
                        indentLevel = sectionDepth,
                    ),
                )
                continue
            }
            if (line.startsWith(">")) {
                val headingLevel = line.takeWhile { it == '>' }.length.coerceAtMost(MAX_HEADING_LEVEL)
                sectionDepth = headingLevel
                val headingText = line.drop(headingLevel)
                val headingStyle = MicronTheme.headingStyle(headingLevel, isDark)
                val style =
                    MicronStyle(
                        foreground = headingStyle.foreground,
                        background = headingStyle.background,
                        bold = true,
                    )
                val elements =
                    if (headingText.isBlank()) {
                        listOf(MicronElement.Text(" ", style))
                    } else {
                        parseInline(headingText, style, currentAlignment).first
                    }
                outputLines.add(
                    MicronLine(
                        elements = elements,
                        alignment = currentAlignment,
                        indentLevel = sectionDepth,
                        isHeading = true,
                        headingLevel = headingLevel,
                    ),
                )
                continue
            }

            // Dividers
            if (line.startsWith("-")) {
                var dividerChar = if (line.length == 2) line[1] else '\u2500' // ─
                // D8: Replace control characters (ord < 32) with default
                if (dividerChar.code < 32) dividerChar = '\u2500'
                outputLines.add(
                    MicronLine(
                        elements = listOf(MicronElement.Divider(dividerChar)),
                        alignment = currentAlignment,
                        indentLevel = sectionDepth,
                    ),
                )
                continue
            }

            // Partials: `{url`refresh`fields}
            if (line.startsWith("`{")) {
                val closeBrace = line.indexOf('}')
                if (closeBrace != -1) {
                    val partial = parsePartial(line.substring(2, closeBrace))
                    if (partial != null) {
                        outputLines.add(
                            MicronLine(
                                elements = listOf(partial),
                                alignment = currentAlignment,
                                indentLevel = sectionDepth,
                            ),
                        )
                        continue
                    }
                }
            }

            // Regular content line — parse inline elements
            val (elements, updatedStyle, updatedAlignment) =
                parseInline(
                    line,
                    currentStyle,
                    currentAlignment,
                )
            currentStyle = updatedStyle
            currentAlignment = updatedAlignment

            outputLines.add(
                MicronLine(
                    elements = elements,
                    alignment = currentAlignment,
                    indentLevel = sectionDepth,
                ),
            )
        }

        return MicronDocument(
            lines = outputLines,
            pageBackground = pageBackground,
            pageForeground = pageForeground,
            cacheTime = cacheTime,
        )
    }

    /**
     * Parse inline elements from a content string.
     *
     * Returns a triple of (elements, updated style, updated alignment).
     * Style and alignment persist across lines (formatting doesn't reset at newlines).
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun parseInline(
        line: String,
        initialStyle: MicronStyle,
        initialAlignment: MicronAlignment,
    ): Triple<List<MicronElement>, MicronStyle, MicronAlignment> {
        val elements = mutableListOf<MicronElement>()
        var style = initialStyle
        var alignment = initialAlignment
        var i = 0
        val textBuffer = StringBuilder()

        fun flushText() {
            if (textBuffer.isNotEmpty()) {
                elements.add(MicronElement.Text(textBuffer.toString(), style))
                textBuffer.clear()
            }
        }

        while (i < line.length) {
            val c = line[i]

            // Escape character
            if (c == '\\' && i + 1 < line.length) {
                textBuffer.append(line[i + 1])
                i += 2
                continue
            }

            // Backtick formatting command (also entry point for links/fields)
            if (c == '`') {
                if (i + 1 >= line.length) {
                    // Trailing backtick — reset all formatting
                    flushText()
                    style = MicronStyle()
                    i++
                    continue
                }

                val cmd = line[i + 1]
                val result = processFormatCommand(cmd, line, i, style, alignment)
                if (result != null) {
                    flushText()
                    style = result.style
                    alignment = result.alignment
                    i = result.nextIndex
                    continue
                }

                // Backtick before link `[...] — parse link inline
                if (cmd == '[') {
                    val linkStart = i + 2 // after `[
                    val linkEnd = line.indexOf(']', linkStart)
                    if (linkEnd != -1) {
                        flushText()
                        val linkData = line.substring(linkStart, linkEnd)
                        elements.add(parseLink(linkData, style))
                        i = linkEnd + 1
                        continue
                    }
                    // No closing ] — treat backtick as formatting entry, [ as literal
                    textBuffer.append('[')
                    i += 2
                    continue
                }

                // Backtick before field `<...> — parse field inline
                if (cmd == '<') {
                    val fieldStart = i + 2 // after `<
                    val fieldEnd = line.indexOf('>', fieldStart)
                    val fieldElement = if (fieldEnd != -1) parseField(line.substring(fieldStart, fieldEnd), style) else null
                    if (fieldElement != null) {
                        flushText()
                        elements.add(fieldElement)
                        i = fieldEnd + 1
                        continue
                    }
                    // Not a valid field — treat `< as literal
                    textBuffer.append('<')
                    i += 2
                    continue
                }

                // Unknown formatting command — consume backtick + command char
                // (matches Python MicronParser.py lines 684-686: unrecognized
                // chars in formatting mode are silently discarded)
                flushText()
                i += 2
                continue
            }

            // Regular character
            textBuffer.append(c)
            i++
        }

        flushText()

        // If no elements were produced, add an empty text element to preserve the line
        if (elements.isEmpty()) {
            elements.add(MicronElement.Text("", style))
        }

        return Triple(elements, style, alignment)
    }

    private data class FormatResult(
        val style: MicronStyle,
        val alignment: MicronAlignment,
        val nextIndex: Int,
    )

    @Suppress("CyclomaticComplexMethod")
    private fun processFormatCommand(
        cmd: Char,
        line: String,
        backtickIndex: Int,
        currentStyle: MicronStyle,
        currentAlignment: MicronAlignment,
    ): FormatResult? {
        var style = currentStyle
        var alignment = currentAlignment
        val afterCmd = backtickIndex + 2

        return when (cmd) {
            // Toggle bold
            '!' -> FormatResult(style.copy(bold = !style.bold), alignment, afterCmd)

            // Toggle italic
            '*' -> FormatResult(style.copy(italic = !style.italic), alignment, afterCmd)

            // Toggle underline
            '_' -> FormatResult(style.copy(underline = !style.underline), alignment, afterCmd)

            // Reset foreground color
            'f' -> FormatResult(style.copy(foreground = MicronColor.Default), alignment, afterCmd)

            // Reset background color
            'b' -> FormatResult(style.copy(background = MicronColor.Default), alignment, afterCmd)

            // Foreground color: Fxxx (3 chars)
            'F' -> {
                if (afterCmd + 3 <= line.length) {
                    val colorStr = line.substring(afterCmd, afterCmd + 3)
                    val color = MicronColor.parse(colorStr)
                    if (color != null) {
                        FormatResult(style.copy(foreground = color), alignment, afterCmd + 3)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            // Background color: Bxxx (3 chars)
            'B' -> {
                if (afterCmd + 3 <= line.length) {
                    val colorStr = line.substring(afterCmd, afterCmd + 3)
                    val color = MicronColor.parse(colorStr)
                    if (color != null) {
                        FormatResult(style.copy(background = color), alignment, afterCmd + 3)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }

            // Alignment
            'c' -> FormatResult(style, MicronAlignment.CENTER, afterCmd)
            'l' -> FormatResult(style, MicronAlignment.LEFT, afterCmd)
            'r' -> FormatResult(style, MicronAlignment.RIGHT, afterCmd)
            'a' -> FormatResult(style, MicronAlignment.LEFT, afterCmd)

            // Reset all formatting (backtick + backtick)
            '`' -> FormatResult(MicronStyle(), MicronAlignment.LEFT, afterCmd)

            else -> null
        }
    }

    /**
     * Parse a link from the content between [ and ].
     * Formats:
     * - `destination` → label=destination, no fields
     * - `label`destination` → label + destination
     * - `label`destination`field1|field2` → label + destination + field names
     */
    private fun parseLink(
        linkData: String,
        style: MicronStyle,
    ): MicronElement.Link {
        val components = linkData.split('`')
        val label: String
        val destination: String
        val fieldNames: List<String>

        when (components.size) {
            1 -> {
                label = components[0]
                destination = components[0]
                fieldNames = emptyList()
            }
            2 -> {
                label = components[0]
                destination = components[1]
                fieldNames = emptyList()
            }
            else -> {
                label = components[0]
                destination = components[1]
                // Fields are pipe-separated in the third component
                fieldNames =
                    if (components[2].isNotEmpty()) {
                        components[2].split('|')
                    } else {
                        emptyList()
                    }
            }
        }

        return MicronElement.Link(
            label = label.ifEmpty { destination },
            destination = destination,
            fieldNames = fieldNames,
            style = style.copy(underline = true),
        )
    }

    /**
     * Parse a partial from the content between `{ and }.
     * Format: `url`, `url`refresh`, or `url`refresh`fields`
     * Fields are pipe-separated; "pid=<value>" extracts a partial ID.
     */
    private fun parsePartial(content: String): MicronElement.Partial? {
        if (content.isEmpty()) return null
        val components = content.split('`')
        val url = components[0]
        if (url.isEmpty()) return null
        val refreshInterval = components.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 1 }
        val fieldsStr = components.getOrNull(2) ?: ""
        val fieldNames = if (fieldsStr.isNotEmpty()) fieldsStr.split('|') else emptyList()
        val partialId = fieldNames.firstOrNull { it.startsWith("pid=") }?.substringAfter("pid=")
        return MicronElement.Partial(
            url = url,
            refreshInterval = refreshInterval,
            fieldNames = fieldNames,
            partialId = partialId,
        )
    }

    /**
     * Parse a field from the content between < and >.
     * Format: `flags|name`value` or `flags|name`value`label`prechecked`
     *
     * Flag characters:
     * - `!` = masked (password) field
     * - `?` = checkbox
     * - `^` = radio button
     * - digits = field width
     */
    private fun parseField(
        fieldContent: String,
        style: MicronStyle,
    ): MicronElement? {
        val backtickPos = fieldContent.indexOf('`')
        if (backtickPos == -1) return null

        val beforeBacktick = fieldContent.substring(0, backtickPos)
        val afterBacktick = fieldContent.substring(backtickPos + 1)

        // Parse flags and name from the part before the backtick
        var fieldType = FieldType.TEXT
        var fieldMasked = false
        var fieldWidth = DEFAULT_FIELD_WIDTH
        var fieldName: String
        var fieldValue: String

        if ('|' in beforeBacktick) {
            val parts = beforeBacktick.split('|', limit = 2)
            val flags = parts[0]
            fieldName = parts[1]

            // Parse flags
            var flagStr = flags
            when {
                '^' in flagStr -> {
                    fieldType = FieldType.RADIO
                    flagStr = flagStr.replace("^", "")
                }
                '?' in flagStr -> {
                    fieldType = FieldType.CHECKBOX
                    flagStr = flagStr.replace("?", "")
                }
                '!' in flagStr -> {
                    fieldMasked = true
                    flagStr = flagStr.replace("!", "")
                }
            }

            // Remaining flags are width
            if (flagStr.isNotEmpty()) {
                flagStr.toIntOrNull()?.let { w ->
                    fieldWidth = w.coerceIn(1, MAX_FIELD_WIDTH)
                }
            }
        } else {
            fieldName = beforeBacktick
        }

        // Parse value (and label/prechecked for checkbox/radio) from after backtick
        val valueParts = afterBacktick.split('`')
        fieldValue = valueParts.getOrElse(0) { "" }

        return when (fieldType) {
            FieldType.CHECKBOX -> {
                val label = valueParts.getOrElse(1) { fieldName }
                val prechecked = valueParts.getOrElse(2) { "" } == "*"
                MicronElement.Checkbox(
                    name = fieldName,
                    value = fieldValue,
                    label = label,
                    prechecked = prechecked,
                    style = style,
                )
            }
            FieldType.RADIO -> {
                val label = valueParts.getOrElse(1) { fieldName }
                val prechecked = valueParts.getOrElse(2) { "" } == "*"
                MicronElement.Radio(
                    name = fieldName,
                    value = fieldValue,
                    label = label,
                    prechecked = prechecked,
                    style = style,
                )
            }
            FieldType.TEXT ->
                MicronElement.Field(
                    name = fieldName,
                    defaultValue = fieldValue,
                    width = fieldWidth,
                    masked = fieldMasked,
                    style = style,
                )
        }
    }

    private enum class FieldType { TEXT, CHECKBOX, RADIO }
}
