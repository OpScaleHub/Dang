package com.github.opscalehub.dong.i18n

import com.github.opscalehub.dong.domain.calendar.Jalali
import com.github.opscalehub.dong.domain.model.CalendarSystem
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

/** Persian number/date presentation. Pure Kotlin so exports and UI format identically. */
object Fmt {
    private const val FA_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

    /**
     * In right-to-left text a leading minus is a neutral and drifts to the right edge
     * («۳٬۲۶۰-»). Negative numbers are wrapped in a left-to-right isolate (LRI…PDI) so the
     * minus stays attached on the left, as Persian banking apps show it.
     */
    private fun isolateNegative(s: String): String = if (s.startsWith("−")) "\u2066$s\u2069" else s

    /** A formatted amount with an explicit sign («+۱۰۰٬۰۰۰» / «−۳۰۰٬۰۰۰»), kept left-attached in RTL. */
    fun signed(positive: Boolean, formattedMagnitude: String): String =
        "\u2066" + (if (positive) "+" else "−") + formattedMagnitude.removePrefix("\u2066").removeSuffix("\u2069") + "\u2069"

    fun digits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '0'..'9') FA_DIGITS[c - '0'] else c)
    }

    /** Persian/Arabic-Indic digits and separators → Latin, for parsing user input. */
    fun latin(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            in '۰'..'۹' -> append('0' + (c - '۰'))
            in '٠'..'٩' -> append('0' + (c - '٠'))
            '٫' -> append('.')
            '٬', ',', '،', '\u2066', '\u2067', '\u2068', '\u2069' -> Unit
            else -> append(c)
        }
    }

    fun grouped(value: Long): String {
        val neg = value < 0
        val s = (if (neg) -value else value).toString().reversed().chunked(3).joinToString("٬").reversed()
        return if (neg) "−$s" else s
    }

    /** Money in minor units → "۱٬۲۳۴٬۰۰۰"; with [minorDigits] > 0 adds a decimal part. */
    fun money(minor: Long, minorDigits: Int = 0, persian: Boolean = true): String {
        val text = if (minorDigits <= 0) {
            grouped(minor)
        } else {
            var p = 1L
            repeat(minorDigits) { p *= 10 }
            val neg = minor < 0
            val a = if (neg) -minor else minor
            (if (neg) "−" else "") + grouped(a / p).removePrefix("−") + "٫" + (a % p).toString().padStart(minorDigits, '0')
        }
        return if (persian) isolateNegative(digits(text)) else text.replace('٬', ',').replace('٫', '.').replace('−', '-')
    }

    fun number(value: Long): String = isolateNegative(digits(grouped(value)))

    private fun pow10(n: Int): Long {
        var p = 1L
        repeat(n) { p *= 10 }
        return p
    }

    private fun log10(divisor: Long): Int {
        var d = divisor
        var n = 0
        while (d > 1) {
            require(d % 10 == 0L) { "display divisor must be a power of 10" }
            d /= 10
            n++
        }
        return n
    }

    /**
     * Stored minor units → display unit. [divisor] = minor units per display unit
     * beyond the currency's own minor digits (Rial → Toman: 10). Fraction digits
     * caused by the divisor are shown only when non-zero («۱٫۵»), so typical
     * amounts read as whole Tomans.
     */
    fun moneyIn(minor: Long, minorDigits: Int, divisor: Long, persian: Boolean = true): String {
        val extra = log10(divisor)
        val scale = pow10(minorDigits + extra)
        val neg = minor < 0
        val a = if (neg) -minor else minor
        var frac = (a % scale).toString().padStart(minorDigits + extra, '0')
        if (frac.isNotEmpty()) {
            val trimmed = frac.trimEnd('0')
            frac = if (trimmed.length < minorDigits) frac.take(minorDigits) else trimmed
        }
        val s = (if (neg) "−" else "") + grouped(a / scale) + if (frac.isEmpty()) "" else "٫$frac"
        return if (persian) isolateNegative(digits(s)) else s.replace('٬', ',').replace('٫', '.').replace('−', '-')
    }

    /** Display-unit text → stored minor units; null if invalid or more precise than storable. */
    fun parseMoneyIn(text: String, minorDigits: Int, divisor: Long): Long? {
        val t = latin(text).trim().replace("−", "-")
        if (t.isEmpty() || t == "-") return null
        val neg = t.startsWith("-")
        val body = t.removePrefix("-")
        val parts = body.split('.')
        if (parts.size > 2 || parts.any { p -> p.any { !it.isDigit() } }) return null
        val decimals = minorDigits + log10(divisor)
        val fracText = if (parts.size == 2) parts[1] else ""
        if (fracText.length > decimals) return null
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val frac = fracText.padEnd(decimals, '0').ifEmpty { "0" }.toLong()
        val v = whole * pow10(decimals) + frac
        return if (neg) -v else v
    }

    /** Litres → m³ with 3 decimals ("۱۲٫۵۰۰"). */
    fun cubicMeters(liters: Long, persian: Boolean = true): String {
        val neg = liters < 0
        val a = if (neg) -liters else liters
        // Trailing zeros are dropped ("۲۶٫۵", not "۲۶٫۵۰۰"): in many Persian fonts the decimal mark ٫
        // looks like the thousands separator ٬, so three decimals would read as thousands.
        val frac = (a % 1000).toString().padStart(3, '0').trimEnd('0')
        val s = (if (neg) "−" else "") + grouped(a / 1000) + if (frac.isEmpty()) "" else "٫$frac"
        return if (persian) isolateNegative(digits(s)) else s.replace('٬', ',').replace('٫', '.').replace('−', '-')
    }

    /** "12.5" / "۱۲٫۵" m³ → litres; null if invalid. */
    fun parseCubicMeters(text: String): Long? {
        val t = latin(text).trim()
        if (t.isEmpty()) return null
        val parts = t.split('.')
        if (parts.size > 2) return null
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val frac = if (parts.size == 2) parts[1].take(3).padEnd(3, '0') else "000"
        if (frac.any { !it.isDigit() } || whole < 0) return null
        return whole * 1000 + frac.toLong()
    }

    /** Money text → minor units. Accepts grouping separators and Persian digits. */
    fun parseMoney(text: String, minorDigits: Int = 0): Long? {
        val t = latin(text).trim().replace("−", "-")
        if (t.isEmpty()) return null
        if (minorDigits == 0) return t.toLongOrNull()
        val parts = t.split('.')
        if (parts.size > 2) return null
        val whole = parts[0].toLongOrNull() ?: return null
        val frac = (if (parts.size == 2) parts[1] else "").take(minorDigits).padEnd(minorDigits, '0').toLongOrNull() ?: return null
        var p = 1L
        repeat(minorDigits) { p *= 10 }
        return whole * p + if (whole < 0) -frac else frac
    }

    fun parseLong(text: String): Long? = latin(text).trim().toLongOrNull()

    fun date(epochDay: Long, calendar: CalendarSystem = CalendarSystem.JALALI, persian: Boolean = true): String {
        val s = when (calendar) {
            CalendarSystem.JALALI -> Jalali.fromEpochDay(epochDay).toString()
            CalendarSystem.GREGORIAN -> LocalDate.ofEpochDay(epochDay).toString().replace('-', '/')
        }
        return if (persian) digits(s) else s
    }

    fun longDate(epochDay: Long, calendar: CalendarSystem = CalendarSystem.JALALI): String = when (calendar) {
        CalendarSystem.JALALI -> Jalali.fromEpochDay(epochDay).let { digits("${it.day} ${Jalali.monthNames[it.month - 1]} ${it.year}") }
        CalendarSystem.GREGORIAN -> digits(LocalDate.ofEpochDay(epochDay).toString())
    }

    fun parseDate(text: String, calendar: CalendarSystem = CalendarSystem.JALALI): Long? = when (calendar) {
        CalendarSystem.JALALI -> Jalali.parse(text)?.let { Jalali.toEpochDay(it.year, it.month, it.day) }
        CalendarSystem.GREGORIAN -> runCatching { LocalDate.parse(latin(text).trim().replace('/', '-')).toEpochDay() }.getOrNull()
    }

    fun dateTime(millis: Long, calendar: CalendarSystem = CalendarSystem.JALALI): String {
        val ldt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        return date(ldt.toLocalDate().toEpochDay(), calendar) + " " + digits("%02d:%02d".format(ldt.hour, ldt.minute))
    }

    fun percentBp(bp: Int): String = digits(if (bp % 100 == 0) "${bp / 100}" else "%.2f".format(bp / 100.0)) + "٪"
}
