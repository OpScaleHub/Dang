package com.github.opscalehub.dong.domain.water

import com.github.opscalehub.dong.domain.Fixtures
import com.github.opscalehub.dong.domain.allocation.AllocationEngine
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.domain.allocation.CommonWater
import com.github.opscalehub.dong.domain.allocation.SimpleBasis
import com.github.opscalehub.dong.domain.model.Category
import com.github.opscalehub.dong.domain.model.ChargeItem
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.domain.model.Expense
import com.github.opscalehub.dong.domain.model.Meter
import com.github.opscalehub.dong.domain.model.MeterReading
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.domain.model.ReadingKind
import com.github.opscalehub.dong.domain.model.TariffTier
import com.github.opscalehub.dong.domain.model.WaterTariff
import com.github.opscalehub.dong.domain.period.IssueCode
import com.github.opscalehub.dong.domain.period.PeriodCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterTest {
    private val s = Fixtures.start
    private val e = Fixtures.end
    private val m = Meter("m", "m", MeterRole.UNIT, "1-1", rolloverLimitLiters = 100_000_000)
    private fun r(id: String, date: Long, v: Long, kind: ReadingKind = ReadingKind.REGULAR, sup: String? = null, at: Long = 0) =
        MeterReading(id, "m", date, v, kind, sup, at)
    private fun calc(vararg rs: MeterReading) = WaterCalculator.meterConsumption(m, rs.toList(), s, e)

    @Test
    fun `normal readings`() {
        val (c, issues) = calc(r("a", s - 1, 1_000), r("b", e, 13_500))
        assertEquals(12_500L, c.liters)
        assertTrue(issues.isEmpty())
        assertEquals("a", c.openingReadingId)
        assertEquals("b", c.closingReadingId)
    }

    @Test
    fun `zero consumption is valid`() {
        val (c, issues) = calc(r("a", s - 1, 5_000), r("b", e, 5_000))
        assertEquals(0L, c.liters)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `negative difference is flagged not accepted`() {
        val (c, issues) = calc(r("a", s - 1, 5_000), r("b", e, 4_000))
        assertNull(c.liters)
        assertEquals(IssueCode.NEGATIVE_CONSUMPTION, issues.single().code)
    }

    @Test
    fun `explicit rollover is accepted`() {
        val (c, _) = calc(r("a", s - 1, 99_998_500), r("b", e, 3_200, ReadingKind.ROLLOVER))
        assertEquals(4_700L, c.liters)
        val noLimit = WaterCalculator.meterConsumption(m.copy(rolloverLimitLiters = null), listOf(r("a", s - 1, 9_000), r("b", e, 10, ReadingKind.ROLLOVER)), s, e)
        assertEquals(IssueCode.ROLLOVER_WITHOUT_LIMIT, noLimit.second.single().code)
    }

    @Test
    fun `missing readings`() {
        assertEquals(IssueCode.MISSING_READING, calc().second.single().code)
        assertEquals(IssueCode.MISSING_CLOSING_READING, calc(r("a", s - 1, 1)).second.single().code)
        assertEquals(IssueCode.MISSING_OPENING_READING, calc(r("b", e, 10)).second.single().code)
    }

    @Test
    fun `meter reset via initial reading starts new baseline`() {
        val (c, issues) = calc(r("a", s - 1, 50_000), r("b", s + 10, 52_000), r("c", s + 10, 0, ReadingKind.INITIAL, at = 1), r("d", e, 3_000))
        assertEquals(5_000L, c.liters) // 2,000 before reset + 3,000 after
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `new meter starting inside the period`() {
        val (c, _) = calc(r("a", s + 5, 0, ReadingKind.INITIAL), r("b", e, 7_000))
        assertEquals(7_000L, c.liters)
    }

    @Test
    fun `correction supersedes original reading`() {
        val (c, _) = calc(r("a", s - 1, 1_000), r("b", e, 900), r("b2", e, 1_900, sup = "b", at = 5))
        assertEquals(900L, c.liters)
    }

    @Test
    fun `retired meter is inactive and readings after final are errors`() {
        val (c, issues) = calc(r("a", s - 30, 1_000), r("b", s - 1, 2_000, ReadingKind.FINAL))
        assertTrue(c.inactive)
        assertTrue(issues.isEmpty())
        val (_, bad) = calc(r("a", s - 1, 1_000), r("b", s + 1, 2_000, ReadingKind.FINAL), r("c", e, 3_000))
        assertEquals(IssueCode.READING_AFTER_FINAL, bad.single().code)
    }

    @Test
    fun `meter replacement sums old and new meter for the unit`() {
        val w = Fixtures.water()
        assertEquals(9_500L, w.unitLiters["2-2"]) // 3,400 on old meter + 6,100 on new meter
    }

    @Test
    fun `main meter reconciliation shows unallocated consumption`() {
        val w = Fixtures.water()
        assertEquals(142_000L, w.mainLiters)
        assertEquals(115_500L, w.unitSumLiters)
        assertEquals(26_500L, w.unallocatedLiters)
        assertTrue(w.issues.any { it.code == IssueCode.UNALLOCATED_WATER })
        assertTrue(w.issues.none { it.blocking })
    }

    @Test
    fun `unit meters exceeding main is warned`() {
        val readings = Fixtures.readings.map { if (it.id == "r1") it.copy(valueLiters = 5_200_000) else it }
        val w = WaterCalculator.report(Fixtures.meters, readings, Fixtures.unitIds, s, e)
        assertEquals(-35_500L, w.unallocatedLiters)
        assertTrue(w.issues.any { it.code == IssueCode.UNIT_METERS_EXCEED_MAIN })
    }

    @Test
    fun `consumption allocation proportional`() {
        val r = AllocationEngine.allocate("w", 11_550_000, AllocationPolicy.single(Basis.Consumption()), Fixtures.ctx())
        assertEquals(1_250_000L, r.shares["1-1"]) // 12.5 of 115.5 m³
        assertEquals(0L, r.shares["1-2"])
        assertEquals(11_550_000L, r.allocatedTotal)
    }

    @Test
    fun `common water separate and fund borne`() {
        val sep = AllocationEngine.allocate("w", 18_000_000, AllocationPolicy.single(Basis.Consumption(CommonWater.Separate(SimpleBasis.EQUAL))), Fixtures.ctx())
        assertTrue(sep.valid)
        assertEquals(14_641_000L, sep.components[0].amount) // 18M × 115.5/142 rounded to 1000
        assertEquals(3_359_000L, sep.components[1].amount)
        assertEquals(18_000_000L, sep.allocatedTotal)
        val fb = AllocationEngine.allocate("w", 18_000_000, AllocationPolicy.single(Basis.Consumption(CommonWater.FundBorne)), Fixtures.ctx())
        assertEquals(14_641_000L, fb.allocatedTotal)
        assertEquals(3_359_000L, fb.fundBorne)
    }

    @Test
    fun `common water without main meter blocks`() {
        val meters = Fixtures.meters.filter { it.role != MeterRole.MAIN }
        val w = WaterCalculator.report(meters, Fixtures.readings, Fixtures.unitIds, s, e)
        val r = AllocationEngine.allocate("w", 1_000_000, AllocationPolicy.single(Basis.Consumption(CommonWater.FundBorne)), Fixtures.ctx(water = w))
        assertFalse(r.valid)
        assertTrue(r.issues.any { it.code == IssueCode.MAIN_METER_REQUIRED })
    }

    @Test
    fun `missing unit reading blocks consumption allocation`() {
        val readings = Fixtures.readings.filter { it.id != "r3" }
        val w = WaterCalculator.report(Fixtures.meters, readings, Fixtures.unitIds, s, e)
        assertNull(w.unitLiters["1-1"])
        assertNull(w.unallocatedLiters)
        val r = AllocationEngine.allocate("w", 1_000_000, AllocationPolicy.single(Basis.Consumption()), Fixtures.ctx(water = w))
        assertTrue(r.issues.any { it.code == IssueCode.MISSING_CONSUMPTION && it.unitId == "1-1" })
    }

    private val tiered = WaterTariff(
        "t1", "tier", effectiveFrom = s - 365, fixedFeeMinor = 50_000,
        tiers = listOf(TariffTier(5_000, 10_000), TariffTier(15_000, 20_000), TariffTier(null, 40_000)),
        surchargeBp = 3000,
    )

    @Test
    fun `tiered tariff with fixed fee and surcharge`() {
        // 22.4 m³: 5×10,000 + 10×20,000 + 7.4×40,000 = 50,000+200,000+296,000 = 546,000
        // surcharge 30% = 163,800 ; fixed 50,000 → 759,800 → rounded to 1000 → 760,000
        val c = TariffCalculator.charge(tiered, 22_400, 1000)
        assertEquals(546_000L, c.consumptionMinor)
        assertEquals(163_800L, c.surchargeMinor)
        assertEquals(760_000L, c.totalMinor)
        assertEquals(3, c.tiers.size)
        assertEquals(50_000L, TariffCalculator.charge(tiered, 0, 1000).totalMinor) // fixed fee only
    }

    @Test
    fun `tariff selection follows effective date`() {
        val newer = tiered.copy(id = "t2", effectiveFrom = s + 10, fixedFeeMinor = 70_000)
        assertEquals("t2", TariffCalculator.select(listOf(tiered, newer), e)?.id)
        assertEquals("t1", TariffCalculator.select(listOf(tiered, newer), s + 5)?.id)
        assertNull(TariffCalculator.select(listOf(newer), s))
    }

    @Test
    fun `invalid tariffs rejected`() {
        assertFalse(TariffCalculator.validate(tiered.copy(tiers = emptyList())))
        assertFalse(TariffCalculator.validate(tiered.copy(tiers = listOf(TariffTier(5_000, 1)))))
        assertFalse(TariffCalculator.validate(tiered.copy(tiers = listOf(TariffTier(5_000, 1), TariffTier(4_000, 1), TariffTier(null, 1)))))
    }

    @Test
    fun `water tariff charge with common component`() {
        val t = tiered.copy(commonRateMinorPerM3 = 9_000)
        val r = AllocationEngine.waterTariff("wt", t, AllocationPolicy.single(Basis.Consumption(CommonWater.Separate(SimpleBasis.EQUAL))), Fixtures.ctx())
        assertTrue(r.valid)
        // common: 26.5 m³ × 9,000 = 238,500 → 239,000 split equally among 9
        assertEquals(239_000L, r.components[1].amount)
        assertEquals(760_000L + r.components[1].units.first { it.unitId == "3-1" }.share, r.shares["3-1"])
    }

    @Test
    fun `double charging water is prevented`() {
        val waterCat = Category("cw", "آب", isWater = true)
        val input = Fixtures.input().copy(
            categories = listOf(waterCat),
            tariffs = listOf(tiered),
            chargeItems = listOf(ChargeItem("ci", "آب", "cw", "op", ChargeMode.WATER_TARIFF, 0, AllocationPolicy.single(Basis.Consumption()))),
            expenses = listOf(Expense("bill", "cw", "op", "قبض", 18_000_000, AllocationPolicy.single(Basis.Consumption()), e)),
        )
        val res = PeriodCalculator.calculate(input)
        assertFalse(res.canFinalize)
        assertTrue(res.issues.any { it.code == IssueCode.DOUBLE_WATER_CHARGE && it.sourceId == "bill" })
        // The supplier bill recorded as fund-borne is the correct way: no double charge.
        val ok = PeriodCalculator.calculate(input.copy(expenses = input.expenses.map { it.copy(policy = AllocationPolicy.FUND_ONLY) }))
        assertTrue(ok.issues.none { it.code == IssueCode.DOUBLE_WATER_CHARGE })
        assertTrue(ok.canFinalize)
    }
}
