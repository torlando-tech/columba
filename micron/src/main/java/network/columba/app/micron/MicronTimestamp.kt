package network.columba.app.micron

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.abs

/**
 * Renders the `` `T `` timestamp construct of Micron markup:
 *
 * ```
 * `T<unix-seconds>`T
 * `T<unix-seconds>|<strftime-format>`T
 * ```
 *
 * The page supplies unix seconds, which carry no timezone, and the client renders that
 * instant in the reader's own zone. [format] is a strftime format — every conversion C
 * strftime defines, plus the glibc and BSD extensions — and an empty one selects
 * [DEFAULT_FORMAT]. The conversions strftime resolves through the locale are pinned to
 * their C locale forms, so a page renders the same text on every device.
 *
 * The engine is hand-written because [DateTimeFormatter] has no padding flags and rejects
 * an unknown conversion instead of passing it through.
 */
object MicronTimestamp {
    /** Used when a construct names no format: `Fri Sep 11, 2026 9:08:27PM EST`. */
    const val DEFAULT_FORMAT = "%a %b %d, %Y %-I:%M:%S%p %Z"

    /** Opens and closes a timestamp construct. */
    internal const val MARKER = "`T"

    /** Padding flags that may follow a `%`: no padding, space padding, zero padding. */
    private const val PAD_FLAGS = "-_0"

    /** Modifiers that may precede a conversion; POSIX makes both no-ops in the C locale. */
    private const val MODIFIERS = "EO"

