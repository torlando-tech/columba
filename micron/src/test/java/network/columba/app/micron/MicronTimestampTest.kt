package network.columba.app.micron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The `` `T `` construct and the strftime engine behind it. Expected values follow Python
 * 3's `time.strftime`, the reference for the format language.
 *
 * The zones are real ones with a steady abbreviation — `America/Jamaica` is UTC-5 with no
 * daylight saving and `Asia/Tokyo` is UTC+9 — since `java.time.ZoneId` cannot represent an
 * arbitrary fixed offset under a name.
 */
class MicronTimestampTest {
    private val est = ZoneId.of("America/Jamaica")
    private val jst = ZoneId.of("Asia/Tokyo")

    private val fridayEvening = 1_789_178_907L // 2026-09-11 21:08:27 in UTC-5
    private val tuesdayMorning = 1_788_271_507L // 2026-09-01 09:05:07 in UTC-5

    @Test
    fun `strftime conversions`() {
        val cases =
            listOf(
                FormatCase("default format", fridayEvening, "", "Fri Sep 11, 2026 9:08:27PM EST"),
                FormatCase("explicit default format", fridayEvening, "%a %b %d, %Y %-I:%M:%S%p %Z", "Fri Sep 11, 2026 9:08:27PM EST"),
                FormatCase("12-hour pads by default", tuesdayMorning, "%I:%M:%S %p", "09:05:07 AM"),
                FormatCase("no-pad flag", tuesdayMorning, "%-I:%-M:%-S %-d/%-m", "9:5:7 1/9"),
                FormatCase("day of month", tuesdayMorning, "%d|%e|%-d", "01| 1|1"),
                FormatCase("month and year", tuesdayMorning, "%b %B %y %Y", "Sep September 26 2026"),
                FormatCase("h is a synonym for b", tuesdayMorning, "%h", "Sep"),
                FormatCase("weekday", fridayEvening, "%a %A", "Fri Friday"),
                FormatCase("24-hour clock", fridayEvening, "%H:%M:%S", "21:08:27"),
                FormatCase("iso shorthands", fridayEvening, "%F %T", "2026-09-11 21:08:27"),
                FormatCase("time shorthand", fridayEvening, "%R", "21:08"),
                FormatCase("us shorthand", fridayEvening, "%D", "09/11/26"),
                FormatCase("unix seconds", fridayEvening, "%s", "1789178907"),
                FormatCase("numeric zone offset", fridayEvening, "%z", "-0500"),
                FormatCase("zone abbreviation", fridayEvening, "%Z", "EST"),
                FormatCase("day of year", tuesdayMorning, "%j", "244"),
                FormatCase("day of year is three digits wide", 1_767_589_200L, "%j|%-j", "005|5"),
                FormatCase("literal percent", fridayEvening, "100%%", "100%"),
                FormatCase("trailing percent", fridayEvening, "50%", "50%"),
                FormatCase("unknown specifier passes through", fridayEvening, "%q", "%q"),
                FormatCase("unknown specifier keeps its no-pad flag", fridayEvening, "%-q", "%-q"),
                FormatCase("unknown multi-character specifier", fridayEvening, "%10d", "%10d"),
                FormatCase("bare percent", fridayEvening, "%", "%"),
                FormatCase("trailing no-pad percent", fridayEvening, "abc%-", "abc%"),
                FormatCase("no-pad is ignored where there is nothing to pad", fridayEvening, "%-Z|%-s|%-p", "EST|1789178907|PM"),
                FormatCase("text around the conversions", fridayEvening, "at %H:%M on %F", "at 21:08 on 2026-09-11"),
                FormatCase("century", tuesdayMorning, "%C", "20"),
                FormatCase("locale date and time, in the C locale", tuesdayMorning, "%c", "Tue Sep  1 09:05:07 2026"),
                FormatCase("iso week-based year", fridayEvening, "%G|%g", "2026|26"),
                FormatCase("hour, space padded", tuesdayMorning, "%k", " 9"),
                FormatCase("12-hour clock, space padded", tuesdayMorning, "%l", " 9"),
                FormatCase("locale date, in the C locale", fridayEvening, "%x", "09/11/26"),
                FormatCase("locale time, in the C locale", fridayEvening, "%X", "21:08:27"),
                FormatCase("locale 12-hour time, in the C locale", tuesdayMorning, "%r", "09:05:07 AM"),
                FormatCase("newline and tab", fridayEvening, "%n%t", "\n\t"),
                FormatCase("iso weekday and week number", fridayEvening, "%u|%w", "5|5"),
                FormatCase("week number, Sunday first", fridayEvening, "%U", "36"),
                FormatCase("week number, Monday first", fridayEvening, "%W", "36"),
                FormatCase("iso week number", fridayEvening, "%V", "37"),
                FormatCase("bsd date form", tuesdayMorning, "%v", " 1-Sep-2026"),
                FormatCase("date(1) form", tuesdayMorning, "%+", "Tue Sep  1 09:05:07 EST 2026"),
                FormatCase("space and zero padding flags", tuesdayMorning, "%-d|%_d|%0d|%-H|%_H|%0H", "1| 1|01|9| 9|09"),
                FormatCase("E and O modifiers change nothing in the C locale", tuesdayMorning, "%Ec|%Od|%OX|%OY", "Tue Sep  1 09:05:07 2026|01|09:05:07|2026"),
            )

        for (case in cases) {
            assertEquals(case.name, case.want, MicronTimestamp.formatUnix(case.seconds, case.format, est))
        }
    }

    @Test
    fun `midnight and noon are twelve on the 12-hour clock`() {
        assertEquals("12:00 AM", MicronTimestamp.formatUnix(1_789_102_800L, "%I:%M %p", est))
        assertEquals("12:00 PM", MicronTimestamp.formatUnix(1_789_146_000L, "%I:%M %p", est))
    }

    @Test
    fun `the viewer's zone decides the rendering`() {
        assertEquals("Fri Sep 11, 2026 9:08:27PM EST", MicronTimestamp.formatUnix(fridayEvening, "", est))
        assertEquals("Sat Sep 12, 2026 2:08:27AM UTC", MicronTimestamp.formatUnix(fridayEvening, "", ZoneOffset.UTC))
        assertEquals("Sat Sep 12, 2026 2:08:27AM UTC", MicronTimestamp.formatUnix(fridayEvening, "", ZoneId.of("UTC")))
        assertEquals("Sat Sep 12, 2026 11:08:27AM JST", MicronTimestamp.formatUnix(fridayEvening, "", jst))
        assertEquals("Fri Sep 11, 2026 10:08:27PM EDT", MicronTimestamp.formatUnix(fridayEvening, "", ZoneId.of("America/New_York")))
    }

    @Test
    fun `offset zones have no abbreviation of their own`() {
        assertEquals("-05:00", MicronTimestamp.formatUnix(fridayEvening, "%Z", ZoneOffset.ofHours(-5)))
        assertEquals("UTC", MicronTimestamp.formatUnix(fridayEvening, "%Z", ZoneOffset.UTC))
        // %z is minute-granular, as strftime is: the offset's seconds are dropped.
        assertEquals("-0044", MicronTimestamp.formatUnix(fridayEvening, "%z", ZoneOffset.ofTotalSeconds(-2670)))
        assertEquals("+0545", MicronTimestamp.formatUnix(fridayEvening, "%z", ZoneOffset.ofTotalSeconds(20700)))
    }

    @Test
    fun `format and zone default when omitted`() {
        assertEquals("Fri Sep 11, 2026 9:08:27PM EST", MicronTimestamp.formatUnix(fridayEvening, zone = est))
        assertEquals(
            MicronTimestamp.formatUnix(fridayEvening, "", ZoneId.systemDefault()),
            MicronTimestamp.formatUnix(fridayEvening),
        )
    }

    @Test
    fun `construct rendering`() {
        val local = MicronTimestamp.formatUnix(fridayEvening)
        val cases =
            listOf(
                MarkupCase("seconds alone use the default format", "`T1789178907`T", local),
                MarkupCase("a format overrides the default", "`T1789178907|%F %T`T", MicronTimestamp.formatUnix(fridayEvening, "%F %T")),
                MarkupCase("an empty format uses the default", "`T1789178907|`T", local),
                MarkupCase("the format is everything after the first pipe", "`T1789178907|%F|%T`T", MicronTimestamp.formatUnix(fridayEvening, "%F|%T")),
                MarkupCase("seconds are trimmed", "`T 1789178907 `T", local),
                MarkupCase("surrounding text is preserved", "posted `T1789178907|%s`T by Glenn", "posted 1789178907 by Glenn"),
                MarkupCase("two constructs on one line", "`T1789178907|%s`T and `T1788271507|%s`T", "1789178907 and 1788271507"),
                MarkupCase("adjacent constructs", "`T1`T`T2`T", MicronTimestamp.formatUnix(1) + MicronTimestamp.formatUnix(2)),
            )

        for (case in cases) {
            assertEquals(case.name, case.want, stripText(MicronParser.parse(case.markup)))
        }
    }

    @Test
    fun `a line-initial construct is not a partial`() {
        val text = stripText(MicronParser.parse("`T1789178907|%s`T Glenn: hello"))
        assertEquals("1789178907 Glenn: hello", text)
        assertFalse(text.contains("{"))
    }

    @Test
    fun `constructs that cannot be rendered stay text`() {
        val cases =
            listOf(
                MarkupCase("no closing marker", "`T1789178907", "1789178907"),
                MarkupCase("no digits", "`Tsoon`T", "soon"),
                MarkupCase("empty", "`T`T", ""),
                MarkupCase("seconds past a Long", "`T99999999999999999999`T", "99999999999999999999"),
                // Inside a Long, but outside java.time's calendar, which ends near year 1e9.
                MarkupCase("seconds past the calendar", "`T40000000000000000`T", "40000000000000000"),
                MarkupCase("seconds past the calendar with a format", "`T40000000000000000|%F`T", "40000000000000000|%F"),
                MarkupCase("Long.MAX_VALUE", "`T9223372036854775807`T", "9223372036854775807"),
            )

        for (case in cases) {
            assertEquals(case.name, case.want, stripText(MicronParser.parse(case.markup)))
        }
    }

    @Test
    fun `a construct inherits the line style`() {
        val document = MicronParser.parse("`!posted `T1789178907|%s`T by Glenn`!")
        val line = document.lines.single()
        val texts = line.elements.filterIsInstance<MicronElement.Text>()
        assertEquals(listOf("posted ", "1789178907", " by Glenn"), texts.map { it.content })
        assertTrue(texts.all { it.style.bold })
    }

    /** The visible text of a parsed document, for comparing an expansion. */
    private fun stripText(document: MicronDocument): String =
        document.lines
            .flatMap { it.elements }
            .filterIsInstance<MicronElement.Text>()
            .joinToString("") { it.content }

    private data class FormatCase(
        val name: String,
        val seconds: Long,
        val format: String,
        val want: String,
    )

    private data class MarkupCase(
        val name: String,
        val markup: String,
        val want: String,
    )
}
