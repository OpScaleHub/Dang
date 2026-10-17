package com.github.opscalehub.dong.domain.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class JalaliTest {

    @Test
    fun `known nowruz dates`() {
        assertEquals(LocalDate.of(2024, 3, 20), Jalali.toGregorian(1403, 1, 1))
        assertEquals(LocalDate.of(2025, 3, 21), Jalali.toGregorian(1404, 1, 1))
        assertEquals(LocalDate.of(2026, 3, 21), Jalali.toGregorian(1405, 1, 1))
        assertEquals(LocalDate.of(2021, 3, 21), Jalali.toGregorian(1400, 1, 1))
    }

    @Test
    fun `leap years`() {
        assertTrue(Jalali.isLeapYear(1399))
        assertTrue(Jalali.isLeapYear(1403))
        assertFalse(Jalali.isLeapYear(1404))
        assertEquals(30, Jalali.monthLength(1403, 12))
        assertEquals(29, Jalali.monthLength(1404, 12))
        assertEquals(LocalDate.of(2025, 3, 20), Jalali.toGregorian(1403, 12, 30))
    }

    @Test
    fun `round trip across 1300 to 1500`() {
        var d = Jalali.toGregorian(1300, 1, 1)
        val last = Jalali.toGregorian(1500, 12, 29)
        while (!d.isAfter(last)) {
            val j = Jalali.fromGregorian(d)
            assertEquals(d, Jalali.toGregorian(j))
            d = d.plusDays(1)
        }
    }

    @Test
    fun `parse persian and latin digits`() {
        assertEquals(JalaliDate(1405, 7, 30), Jalali.parse("۱۴۰۵/۰۷/۳۰"))
        assertEquals(JalaliDate(1405, 7, 1), Jalali.parse("1405-7-1"))
        assertNull(Jalali.parse("1405/12/31"))
        assertNull(Jalali.parse("abc"))
    }
}
