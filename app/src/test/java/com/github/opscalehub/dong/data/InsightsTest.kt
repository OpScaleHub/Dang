package com.github.opscalehub.dong.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.i18n.Fmt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reporting math on the demo building (same scenario as the workbook). Expected
 * values are derived by hand from the scenario, not from the implementation.
 */
@RunWith(AndroidJUnit4::class)
class InsightsTest {
    private lateinit var db: DongDatabase
    private lateinit var r: Repos
    private lateinit var b: String
    private lateinit var d: ReportData
    private lateinit var mehr: com.github.opscalehub.dong.data.db.PeriodEntity
    private lateinit var aban: com.github.opscalehub.dong.data.db.PeriodEntity
    private val unit = HashMap<String, String>()

    @Before
    fun setup() = runBlocking {
        db = DongDatabase.build(ApplicationProvider.getApplicationContext(), null)
        r = Repos(db, FixedClock())
        b = SampleData.createBuilding(r)
        SampleData.addDemoData(r, b)
        d = ReportRepository(r).load(b)
        mehr = d.periods.first { it.status == PeriodStatus.FINALIZED }
        aban = d.periods.first { it.status == PeriodStatus.DRAFT }
        d.units.forEach { unit[Fmt.latin(it.label)] = it.id }
    }

    @After
    fun close() = db.close()

    @Test
    fun `collection settles oldest debts first`() {
        val c = Insights.collection(d, mehr.id)
        assertEquals(62_600_000L, c.billed)
        // 42.1M paid; 0.9M cleared 1-2's opening debt, 0.57M covered 3-1's refund and surplus,
        // 0.5M of 3-1's opening credit applied to its new charge → 41.13M toward this month.
        assertEquals(41_130_000L, c.collected)
        assertEquals(657, c.perMille)
        assertEquals(0L, Insights.collection(d, aban.id).billed)
    }

    @Test
    fun `aging buckets open charges by age and keeps credits apart`() {
        val today = r.clock.today()
        val now = Insights.aging(d, today)
        assertEquals(d.balances.outstandingDebts, now.sumOf { it.total })
        assertTrue(now.all { a -> a.buckets.filterKeys { it != Insights.AgeBucket.CURRENT }.values.all { it == 0L } })
        assertEquals(1_871_000L, now.first { it.unitId == unit["1-2"] }.total)
        assertEquals(70_000L, now.first { it.unitId == unit["3-1"] }.credit)
        val later = Insights.aging(d, mehr.endDate + 50)
        assertEquals(d.balances.outstandingDebts, later.sumOf { it.buckets.getValue(Insights.AgeBucket.DAYS_31_60) })
        assertEquals(unit["4-1"], later.first().unitId) // 4,678,000 owed: the largest closing balance
        assertEquals(4_678_000L, later.first().total)
    }

    @Test
    fun `cash flow waterfall per month`() {
        val m = Insights.cashFlow(d, mehr.startDate, mehr.endDate)
        assertEquals(60_000_000L, m.opening)
        assertEquals(-32_600_000L, m.flows.getValue(Insights.Flow.SUPPLIER_PAYMENTS))
        assertEquals(27_400_000L, m.closing)
        val a = Insights.cashFlow(d, aban.startDate, aban.endDate)
        assertEquals(27_400_000L, a.opening)
        assertEquals(42_100_000L, a.flows.getValue(Insights.Flow.COLLECTIONS))
        assertEquals(-500_000L, a.flows.getValue(Insights.Flow.REFUNDS))
        assertEquals(69_000_000L, a.closing)
        assertEquals(d.balances.cashTotal, a.closing)
    }

    @Test
    fun `reversed payments are neither collections nor charges`() = runBlocking {
        val pay = r.finance.receivePayment(b, unit.getValue("1-3"), 1_000_000, d.cashAccounts.first().id, aban.startDate)
        r.finance.reverse(pay, "اشتباه")
        val d2 = ReportRepository(r).load(b)
        assertEquals(41_130_000L, Insights.collection(d2, mehr.id).collected)
        assertEquals(42_100_000L, Insights.cashFlow(d2, aban.startDate, aban.endDate).flows.getValue(Insights.Flow.COLLECTIONS))
        assertEquals(d2.balances.outstandingDebts, Insights.aging(d2, r.clock.today()).sumOf { it.total })
    }

