package com.github.opscalehub.dong.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.domain.calendar.Jalali
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleDataTest {
    /** The demo must load on any day of the year (first/last day of month, Nowruz, leap Esfand). */
    @Test
    fun `demo data loads for many dates`() = runBlocking {
        val days = listOf(Jalali.toEpochDay(1405, 7, 25), Jalali.toEpochDay(1405, 8, 5), Jalali.toEpochDay(1405, 1, 1),
            Jalali.toEpochDay(1404, 12, 29), Jalali.toEpochDay(1403, 12, 30), Jalali.toEpochDay(1405, 6, 31), Jalali.toEpochDay(1405, 7, 1))
        for (day in days) {
            val db = DongDatabase.build(ApplicationProvider.getApplicationContext(), null)
            try {
                val r = Repos(db, FixedClock(day))
                val b = SampleData.createBuilding(r)
                try {
                    SampleData.addDemoData(r, b)
                } catch (e: OpException) {
                    fail("day ${Jalali.fromEpochDay(day)}: ${e.code} ${e.detail}")
                }
                assertTrue(r.finance.balances(b).reconciles)
            } finally {
                db.close()
            }
        }
    }
}
