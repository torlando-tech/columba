package com.lxmf.messenger.micron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicronParserTest {
    // ==================== Plain Text ====================

    @Test
    fun `plain text produces single text element`() {
        val doc = MicronParser.parse("Hello world")
        assertEquals(1, doc.lines.size)
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("Hello world", text.content)
    }

    @Test
    fun `empty input produces empty document`() {
        val doc = MicronParser.parse("")
        assertEquals(1, doc.lines.size)
        assertTrue(doc.lines[0].elements[0] is MicronElement.LineBreak)
    }

    @Test
    fun `multiple lines parsed separately`() {
        val doc = MicronParser.parse("line one\nline two\nline three")
        assertEquals(3, doc.lines.size)
    }

    @Test
    fun `empty line produces line break`() {
        val doc = MicronParser.parse("before\n\nafter")
        assertEquals(3, doc.lines.size)
        assertTrue(doc.lines[1].elements[0] is MicronElement.LineBreak)
    }

    // ==================== Comments ====================

    @Test
    fun `comment lines are skipped`() {
        val doc = MicronParser.parse("# This is a comment\nvisible text")
        assertEquals(1, doc.lines.size)
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("visible text", text.content)
    }

    @Test
    fun `page background directive parsed`() {
        val doc = MicronParser.parse("#!bg=00f\ntext")
        assertEquals(MicronColor.Hex(0x00, 0x00, 0xFF), doc.pageBackground)
    }

    @Test
    fun `page foreground directive parsed`() {
        val doc = MicronParser.parse("#!fg=ddd\ntext")
        assertEquals(MicronColor.Hex(0xDD, 0xDD, 0xDD), doc.pageForeground)
    }

    // ==================== Cache Directive ====================

    @Test
    fun `cache directive parsed`() {
        val doc = MicronParser.parse("#!c=300\ntext")
        assertEquals(300, doc.cacheTime)
    }

    @Test
    fun `cache directive zero means no cache`() {
        val doc = MicronParser.parse("#!c=0\ntext")
        assertEquals(0, doc.cacheTime)
    }

    @Test
    fun `no cache directive means null`() {
        val doc = MicronParser.parse("just text")
        assertNull(doc.cacheTime)
    }

    @Test
    fun `cache directive with other directives`() {
        val doc = MicronParser.parse("#!bg=000\n#!c=600\n#!fg=ddd\ntext")
        assertEquals(MicronColor.Hex(0x00, 0x00, 0x00), doc.pageBackground)
        assertEquals(MicronColor.Hex(0xDD, 0xDD, 0xDD), doc.pageForeground)
        assertEquals(600, doc.cacheTime)
    }

    @Test
    fun `invalid cache directive ignored`() {
        val doc = MicronParser.parse("#!c=abc\ntext")
        assertNull(doc.cacheTime)
    }

    // ==================== Headings ====================

    @Test
    fun `heading level 1`() {
        val doc = MicronParser.parse(">Heading One")
        val line = doc.lines[0]
        assertTrue(line.isHeading)
        assertEquals(1, line.headingLevel)
        assertEquals(1, line.indentLevel)
    }

    @Test
    fun `heading level 2`() {
        val doc = MicronParser.parse(">>Heading Two")
        val line = doc.lines[0]
        assertTrue(line.isHeading)
        assertEquals(2, line.headingLevel)
        assertEquals(2, line.indentLevel)
    }

    @Test
    fun `heading level 3`() {
        val doc = MicronParser.parse(">>>Heading Three")
        val line = doc.lines[0]
        assertTrue(line.isHeading)
        assertEquals(3, line.headingLevel)
        assertEquals(3, line.indentLevel)
    }

    @Test
    fun `heading level capped at 3`() {
        val doc = MicronParser.parse(">>>>Too many")
        assertEquals(3, doc.lines[0].headingLevel)
    }

    @Test
    fun `empty heading produces space text`() {
        val doc = MicronParser.parse(">")
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals(" ", text.content)
    }

    @Test
    fun `heading sets section depth for subsequent lines`() {
        val doc = MicronParser.parse(">>Heading\nContent after heading")
        assertEquals(2, doc.lines[0].indentLevel) // heading
        assertEquals(2, doc.lines[1].indentLevel) // content inherits depth
    }

    // ==================== Dividers ====================

    @Test
    fun `plain divider`() {
        val doc = MicronParser.parse("-")
        val divider = doc.lines[0].elements[0] as MicronElement.Divider
        assertEquals('\u2500', divider.character) // ─
    }

    @Test
    fun `divider with custom character`() {
        val doc = MicronParser.parse("-=")
        val divider = doc.lines[0].elements[0] as MicronElement.Divider
        assertEquals('=', divider.character)
    }

    @Test
    fun `divider with star character`() {
        val doc = MicronParser.parse("-*")
        val divider = doc.lines[0].elements[0] as MicronElement.Divider
        assertEquals('*', divider.character)
    }

    // ==================== Section Depth ====================

    @Test
    fun `depth reset with less-than`() {
        val doc = MicronParser.parse(">>Heading\n<\nAfter reset")
        assertEquals(0, doc.lines[1].indentLevel) // "After reset" at depth 0
    }

    // ==================== Bold / Italic / Underline ====================

    @Test
    fun `bold toggle`() {
        val doc = MicronParser.parse("`!bold text`! normal")
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        val bold = texts.first { it.content == "bold text" }
        assertTrue(bold.style.bold)
        val normal = texts.first { it.content == " normal" }
        assertFalse(normal.style.bold)
    }

    @Test
    fun `italic toggle`() {
        val doc = MicronParser.parse("`*italic`* normal")
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        val italic = texts.first { it.content == "italic" }
        assertTrue(italic.style.italic)
        val normal = texts.first { it.content == " normal" }
        assertFalse(normal.style.italic)
    }

    @Test
    fun `underline toggle`() {
        val doc = MicronParser.parse("`_underlined`_ normal")
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        val underlined = texts.first { it.content == "underlined" }
        assertTrue(underlined.style.underline)
        val normal = texts.first { it.content == " normal" }
        assertFalse(normal.style.underline)
    }

    @Test
    fun `nested bold and italic`() {
        val doc = MicronParser.parse("`!`*bold italic`*`!")
        val elements = doc.lines[0].elements
        // Find the "bold italic" text element
        val boldItalic =
            elements
                .filterIsInstance<MicronElement.Text>()
                .first { it.content == "bold italic" }
        assertTrue(boldItalic.style.bold)
        assertTrue(boldItalic.style.italic)
    }

    // ==================== Colors ====================

    @Test
    fun `foreground color hex`() {
        val doc = MicronParser.parse("`Ff00Red text")
        val elements = doc.lines[0].elements
        val colored =
            elements
                .filterIsInstance<MicronElement.Text>()
                .first { it.content == "Red text" }
        assertEquals(MicronColor.Hex(0xFF, 0x00, 0x00), colored.style.foreground)
    }

    @Test
    fun `background color hex`() {
        val doc = MicronParser.parse("`B00fBlue bg")
        val elements = doc.lines[0].elements
        val colored =
            elements
                .filterIsInstance<MicronElement.Text>()
                .first { it.content == "Blue bg" }
        assertEquals(MicronColor.Hex(0x00, 0x00, 0xFF), colored.style.background)
    }

    @Test
    fun `foreground grayscale`() {
        val doc = MicronParser.parse("`Fg50Gray text")
        val elements = doc.lines[0].elements
        val colored =
            elements
                .filterIsInstance<MicronElement.Text>()
                .first { it.content == "Gray text" }
        assertEquals(MicronColor.Grayscale(50), colored.style.foreground)
    }

    @Test
    fun `foreground color reset`() {
        val doc = MicronParser.parse("`Ff00Red`fDefault")
        val elements = doc.lines[0].elements
        val defaultText =
            elements
                .filterIsInstance<MicronElement.Text>()
                .first { it.content == "Default" }
        assertEquals(MicronColor.Default, defaultText.style.foreground)
    }

    @Test
    fun `background color reset`() {
        val doc = MicronParser.parse("`B00fBlue`bDefault")
        val elements = doc.lines[0].elements
        val defaultText =
            elements
                .filterIsInstance<MicronElement.Text>()
                .first { it.content == "Default" }
        assertEquals(MicronColor.Default, defaultText.style.background)
    }

    @Test
    fun `color ddd expands correctly`() {
        val color = MicronColor.parse("ddd")
        assertEquals(MicronColor.Hex(0xDD, 0xDD, 0xDD), color)
    }

    // ==================== Alignment ====================

    @Test
    fun `center alignment`() {
        val doc = MicronParser.parse("`cCentered text")
        assertEquals(MicronAlignment.CENTER, doc.lines[0].alignment)
    }

    @Test
    fun `right alignment`() {
        val doc = MicronParser.parse("`rRight text")
        assertEquals(MicronAlignment.RIGHT, doc.lines[0].alignment)
    }

    @Test
    fun `left alignment explicit`() {
        val doc = MicronParser.parse("`r`lLeft again")
        assertEquals(MicronAlignment.LEFT, doc.lines[0].alignment)
    }

    @Test
    fun `alignment persists across lines`() {
        val doc = MicronParser.parse("`cLine one\nLine two")
        assertEquals(MicronAlignment.CENTER, doc.lines[0].alignment)
        assertEquals(MicronAlignment.CENTER, doc.lines[1].alignment)
    }

    @Test
    fun `alignment reset with a`() {
        val doc = MicronParser.parse("`c`aDefault")
        assertEquals(MicronAlignment.LEFT, doc.lines[0].alignment)
    }

    // ==================== Reset All ====================

    @Test
    fun `double backtick resets all formatting`() {
        val doc = MicronParser.parse("`!`*`_`Ff00styled``reset")
        val elements = doc.lines[0].elements
        val reset =
            elements
                .filterIsInstance<MicronElement.Text>()
                .first { it.content == "reset" }
        assertFalse(reset.style.bold)
        assertFalse(reset.style.italic)
        assertFalse(reset.style.underline)
        assertEquals(MicronColor.Default, reset.style.foreground)
    }

    // ==================== Escape Characters ====================

    @Test
    fun `backslash escapes backtick`() {
        val doc = MicronParser.parse("\\`not a command")
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("`not a command", text.content)
    }

    @Test
    fun `backslash escapes backslash`() {
        val doc = MicronParser.parse("\\\\double")
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("\\double", text.content)
    }

    @Test
    fun `backslash escapes bracket`() {
        val doc = MicronParser.parse("\\[not a link]")
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("[not a link]", text.content)
    }

    // ==================== Literal Mode ====================

    @Test
    fun `literal mode preserves formatting characters`() {
        val doc = MicronParser.parse("`=\n`!not bold`!\n`=")
        assertEquals(1, doc.lines.size)
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("`!not bold`!", text.content)
    }

    @Test
    fun `literal mode toggle on and off`() {
        val doc = MicronParser.parse("before\n`=\nliteral\n`=\nafter")
        assertEquals(3, doc.lines.size)
        assertEquals("before", (doc.lines[0].elements[0] as MicronElement.Text).content)
        assertEquals("literal", (doc.lines[1].elements[0] as MicronElement.Text).content)
        assertEquals("after", (doc.lines[2].elements[0] as MicronElement.Text).content)
    }

    // ==================== Links ====================

    @Test
    fun `simple link with destination only`() {
        val doc = MicronParser.parse("`[/page/index.mu]")
        val link =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Link>()
                .first()
        assertEquals("/page/index.mu", link.label)
        assertEquals("/page/index.mu", link.destination)
        assertTrue(link.fieldNames.isEmpty())
    }

    @Test
    fun `link with label and destination`() {
        val doc = MicronParser.parse("`[Home Page`/page/index.mu]")
        val link =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Link>()
                .first()
        assertEquals("Home Page", link.label)
        assertEquals("/page/index.mu", link.destination)
    }

    @Test
    fun `link with field submission`() {
        val doc = MicronParser.parse("`[Submit`/page/form`username|password]")
        val link =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Link>()
                .first()
        assertEquals("Submit", link.label)
        assertEquals("/page/form", link.destination)
        assertEquals(listOf("username", "password"), link.fieldNames)
    }

    @Test
    fun `link has underline style`() {
        val doc = MicronParser.parse("`[Click me`/page]")
        val link =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Link>()
                .first()
        assertTrue(link.style.underline)
    }

    @Test
    fun `unclosed bracket treated as text`() {
        val doc = MicronParser.parse("[no closing bracket")
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("[no closing bracket", text.content)
    }

    @Test
    fun `text before and after link`() {
        val doc = MicronParser.parse("Click `[here`/page] for more")
        val elements = doc.lines[0].elements
        assertEquals("Click ", (elements[0] as MicronElement.Text).content)
        assertEquals("here", (elements[1] as MicronElement.Link).label)
        assertEquals(" for more", (elements[2] as MicronElement.Text).content)
    }

    // ==================== Form Fields ====================

    @Test
    fun `text field with name and default value`() {
        val doc = MicronParser.parse("`<|username`john>")
        val field =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Field>()
                .first()
        assertEquals("username", field.name)
        assertEquals("john", field.defaultValue)
        assertEquals(24, field.width) // default width
        assertFalse(field.masked)
    }

    @Test
    fun `text field with custom width`() {
        val doc = MicronParser.parse("`<32|email`user@example.com>")
        val field =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Field>()
                .first()
        assertEquals("email", field.name)
        assertEquals("user@example.com", field.defaultValue)
        assertEquals(32, field.width)
    }

    @Test
    fun `masked password field`() {
        val doc = MicronParser.parse("`<!|password`>")
        val field =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Field>()
                .first()
        assertEquals("password", field.name)
        assertTrue(field.masked)
    }

    @Test
    fun `checkbox field`() {
        val doc = MicronParser.parse("`<?|agree`yes`I agree to terms>")
        val checkbox =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Checkbox>()
                .first()
        assertEquals("agree", checkbox.name)
        assertEquals("yes", checkbox.value)
        assertEquals("I agree to terms", checkbox.label)
        assertFalse(checkbox.prechecked)
    }

    @Test
    fun `checkbox prechecked`() {
        val doc = MicronParser.parse("`<?|agree`yes`I agree`*>")
        val checkbox =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Checkbox>()
                .first()
        assertTrue(checkbox.prechecked)
    }

    @Test
    fun `radio button`() {
        val doc = MicronParser.parse("`<^|color`red`Red option>")
        val radio =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Radio>()
                .first()
        assertEquals("color", radio.name)
        assertEquals("red", radio.value)
        assertEquals("Red option", radio.label)
        assertFalse(radio.prechecked)
    }

    @Test
    fun `radio button prechecked`() {
        val doc = MicronParser.parse("`<^|color`blue`Blue`*>")
        val radio =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Radio>()
                .first()
        assertTrue(radio.prechecked)
    }

    @Test
    fun `unclosed field treated as text`() {
        // Bare < at line start triggers depth reset (D2), so the < is consumed
        // and remainder is parsed as text. Use backtick prefix to test field parsing.
        val doc = MicronParser.parse("`<no closing bracket")
        // No closing > means parseField fails — the < is treated as literal
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("<no closing bracket", text.content)
    }

    @Test
    fun `angle brackets without backtick treated as text`() {
        val doc = MicronParser.parse("Status: <Active>")
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        val combined = texts.joinToString("") { it.content }
        assertTrue(combined.contains("<Active>"))
    }

    // ==================== Formatting Persistence ====================

    @Test
    fun `bold persists across lines`() {
        val doc = MicronParser.parse("`!bold start\nstill bold")
        val line2 = doc.lines[1].elements[0] as MicronElement.Text
        assertTrue(line2.style.bold)
    }

    @Test
    fun `color persists across lines`() {
        val doc = MicronParser.parse("`Ff00red\nstill red")
        val line2 = doc.lines[1].elements[0] as MicronElement.Text
        assertEquals(MicronColor.Hex(0xFF, 0x00, 0x00), line2.style.foreground)
    }

    // ==================== MicronColor ====================

    @Test
    fun `color toArgb for hex`() {
        val color = MicronColor.Hex(0xAA, 0xBB, 0xCC)
        assertEquals(0xFFAABBCC.toInt(), color.toArgb())
    }

    @Test
    fun `color toArgb for grayscale 0 is black`() {
        val color = MicronColor.Grayscale(0)
        assertEquals(0xFF000000.toInt(), color.toArgb())
    }

    @Test
    fun `color toArgb for grayscale 99 is white`() {
        val color = MicronColor.Grayscale(99)
        assertEquals(0xFFFFFFFF.toInt(), color.toArgb())
    }

    @Test
    fun `color toArgb for default is null`() {
        assertNull(MicronColor.Default.toArgb())
    }

    @Test
    fun `color parse invalid returns null`() {
        assertNull(MicronColor.parse(""))
        assertNull(MicronColor.parse("zz"))
        assertNull(MicronColor.parse("xyz"))
    }

    @Test
    fun `color parse grayscale out of range returns null`() {
        // g followed by invalid number chars
        assertNull(MicronColor.parse("gxx"))
    }

    // ==================== MicronTheme ====================

    @Test
    fun `dark heading 1 style`() {
        val style = MicronTheme.headingStyle(1, isDark = true)
        assertEquals(MicronColor.Hex(0x22, 0x22, 0x22), style.foreground)
        assertEquals(MicronColor.Hex(0xBB, 0xBB, 0xBB), style.background)
    }

    @Test
    fun `light heading 1 style`() {
        val style = MicronTheme.headingStyle(1, isDark = false)
        assertEquals(MicronColor.Hex(0x00, 0x00, 0x00), style.foreground)
        assertEquals(MicronColor.Hex(0x77, 0x77, 0x77), style.background)
    }

    // ==================== Complex Document ====================

    @Test
    fun `complex document with mixed elements`() {
        val markup =
            """
            #!bg=000
            #!fg=ddd
            >Welcome
            -
            `!Bold text`! and `*italic`*
            `[Visit`/page/about.mu]
            `<|name`Enter name>
            `[Submit`/page/submit`name]
            """.trimIndent()

        val doc = MicronParser.parse(markup)
        assertEquals(MicronColor.Hex(0x00, 0x00, 0x00), doc.pageBackground)
        assertEquals(MicronColor.Hex(0xDD, 0xDD, 0xDD), doc.pageForeground)

        // Heading
        assertTrue(doc.lines[0].isHeading)

        // Divider
        assertTrue(doc.lines[1].elements[0] is MicronElement.Divider)

        // Formatted text line
        val textLine = doc.lines[2].elements
        assertTrue(textLine.any { it is MicronElement.Text && it.style.bold })

        // Link
        assertTrue(doc.lines[3].elements.any { it is MicronElement.Link })

        // Field
        assertTrue(doc.lines[4].elements.any { it is MicronElement.Field })

        // Submit link with field names
        val submitLink =
            doc.lines[5]
                .elements
                .filterIsInstance<MicronElement.Link>()
                .first()
        assertEquals(listOf("name"), submitLink.fieldNames)
    }

    @Test
    fun `heading dark theme styling applied`() {
        val doc = MicronParser.parse(">Test Heading", isDark = true)
        val text = doc.lines[0].elements[0] as MicronElement.Text
        // Dark heading 1: fg=222, bg=bbb
        assertEquals(MicronColor.Hex(0x22, 0x22, 0x22), text.style.foreground)
        assertEquals(MicronColor.Hex(0xBB, 0xBB, 0xBB), text.style.background)
        assertTrue(text.style.bold)
    }

    @Test
    fun `heading light theme styling applied`() {
        val doc = MicronParser.parse(">Test Heading", isDark = false)
        val text = doc.lines[0].elements[0] as MicronElement.Text
        // Light heading 1: fg=000, bg=777
        assertEquals(MicronColor.Hex(0x00, 0x00, 0x00), text.style.foreground)
        assertEquals(MicronColor.Hex(0x77, 0x77, 0x77), text.style.background)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `trailing backtick resets formatting`() {
        // Trailing backtick resets, then next line inherits reset style
        val doc = MicronParser.parse("`!bold`\nnext line")
        val nextLine = doc.lines[1].elements[0] as MicronElement.Text
        assertFalse(nextLine.style.bold)
    }

    @Test
    fun `multiple fields on same line`() {
        val doc = MicronParser.parse("`<|first`John> `<|last`Doe>")
        val fields = doc.lines[0].elements.filterIsInstance<MicronElement.Field>()
        assertEquals(2, fields.size)
        assertEquals("first", fields[0].name)
        assertEquals("last", fields[1].name)
    }

    @Test
    fun `link with empty label uses destination`() {
        val doc = MicronParser.parse("`[`/page/home.mu]")
        val link =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Link>()
                .first()
        assertEquals("/page/home.mu", link.label)
    }

    // ==================== Backtick-prefixed links and fields ====================

    @Test
    fun `backtick before link is consumed not output literally`() {
        // `[label`dest] is the formatting-mode link syntax — backtick should not appear in output
        val doc = MicronParser.parse("`[Home`/page/index.mu]")
        val elements = doc.lines[0].elements
        val link = elements.filterIsInstance<MicronElement.Link>().first()
        assertEquals("Home", link.label)
        assertEquals("/page/index.mu", link.destination)
        // No stray backtick text element before the link
        val texts = elements.filterIsInstance<MicronElement.Text>()
        assertTrue(texts.none { it.content.contains("`") })
    }

    @Test
    fun `backtick before link with formatting`() {
        // Real-world pattern: `c`F0ad`_`[label`dest]`_`f
        val doc = MicronParser.parse("`c`F0ad`_`[\"Hoard's Heart\"`/page/hoardsheart.mu]`_`f")
        assertEquals(MicronAlignment.CENTER, doc.lines[0].alignment)
        val link =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Link>()
                .first()
        assertEquals("\"Hoard's Heart\"", link.label)
        assertEquals("/page/hoardsheart.mu", link.destination)
        // No stray backtick in any text element
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        assertTrue(texts.none { it.content.contains("`") })
    }

    @Test
    fun `backtick before field is consumed not output literally`() {
        val doc = MicronParser.parse("`<|username`john>")
        val field =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Field>()
                .first()
        assertEquals("username", field.name)
        assertEquals("john", field.defaultValue)
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        assertTrue(texts.none { it.content.contains("`") })
    }

    // ==================== Unknown formatting commands (backtick + unrecognized char) ====================

    @Test
    fun `backtick-space after link is consumed not rendered`() {
        // NomadNet pattern: `[Send`:/page/rrc/chat.mu`fields]`
        // The trailing ` (backtick-space) should be silently discarded
        val doc = MicronParser.parse("`[Send`:/page/rrc/chat.mu`msg]` after")
        val elements = doc.lines[0].elements
        val link = elements.filterIsInstance<MicronElement.Link>().first()
        assertEquals("Send", link.label)
        assertEquals(":/page/rrc/chat.mu", link.destination)
        // No stray backtick in any text element
        val texts = elements.filterIsInstance<MicronElement.Text>()
        assertTrue(texts.none { it.content.contains("`") })
        // "after" should appear without a leading backtick
        assertTrue(texts.any { it.content.trimStart() == "after" })
    }

    @Test
    fun `real-world NomadNet page with multiple styled links has no stray backticks`() {
        // Adapted from a real NomadNet chat page
        val markup = "`B333`[Send`:/page/rrc/chat.mu`msg]` `[Clear`:/page/rrc/chat.mu]` "
        val doc = MicronParser.parse(markup)
        val elements = doc.lines[0].elements
        val links = elements.filterIsInstance<MicronElement.Link>()
        assertEquals(2, links.size)
        assertEquals("Send", links[0].label)
        assertEquals("Clear", links[1].label)
        // No stray backtick in any text element
        val texts = elements.filterIsInstance<MicronElement.Text>()
        assertTrue(texts.none { it.content.contains("`") })
    }

    @Test
    fun `backtick plus unrecognized char both discarded`() {
        // `x should silently consume both the backtick and 'x'
        val doc = MicronParser.parse("before`xafter")
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        val combined = texts.joinToString("") { it.content }
        assertEquals("beforeafter", combined)
    }

    // ==================== Partials ====================

    @Test
    fun `partial with url only`() {
        val doc = MicronParser.parse("`{a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4:/page/status.mu}")
        assertEquals(1, doc.lines.size)
        val partial = doc.lines[0].elements[0] as MicronElement.Partial
        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4:/page/status.mu", partial.url)
        assertNull(partial.refreshInterval)
        assertTrue(partial.fieldNames.isEmpty())
        assertNull(partial.partialId)
    }

    @Test
    fun `partial with refresh interval`() {
        val doc = MicronParser.parse("`{a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4:/page/status.mu`30}")
        val partial = doc.lines[0].elements[0] as MicronElement.Partial
        assertEquals(30, partial.refreshInterval)
    }

    @Test
    fun `partial with refresh and fields`() {
        val doc = MicronParser.parse("`{a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4:/page/status.mu`10`field1|field2}")
        val partial = doc.lines[0].elements[0] as MicronElement.Partial
        assertEquals(10, partial.refreshInterval)
        assertEquals(listOf("field1", "field2"), partial.fieldNames)
    }

    @Test
    fun `partial with pid extraction`() {
        val doc = MicronParser.parse("`{a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4:/page/partial.mu`0`pid=status|field1}")
        val partial = doc.lines[0].elements[0] as MicronElement.Partial
        assertNull(partial.refreshInterval) // 0 maps to null (< 1)
        assertEquals("status", partial.partialId)
        assertEquals(listOf("pid=status", "field1"), partial.fieldNames)
    }

    @Test
    fun `partial same-node url`() {
        val doc = MicronParser.parse("`{:/page/local_partial.mu`60}")
        val partial = doc.lines[0].elements[0] as MicronElement.Partial
        assertEquals(":/page/local_partial.mu", partial.url)
        assertEquals(60, partial.refreshInterval)
    }

    @Test
    fun `partial zero refresh maps to null`() {
        val doc = MicronParser.parse("`{a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4:/page/status.mu`0}")
        val partial = doc.lines[0].elements[0] as MicronElement.Partial
        assertNull(partial.refreshInterval)
    }

    @Test
    fun `partial malformed no closing brace falls through to inline`() {
        val doc = MicronParser.parse("`{a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4:/page/status.mu")
        // Should be parsed as inline text (no closing brace)
        val elements = doc.lines[0].elements
        assertTrue(elements[0] is MicronElement.Text)
    }

    @Test
    fun `partial empty content returns null falls through to inline`() {
        val doc = MicronParser.parse("`{}")
        // Empty content → parsePartial returns null → falls through to inline
        val elements = doc.lines[0].elements
        assertTrue(elements[0] is MicronElement.Text)
    }

    @Test
    fun `field width clamped to max`() {
        val doc = MicronParser.parse("`<999|wide`>")
        val field =
            doc.lines[0]
                .elements
                .filterIsInstance<MicronElement.Field>()
                .first()
        assertEquals(256, field.width)
    }

    // ==================== D1: Links/fields require backtick prefix ====================

    @Test
    fun `D1 - bare bracket is literal text not a link`() {
        // In NomadNet, [ only starts a link when preceded by backtick (formatting mode)
        val doc = MicronParser.parse("x < y and [some note]")
        val elements = doc.lines[0].elements
        // Should be a single text element with the literal content
        val texts = elements.filterIsInstance<MicronElement.Text>()
        val combined = texts.joinToString("") { it.content }
        assertEquals("x < y and [some note]", combined)
        // No links or fields should be parsed
        assertTrue(elements.none { it is MicronElement.Link })
        assertTrue(elements.none { it is MicronElement.Field })
    }

    @Test
    fun `D1 - bare angle bracket is literal text not a field`() {
        val doc = MicronParser.parse("if x < 10 then y > 5")
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        val combined = texts.joinToString("") { it.content }
        assertEquals("if x < 10 then y > 5", combined)
        assertTrue(doc.lines[0].elements.none { it is MicronElement.Field })
    }

    @Test
    fun `D1 - backtick bracket still parses as link`() {
        val doc = MicronParser.parse("`[Home`/page/index.mu]")
        val link = doc.lines[0].elements.filterIsInstance<MicronElement.Link>().first()
        assertEquals("Home", link.label)
        assertEquals("/page/index.mu", link.destination)
    }

    @Test
    fun `D1 - backtick angle bracket still parses as field`() {
        val doc = MicronParser.parse("`<|username`john>")
        val field = doc.lines[0].elements.filterIsInstance<MicronElement.Field>().first()
        assertEquals("username", field.name)
        assertEquals("john", field.defaultValue)
    }

    // ==================== D2: Depth reset re-parses remainder ====================

    @Test
    fun `D2 - depth reset with content after less-than`() {
        // NomadNet: < resets depth and re-parses remainder of line
        val doc = MicronParser.parse(">>Heading\n<Content after reset")
        // "Content after reset" should appear at depth 0
        val lastLine = doc.lines.last { it.elements.any { e -> e is MicronElement.Text && e.content.contains("Content") } }
        assertEquals(0, lastLine.indentLevel)
        val text = lastLine.elements.filterIsInstance<MicronElement.Text>().first()
        assertEquals("Content after reset", text.content)
    }

    @Test
    fun `D2 - bare less-than still resets depth`() {
        val doc = MicronParser.parse(">>Heading\n<\nAfter reset")
        // Line after < should be at depth 0
        val afterLine = doc.lines.last()
        assertEquals(0, afterLine.indentLevel)
    }

    // ==================== D3: Literal mode exact match ====================

    @Test
    fun `D3 - indented literal toggle is not toggled`() {
        // NomadNet requires exact match: line == "`=" (no leading whitespace)
        val doc = MicronParser.parse("  `=\n`!bold text`!\n  `=")
        // Since "  `=" should NOT toggle literal mode, `!bold text`! should be parsed as bold
        val texts = doc.lines.flatMap { it.elements }.filterIsInstance<MicronElement.Text>()
        assertTrue(texts.any { it.style.bold && it.content == "bold text" })
    }

    @Test
    fun `D3 - exact literal toggle still works`() {
        val doc = MicronParser.parse("`=\n`!not bold`!\n`=")
        val text = doc.lines[0].elements[0] as MicronElement.Text
        assertEquals("`!not bold`!", text.content)
    }

    // ==================== D5: Divider custom char only for 2-char lines ====================

    @Test
    fun `D5 - divider with exactly 2 chars uses custom character`() {
        val doc = MicronParser.parse("-=")
        val divider = doc.lines[0].elements[0] as MicronElement.Divider
        assertEquals('=', divider.character)
    }

    @Test
    fun `D5 - divider with more than 2 chars uses default character`() {
        // NomadNet: only exactly 2-char lines use custom char; longer lines get default ─
        val doc = MicronParser.parse("-Hello")
        val divider = doc.lines[0].elements[0] as MicronElement.Divider
        assertEquals('\u2500', divider.character)
    }

    @Test
    fun `D5 - single dash uses default character`() {
        val doc = MicronParser.parse("-")
        val divider = doc.lines[0].elements[0] as MicronElement.Divider
        assertEquals('\u2500', divider.character)
    }

    // ==================== D6: No trimEnd on lines ====================

    @Test
    fun `D6 - trailing whitespace preserved`() {
        // Trailing spaces with background color are significant for pixel art
        val doc = MicronParser.parse("`B00ftext   ")
        val texts = doc.lines[0].elements.filterIsInstance<MicronElement.Text>()
        val combined = texts.joinToString("") { it.content }
        assertEquals("text   ", combined)
    }

    @Test
    fun `D6 - trailing spaces not stripped to empty line`() {
        // A line of only spaces should not become empty (LineBreak)
        val doc = MicronParser.parse("   ")
        val elements = doc.lines[0].elements
        assertTrue(elements[0] is MicronElement.Text)
        assertEquals("   ", (elements[0] as MicronElement.Text).content)
    }

    // ==================== D7: Heading + field sanitization ====================

    @Test
    fun `D7 - heading with field strips heading markers`() {
        // NomadNet: if line starts with > and contains `<, strip leading > chars
        val doc = MicronParser.parse(">`<|username`john>")
        // Should NOT be a heading — the > should be stripped
        assertFalse(doc.lines[0].isHeading)
        val field = doc.lines[0].elements.filterIsInstance<MicronElement.Field>().first()
        assertEquals("username", field.name)
    }

    // ==================== D8: Divider control character check ====================

    @Test
    fun `D8 - divider with control character uses default`() {
        // NomadNet: if ord(divider_char) < 32, use default ─
        val doc = MicronParser.parse("-\u0001")
        val divider = doc.lines[0].elements[0] as MicronElement.Divider
        assertEquals('\u2500', divider.character)
    }

    @Test
    fun `D8 - divider with tab control character uses default`() {
        val doc = MicronParser.parse("-\t")
        val divider = doc.lines[0].elements[0] as MicronElement.Divider
        assertEquals('\u2500', divider.character)
    }
}
