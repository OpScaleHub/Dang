package com.github.opscalehub.dong.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.opscalehub.dong.data.db.BillShareEntity
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.domain.calendar.Jalali
import com.github.opscalehub.dong.i18n.MoneyFormat
import com.github.opscalehub.dong.share.Block
import com.github.opscalehub.dong.share.SummaryShare
import com.github.opscalehub.dong.ui.screens.sendProgress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillsTest {
    private val db = DongDatabase.build(ApplicationProvider.getApplicationContext(), null)
    private val r = Repos(db, FixedClock(Jalali.toEpochDay(1405, 7, 26)))

    @After fun close() = db.close()

    private suspend fun demo(): Triple<String, String, String> {
        val b = SampleData.createBuilding(r)
        SampleData.addDemoData(r, b)
        val periods = db.periodDao().periods(b)
        return Triple(b, periods.first { it.status == PeriodStatus.FINALIZED }.id, periods.first { it.status == PeriodStatus.DRAFT }.id)
    }

    private suspend fun rejects(code: OpError, block: suspend () -> Unit) {
        try { block(); fail("expected $code") } catch (e: OpException) { assertEquals(code, e.code) }
    }

    @Test fun `marking bills sent counts toward the month and can be undone`() = runBlocking {
        val (b, closed, _) = demo()
        val d = ReportRepository(r).load(b)
        val billed = billedUnits(d, closed)
        assertEquals(9, billed.size)
        val u = billed.first()
        r.bills.markSent(closed, u, "org.telegram.messenger")
        r.bills.markSent(closed, u, BillShareEntity.MANUAL) // sending twice is still one unit
        assertEquals(1 to 9, sendProgress(billed, db.periodDao().shares(b), closed))
        billed.drop(1).forEach { r.bills.markSent(closed, it, "share") }
        assertEquals(9 to 9, sendProgress(billed, db.periodDao().shares(b), closed))
        r.bills.unmarkSent(closed, u)
        assertEquals(8 to 9, sendProgress(billed, db.periodDao().shares(b), closed))
    }

    @Test fun `only closed months of the same building can be marked`() = runBlocking {
        val (b, closed, draft) = demo()
        val unit = db.buildingDao().units(b).first().id
        rejects(OpError.PERIOD_NOT_FINALIZED) { r.bills.markSent(draft, unit, "share") }
        val other = r.buildings.create("دیگر")
        val stranger = com.github.opscalehub.dong.data.db.UnitEntity(newId(), other, 1, "x", 1000, sortOrder = 1)
        r.buildings.saveUnit(stranger)
        rejects(OpError.INVALID_INPUT) { r.bills.markSent(closed, stranger.id, "share") }
        rejects(OpError.NOT_FOUND) { r.bills.markSent("nope", unit, "share") }
    }

    @Test fun `bill matches the unit statement and the month's charges`() = runBlocking {
        val (b, closed, _) = demo()
        val d = ReportRepository(r).load(b)
        var adjusted = 0
        for (u in d.units) {
            val bill = r.bills.bill(b, u.id, closed)
            val charges = d.periodResults.getValue(closed).lines.filter { it.unitId == u.id }.sumOf { it.amount }
            assertEquals(charges, bill.lines.sumOf { it.second })
            assertEquals(r.finance.unitStatement(b, u.id).closing, bill.due)
            // The image must add up: previous + month + later + adjust − paid = due.
            assertEquals(bill.due, bill.previous + charges + bill.later + bill.adjust - bill.paid)
            if (bill.adjust != 0L) adjusted++
        }
        assertTrue("the demo has a credit note, so one bill shows an adjustment", adjusted > 0)
    }

    @Test fun `month summary totals agree with insights and hide debts unless asked`() = runBlocking {
        val (b, closed, _) = demo()
        val s = r.bills.monthSummary(b, closed)
        val d = ReportRepository(r).load(b)
        val c = Insights.collection(d, closed)
        assertEquals(c.billed, s.billed)
        assertEquals(c.collected, s.collected)
        assertEquals(s.billed, s.units.sumOf { it.second })
        assertEquals(d.balances.cashTotal, s.cash)
        val mf = MoneyFormat(0, 10, "تومان")
        val quiet = SummaryShare.blocks(s, mf, showUnits = true, showDebts = false)
        assertFalse(quiet.any { it is Block.Row && it.value.contains("بدهی") })
        val loud = SummaryShare.blocks(s, mf, showUnits = true, showDebts = true)
        assertTrue(loud.any { it is Block.Row && it.value.contains("بدهی") })
        assertFalse(SummaryShare.blocks(s, mf, showUnits = false, showDebts = true).any { it is Block.Row && it.label.startsWith("واحد") })
    }

    @Test fun `bill shares survive backup and restore`() = runBlocking {
        val (b, closed, _) = demo()
        val unit = billedUnits(ReportRepository(r).load(b), closed).first()
        r.bills.markSent(closed, unit, "share")
        val backup = BackupRepository(r, "test")
        val text = backup.export()
        val db2 = DongDatabase.build(ApplicationProvider.getApplicationContext(), null)
        try {
            val r2 = Repos(db2, FixedClock())
            val b2 = BackupRepository(r2, "test")
            val preview = b2.preview(text)
            assertTrue(preview.problems.isEmpty())
            b2.apply(preview, preview.buildings.associate { it.buildingId to RestoreMode.ADD_MISSING })
            assertEquals(1, db2.periodDao().shares(b).size)
            // Re-importing the same file adds nothing.
            val again = b2.preview(text)
            assertEquals(0, b2.apply(again, again.buildings.associate { it.buildingId to RestoreMode.ADD_MISSING }).inserted)
        } finally {
            db2.close()
        }
    }
}