    /**
     * Renders unix [seconds] in [zone], the device's own zone by default. Throws
     * [java.time.DateTimeException] for an instant outside `java.time`'s calendar.
     */
    fun formatUnix(
        seconds: Long,
        format: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        formatTime(
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), zone),
            format.ifEmpty { DEFAULT_FORMAT },
        )

    /** Expands every conversion in [format] against [time]. */
    private fun formatTime(
        time: ZonedDateTime,
        format: String,
    ): String {
        val out = StringBuilder()
        var i = 0

        while (i < format.length) {
            if (format[i] != '%') {
                out.append(format[i])
                i++
            } else {
                val expansion = expansionAt(time, format, i)
                out.append(expansion.text)
                i = expansion.nextIndex
            }
        }

        return out.toString()
    }

    /** The text of one conversion and the index just past it. */
    private data class Expansion(
        val text: String,
        val nextIndex: Int,
    )

    /**
     * Expands the conversion at the `%` at [percent], including any padding flag and E/O
     * modifier. An unrecognised conversion is copied through verbatim: POSIX leaves it
     * undefined, and that keeps a typo in a page's format visible.
     */
    private fun expansionAt(
        time: ZonedDateTime,
        format: String,
        percent: Int,
    ): Expansion {
        var cursor = percent + 1
        val flag = if (cursor < format.length && format[cursor] in PAD_FLAGS) format[cursor] else null
        if (flag != null) cursor++
        if (cursor + 1 < format.length && format[cursor] in MODIFIERS) cursor++
        if (cursor >= format.length) return Expansion("%", format.length)

        val expansion = expand(time, format[cursor], flag)
        return Expansion(expansion ?: format.substring(percent, cursor + 1), cursor + 1)
    }

    /** A dispatch table over the conversions, so it is long rather than deep. */
    @Suppress("CyclomaticComplexMethod")
    private fun expand(
        time: ZonedDateTime,
        conversion: Char,
        flag: Char?,
    ): String? =
        when (conversion) {
            '%' -> "%"
            '+' -> formatTime(time, "%a %b %e %H:%M:%S %Z %Y") // the date(1) default format
            'a', 'A', 'b', 'h', 'B' -> name(time, conversion)
            'c', 'D', 'F', 'r', 'R', 'T', 'v', 'x', 'X' -> composite(time, conversion)
            'd', 'e', 'H', 'I', 'j', 'k', 'l', 'm', 'M', 'S', 'y', 'C', 'V' -> number(time, conversion, flag)
            'g' -> pad(isoWeekYear(time) % 100, 2, flag, spaceFill = false)
            'G' -> isoWeekYear(time).toString()
            'n' -> "\n"
            'p' -> if (time.hour < 12) "AM" else "PM"
            'P' -> if (time.hour < 12) "am" else "pm"
            's' -> time.toEpochSecond().toString()
            't' -> "\t"
            'u' -> time.dayOfWeek.value.toString()
            'U' -> pad(weekOfYear(time, sundayFirst = true), 2, flag, spaceFill = false)
            'w' -> (time.dayOfWeek.value % 7).toString()
            'W' -> pad(weekOfYear(time, sundayFirst = false), 2, flag, spaceFill = false)
            'Y' -> time.year.toString()
            'z' -> zoneOffset(time)
            'Z' -> zoneName(time)
            else -> null
        }

    private fun name(
        time: ZonedDateTime,
        conversion: Char,
    ): String =
        when (conversion) {
            'a' -> time.dayOfWeek.getDisplayName(TextStyle.SHORT, ENGLISH)
            'A' -> time.dayOfWeek.getDisplayName(TextStyle.FULL, ENGLISH)
            'b', 'h' -> time.month.getDisplayName(TextStyle.SHORT, ENGLISH)
            'B' -> time.month.getDisplayName(TextStyle.FULL, ENGLISH)
            else -> ""
        }

    private fun number(
        time: ZonedDateTime,
        conversion: Char,
        flag: Char?,
    ): String =
        when (conversion) {
            'C' -> pad(time.year / 100, 2, flag, spaceFill = false)
            'd' -> pad(time.dayOfMonth, 2, flag, spaceFill = false)
            'e' -> pad(time.dayOfMonth, 2, flag, spaceFill = true)
            'H' -> pad(time.hour, 2, flag, spaceFill = false)
            'I' -> pad(hour12(time.hour), 2, flag, spaceFill = false)
            'j' -> pad(time.dayOfYear, 3, flag, spaceFill = false)
            'k' -> pad(time.hour, 2, flag, spaceFill = true)
            'l' -> pad(hour12(time.hour), 2, flag, spaceFill = true)
            'm' -> pad(time.monthValue, 2, flag, spaceFill = false)
            'M' -> pad(time.minute, 2, flag, spaceFill = false)
            'S' -> pad(time.second, 2, flag, spaceFill = false)
            'V' -> pad(time.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), 2, flag, spaceFill = false)
            'y' -> pad(time.year % 100, 2, flag, spaceFill = false)
            else -> ""
        }

    /** Conversions that are shorthand for a format of their own, in the C locale. */
    private fun composite(
        time: ZonedDateTime,
        conversion: Char,
    ): String =
        when (conversion) {
            'c' -> formatTime(time, "%a %b %e %H:%M:%S %Y")
            'D', 'x' -> formatTime(time, "%m/%d/%y")
            'F' -> formatTime(time, "%Y-%m-%d")
            'r' -> formatTime(time, "%I:%M:%S %p")
            'R' -> formatTime(time, "%H:%M")
            'T', 'X' -> formatTime(time, "%H:%M:%S")
            'v' -> formatTime(time, "%e-%b-%Y")
            else -> ""
        }

    /** Reports an hour on the 12-hour clock, where midnight and noon are both 12. */
    private fun hour12(hour: Int): Int {
        val wrapped = hour % 12
        return if (wrapped == 0) 12 else wrapped
    }

    /** The ISO 8601 week-based year, which can differ from the calendar year. */
    private fun isoWeekYear(time: ZonedDateTime): Int = time.get(IsoFields.WEEK_BASED_YEAR)

    /**
     * The week of the year that `%U` and `%W` count. Days before the year's first Sunday or
     * Monday are week zero.
     */
    private fun weekOfYear(
        time: ZonedDateTime,
        sundayFirst: Boolean,
    ): Int {
        val weekday = if (sundayFirst) time.dayOfWeek.value % 7 else time.dayOfWeek.value - 1
        return (time.dayOfYear + 7 - weekday) / 7
    }

    /**
     * Renders [value] at [width]. [flag] is the padding flag that preceded the conversion:
     * `-` leaves it bare, `_` pads with spaces, `0` pads with zeros. With no flag,
     * [spaceFill] selects the space padding that `%e`, `%k` and `%l` have by default.
     */
    private fun pad(
        value: Int,
        width: Int,
        flag: Char?,
        spaceFill: Boolean,
    ): String =
        when {
            flag == '-' -> value.toString()
            flag == '_' -> value.toString().padStart(width, ' ')
            flag == '0' -> value.toString().padStart(width, '0')
            spaceFill -> value.toString().padStart(width, ' ')
            else -> value.toString().padStart(width, '0')
        }

    /** `%z`: the numeric offset from UTC, `-0500`. An offset with seconds is truncated, as strftime is. */
    private fun zoneOffset(time: ZonedDateTime): String {
        val total = time.offset.totalSeconds
        val magnitude = abs(total)

        return buildString {
            append(if (total < 0) '-' else '+')
            append((magnitude / 3600).toString().padStart(2, '0'))
            append(((magnitude % 3600) / 60).toString().padStart(2, '0'))
        }
    }

    /**
     * `%Z`, read from the `z` pattern rather than [ZoneId.getDisplayName]: the latter
     * returns CLDR metazone titles (`ET`) where strftime returns the abbreviation
     * (`EDT`). java.time spells UTC's name `Z`, so that one is normalized.
     */
    private fun zoneName(time: ZonedDateTime): String {
        val name = ZONE_TEXT.format(time)
        return if (name == "Z") "UTC" else name
    }

    /** Names are pinned to English so a page renders the same on every device. */
    private val ENGLISH = Locale.ENGLISH

    private val ZONE_TEXT = DateTimeFormatter.ofPattern("z", ENGLISH)
}
