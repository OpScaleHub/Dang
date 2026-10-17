package com.github.opscalehub.dong.domain.calendar

import java.time.LocalDate

/** A Solar Hijri (Jalali) date. Months 1..12, days 1..31. */
data class JalaliDate(val year: Int, val month: Int, val day: Int) {
    override fun toString(): String = "%04d/%02d/%02d".format(year, month, day)
}

/**
 * Jalali ⇄ Gregorian conversion using the arithmetic of the widely used
 * `jalaali` algorithm (Borkowski's break table), valid for Jalali years
 * -61..3177. Storage is always ISO epoch days; Jalali is display only.
 */
object Jalali {
    private val breaks = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    private data class Cal(val leap: Int, val gy: Int, val march: Int)

    private fun div(a: Int, b: Int) = a / b
    private fun mod(a: Int, b: Int) = a - (a / b) * b

    private fun jalCal(jy: Int): Cal {
        val gy = jy + 621
        var leapJ = -14
        var jp = breaks[0]
        require(jy >= jp && jy < breaks.last()) { "Jalali year out of range: $jy" }
        var jump = 0
        for (i in 1 until breaks.size) {
            val jm = breaks[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += div(jump, 33) * 8 + div(mod(jump, 33), 4)
            jp = jm
        }
        var n = jy - jp
        leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1
        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
        val march = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4
        return Cal(leap, gy, march)
    }

    fun isLeapYear(jy: Int): Boolean = jalCal(jy).leap == 0

    fun monthLength(jy: Int, jm: Int): Int = when {
        jm <= 6 -> 31
        jm <= 11 -> 30
        isLeapYear(jy) -> 30
        else -> 29
    }

    fun isValid(jy: Int, jm: Int, jd: Int): Boolean =
        jy in -61..3177 && jm in 1..12 && jd >= 1 && jd <= monthLength(jy, jm)

    fun toGregorian(jy: Int, jm: Int, jd: Int): LocalDate {
        require(isValid(jy, jm, jd)) { "invalid Jalali date $jy/$jm/$jd" }
        val r = jalCal(jy)
        val nowruz = LocalDate.of(r.gy, 3, r.march)
        val dayOfYear = if (jm <= 7) (jm - 1) * 31 + jd - 1 else 186 + (jm - 7) * 30 + jd - 1
        return nowruz.plusDays(dayOfYear.toLong())
    }

    fun toGregorian(d: JalaliDate): LocalDate = toGregorian(d.year, d.month, d.day)

    fun fromGregorian(date: LocalDate): JalaliDate {
        var jy = date.year - 621
        var r = jalCal(jy)
        var nowruz = LocalDate.of(r.gy, 3, r.march)
        if (date.isBefore(nowruz)) {
            jy -= 1
            r = jalCal(jy)
            nowruz = LocalDate.of(r.gy, 3, r.march)
        }
        val k = (date.toEpochDay() - nowruz.toEpochDay()).toInt()
        return if (k < 186) {
            JalaliDate(jy, 1 + k / 31, 1 + k % 31)
        } else {
            val k2 = k - 186
            JalaliDate(jy, 7 + k2 / 30, 1 + k2 % 30)
        }
    }

    fun fromEpochDay(epochDay: Long): JalaliDate = fromGregorian(LocalDate.ofEpochDay(epochDay))
    fun toEpochDay(jy: Int, jm: Int, jd: Int): Long = toGregorian(jy, jm, jd).toEpochDay()

    val monthNames = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    /** Parses "1405/07/30" (Persian or Latin digits, '/' or '-' separators). */
    fun parse(text: String): JalaliDate? {
        val norm = text.trim().map { c ->
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                '-' -> '/'
                else -> c
            }
        }.joinToString("")
        val parts = norm.split('/')
        if (parts.size != 3) return null
        val (y, m, d) = parts.map { it.toIntOrNull() ?: return null }
        return if (isValid(y, m, d)) JalaliDate(y, m, d) else null
    }
}