    @Test
    fun `downward correction lowers the bill instead of acting like a payment`() = runBlocking {
        // Unit 1-1's meter was over-read by 2 m³: the correction moves water cost from 1-1 to others.
        val meter = db.waterDao().meters(b).first { it.unitId == unit["1-1"] && it.retiredOn == null }
        val closing = db.waterDao().readings(b).first { it.meterId == meter.id && it.readingDate == mehr.endDate }
        r.water.correctReading(closing.id, closing.valueLiters - 2_000, "خطای قرائت")
        r.periods.applyCorrection(mehr.id, "خطای قرائت")
        val d2 = ReportRepository(r).load(b)
        val c = Insights.collection(d2, mehr.id)
        assertEquals(62_600_000L, c.billed) // the same bill, redistributed
        assertEquals(d2.balances.outstandingDebts, Insights.aging(d2, r.clock.today()).sumOf { it.total })
    }

    @Test
    fun `expenses by category with previous month`() {
        val e = Insights.expensesByCategory(d, mehr)
        assertEquals(56_600_000L, e.sumOf { it.amount })
        assertEquals("آب", d.categoryName(e.first().categoryId))
        assertEquals(18_000_000L, e.first().amount)
        val next = Insights.expensesByCategory(d, aban)
        val cleaning = next.first { d.categoryName(it.categoryId) == "نظافت" }
        assertEquals(9_000_000L, cleaning.amount)
        assertEquals(9_000_000L, cleaning.previous)
    }

    @Test
    fun `monthly series reserve and water`() {
        val m = Insights.monthly(d)
        assertEquals(listOf(mehr.id, aban.id), m.map { it.periodId })
        assertEquals(46_000_000L, m[0].reserve) // 40M opening + 9M contributions − 3M emergency repair
        assertEquals(51_000_000L, m[1].reserve) // + 5M transfer
        assertEquals(56_600_000L, m[0].expenses)
        val w = Insights.water(d, mehr.id)!!
        assertEquals(142_000L, w.mainLiters)
        assertEquals(26_500L, w.unexplainedLiters)
        assertEquals(186, w.unexplainedPerMille)
        assertEquals(115_500L / 9, w.averageLiters)
    }

    @Test
    fun `unit composition and balance history`() {
        val u = unit.getValue("1-1")
        val comp = Insights.unitComposition(d, u, mehr.id)
        assertEquals(4_902_000L, comp.sumOf { it.second })
        assertEquals("آب", d.categoryName(comp.first().first))
        assertEquals(1_959_000L, comp.first().second) // 1,585,000 metered + 374,000 common
        assertEquals(listOf(4_902_000L, 1_902_000L), Insights.unitBalanceHistory(d, u).map { it.second })
    }

    @Test
    fun `batch readings are atomic`() = runBlocking {
        val meters = db.waterDao().meters(b).filter { it.retiredOn == null }
        val before = db.waterDao().readings(b).size
        try {
            r.water.addReadings(aban.endDate, listOf(WaterRepository.ReadingInput(meters[0].id, 9_000_000), WaterRepository.ReadingInput(meters[1].id, -1)))
            org.junit.Assert.fail("must reject")
        } catch (e: OpException) {
            assertEquals(OpError.INVALID_AMOUNT, e.code)
        }
        assertEquals(before, db.waterDao().readings(b).size)
        r.water.addReadings(aban.endDate, meters.map { WaterRepository.ReadingInput(it.id, 99_999_999) })
        assertEquals(before + meters.size, db.waterDao().readings(b).size)
    }

    @Test
    fun `allocation preview equals the finalized result`() = runBlocking {
        val pol = AllocationPolicy.single(Basis.Area)
        val preview = r.expenses.previewAllocation(b, aban.id, 12_000_000, pol)
        val cat = db.expenseDao().categories(b).first { it.name == "تعمیر و نگهداری ساختمان" }
        val e = com.github.opscalehub.dong.data.db.ExpenseEntity(newId(), b, aban.id, cat.id, cat.defaultFundId!!, null, aban.startDate, "x", 12_000_000, policyJson = pol.encode(), createdAt = 0)
        r.expenses.create(e)
        val res = r.periods.preview(aban.id).sources.first { it.sourceId == e.id }.allocation
        assertEquals(res.shares, preview.shares)
        assertEquals(751_000L, preview.shares[unit["1-1"]])
    }
}
