package com.github.opscalehub.dong.i18n

import com.github.opscalehub.dong.domain.calendar.Jalali
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test
    fun `money uses persian digits and grouping`() {
        assertEquals("۱۸٬۰۰۰٬۰۰۰", Fmt.money(18_000_000))
        assertEquals("\u2066−۷۰٬۰۰۰\u2069", Fmt.money(-70_000)) // LTR isolate keeps the minus on the left in RTL
        assertEquals("۰", Fmt.money(0))
        assertEquals("۱٬۲۳۴٫۵۶", Fmt.money(123_456, minorDigits = 2))
        assertEquals("18,000,000", Fmt.money(18_000_000, persian = false))
    }

    @Test
    fun `cubic meters drop trailing zeros so decimals cannot read as thousands`() {
        assertEquals("۲۶٫۵", Fmt.cubicMeters(26_500))
        assertEquals("۱۲", Fmt.cubicMeters(12_000))
        assertEquals("۰٫۰۰۱", Fmt.cubicMeters(1))
        assertEquals("۵٬۲۶۲", Fmt.cubicMeters(5_262_000))
        assertEquals("\u2066−۳۵٫۵\u2069", Fmt.cubicMeters(-35_500))
    }

    @Test
    fun `parsing accepts persian and latin input`() {
        assertEquals(26_500L, Fmt.parseCubicMeters("۲۶٫۵"))
        assertEquals(26_500L, Fmt.parseCubicMeters("26.5"))
        assertEquals(1_017_600L, Fmt.parseCubicMeters("1017.6"))
        assertNull(Fmt.parseCubicMeters("1.2.3"))
        assertNull(Fmt.parseCubicMeters(""))
        assertEquals(18_000_000L, Fmt.parseMoney("۱۸٬۰۰۰٬۰۰۰"))
        assertEquals(18_000_000L, Fmt.parseMoney("18,000,000"))
        assertEquals(-500_000L, Fmt.parseMoney("-500000"))
        assertEquals(12_345L, Fmt.parseMoney("123.45", 2))
        assertNull(Fmt.parseMoney("abc"))
        // round trip of what the UI displays
        listOf(0L, 1L, 999L, 1_000L, 26_500L, 9_998_500L).forEach { assertEquals(it, Fmt.parseCubicMeters(Fmt.cubicMeters(it))) }
        listOf(0L, 7L, 1_234_567L).forEach { assertEquals(it, Fmt.parseMoney(Fmt.money(it))) }
    }

    @Test
    fun `dates`() {
        val d = Jalali.toEpochDay(1405, 7, 25)
        assertEquals("۱۴۰۵/۰۷/۲۵", Fmt.date(d))
        assertEquals("۲۵ مهر ۱۴۰۵", Fmt.longDate(d))
        assertEquals(d, Fmt.parseDate("۱۴۰۵/۰۷/۲۵"))
        assertEquals("2026/10/17", Fmt.date(d, com.github.opscalehub.dong.domain.model.CalendarSystem.GREGORIAN, persian = false))
    }

    @Test
    fun `display currency toman over rial is exact`() {
        assertEquals("۱٬۸۰۰٬۰۰۰", Fmt.moneyIn(18_000_000, 0, 10))
        assertEquals("۱٫۵", Fmt.moneyIn(15, 0, 10))
        assertEquals("\u2066−۷٬۰۰۰\u2069", Fmt.moneyIn(-70_000, 0, 10))
        assertEquals("۰", Fmt.moneyIn(0, 0, 10))
        assertEquals("۱٬۲۳۴٫۵۶", Fmt.moneyIn(123_456, 2, 1)) // cents keep two digits
        assertEquals(18_000_000L, Fmt.parseMoneyIn("۱٬۸۰۰٬۰۰۰", 0, 10))
        assertEquals(15L, Fmt.parseMoneyIn("1.5", 0, 10))
        assertNull(Fmt.parseMoneyIn("1.55", 0, 10)) // finer than one Rial
        assertNull(Fmt.parseMoneyIn("-", 0, 10))
        listOf(0L, 1L, 15L, 999_999L, 18_000_000L, -70_000L).forEach { assertEquals(it, Fmt.parseMoneyIn(Fmt.moneyIn(it, 0, 10), 0, 10)) }
    }

    @Test
    fun `signed amounts keep the sign on the left in RTL`() {
        assertEquals("\u2066+۱۰۰٬۰۰۰\u2069", Fmt.signed(true, Fmt.money(100_000)))
        assertEquals("\u2066−۳۰۰٬۰۰۰\u2069", Fmt.signed(false, Fmt.money(300_000)))
        assertEquals(-300_000L, Fmt.parseMoney(Fmt.money(-300_000))) // isolates are ignored when parsing
    }
}
