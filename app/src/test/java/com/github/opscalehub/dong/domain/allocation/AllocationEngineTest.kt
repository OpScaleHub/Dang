package com.github.opscalehub.dong.domain.allocation

import com.github.opscalehub.dong.domain.Fixtures
import com.github.opscalehub.dong.domain.model.OccupancyBasis
import com.github.opscalehub.dong.domain.model.OccupancyRecord
import com.github.opscalehub.dong.domain.model.ParkingRelation
import com.github.opscalehub.dong.domain.period.IssueCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllocationEngineTest {

    private val ctx = Fixtures.ctx()
    private fun codes(r: AllocationResult) = r.issues.map { it.code }.toSet()

    @Test
    fun `equal share per unit`() {
        val r = AllocationEngine.allocate("x", 4_500_000, AllocationPolicy.single(Basis.Equal), ctx)
        assertTrue(r.valid)
        assertEquals(9, r.shares.size)
        assertTrue(r.shares.values.all { it == 500_000L })
    }

    @Test
    fun `per occupant uses occupant days`() {
        val r = AllocationEngine.allocate("clean", 9_000_000, AllocationPolicy.single(Basis.Occupants), ctx)
        assertEquals(9_000_000L, r.allocatedTotal)
        // 26 occupants: 9,000,000/26 = 346,153.8 per person → 3-1 has 5 people
        assertEquals(1_731_000L, r.shares["3-1"])
        val detail = r.components.single().units.first { it.unitId == "3-1" }
        assertEquals(QuantityKind.OCCUPANT_DAYS, detail.quantityKind)
        assertEquals(5L * 30, detail.quantity)
    }

    @Test
    fun `occupancy change mid period is day weighted`() {
        // 1-2 has 1 occupant, then 3 from day 16 of 30: 15×1 + 15×3 = 60 occupant-days
        val occ = Fixtures.occupancy + OccupancyRecord("1-2", Fixtures.start + 15, 3)
        val c = Fixtures.ctx(occ = occ)
        assertEquals(60L, c.occupancyQuantity["1-2"])
        val atEnd = Fixtures.ctx(occ = occ, basis = OccupancyBasis.PERIOD_END)
        assertEquals(3L, atEnd.occupancyQuantity["1-2"])
        val atStart = Fixtures.ctx(occ = occ, basis = OccupancyBasis.PERIOD_START)
        assertEquals(1L, atStart.occupancyQuantity["1-2"])
    }

    @Test
    fun `zero occupants gives zero share and is explained`() {
        val occ = Fixtures.occupancy.map { if (it.unitId == "1-2") it.copy(occupantCount = 0) else it }
        val r = AllocationEngine.allocate("clean", 2_500_000, AllocationPolicy.single(Basis.Occupants), Fixtures.ctx(occ = occ))
        assertTrue(r.valid)
        assertEquals(0L, r.shares["1-2"])
        assertTrue(IssueCode.UNIT_ZERO_WEIGHT in codes(r))
        assertEquals(2_500_000L, r.allocatedTotal)
    }

    @Test
    fun `missing occupancy record blocks`() {
        val occ = Fixtures.occupancy.filter { it.unitId != "4-2" }
        val r = AllocationEngine.allocate("clean", 1_000_000, AllocationPolicy.single(Basis.Occupants), Fixtures.ctx(occ = occ))
        assertFalse(r.valid)
        assertTrue(IssueCode.MISSING_OCCUPANCY in codes(r))
        assertTrue(r.shares.isEmpty())
    }

    @Test
    fun `area based allocation`() {
        val r = AllocationEngine.allocate("maint", 12_000_000, AllocationPolicy.single(Basis.Area), ctx)
        assertEquals(12_000_000L, r.allocatedTotal)
        // 958 m²: 60/958×12M = 751,565.7 ; 156/958×12M = 1,954,070.9 (values from workbook reference)
        assertEquals(751_000L, r.shares["1-1"])
        assertEquals(1_954_000L, r.shares["2-1"])
    }

    @Test
    fun `missing area blocks`() {
        val units = Fixtures.units.map { if (it.id == "1-3") it.copy(areaCentiM2 = 0) else it }
        val r = AllocationEngine.allocate("maint", 1_000_000, AllocationPolicy.single(Basis.Area), Fixtures.ctx(unitList = units))
        assertTrue(IssueCode.MISSING_AREA in codes(r))
        assertFalse(r.valid)
    }

    @Test
    fun `weighted allocation`() {
        val weights = mapOf("2-1" to 1L, "2-2" to 1L, "3-1" to 2L, "3-2" to 2L, "4-1" to 3L, "4-2" to 3L)
        val r = AllocationEngine.allocate("elev", 6_000_000, AllocationPolicy.single(Basis.Weighted(weights)), ctx)
        assertEquals(1_500_000L, r.shares["4-2"])
        assertEquals(0L, r.shares["1-1"])
        assertEquals(6_000_000L, r.allocatedTotal)
    }

    @Test
    fun `explicit amounts must match the total`() {
        val ok = AllocationEngine.allocate("rep", 2_500_000, AllocationPolicy.single(Basis.Explicit(mapOf("2-1" to 1_500_000L, "4-2" to 1_000_000L))), ctx)
        assertTrue(ok.valid)
        assertEquals(mapOf("2-1" to 1_500_000L, "4-2" to 1_000_000L), ok.shares)
        val bad = AllocationEngine.allocate("rep", 2_600_000, AllocationPolicy.single(Basis.Explicit(mapOf("2-1" to 1_500_000L, "4-2" to 1_000_000L))), ctx)
        assertFalse(bad.valid)
        assertTrue(IssueCode.EXPLICIT_SUM_MISMATCH in codes(bad))
    }

    @Test
    fun `parking in use only`() {
        val r = AllocationEngine.allocate("pel", 1_600_000, AllocationPolicy.single(Basis.Parking(SpaceFilter.IN_USE)), ctx)
        assertTrue(r.valid)
        // in-use spaces: 2-1:1, 3-1:2, 4-1:1, 2-2:1, 3-2:1 = 6
        assertEquals(533_000L, r.shares["3-1"])
        assertEquals(0L, r.shares["1-1"])
        assertEquals(1_600_000L, r.allocatedTotal)
        val excluded = r.components.single().excludedSpaces
        assertEquals(setOf("P2", "P8"), excluded.toSet()) // unused + unassigned
        assertTrue(IssueCode.PARKING_SPACES_EXCLUDED in codes(r))
    }

    @Test
    fun `parking by ownership relation and all assigned`() {
        val owned = AllocationEngine.allocate("p", 1_000_000, AllocationPolicy.single(Basis.Parking(SpaceFilter.ALL_ASSIGNED, setOf(ParkingRelation.OWNED))), ctx)
        // owned: 2-1:2 (one unused), 3-1:2, 4-1:1 = 5 → 200,000 each space
        assertEquals(400_000L, owned.shares["2-1"])
        assertEquals(0L, owned.shares["3-2"])
        val selected = AllocationEngine.allocate("p", 900_000, AllocationPolicy.single(Basis.Parking(SpaceFilter.SELECTED, spaceIds = setOf("P1", "P7"), spaceWeights = mapOf("P7" to 2L))), ctx)
        assertEquals(300_000L, selected.shares["2-1"])
        assertEquals(600_000L, selected.shares["3-2"])
    }

    @Test
    fun `fixed plus variable components`() {
        val p = AllocationPolicy(components = listOf(
            AllocationComponent(Portion.Fixed(900_000), Basis.Equal),
            AllocationComponent(Portion.Remainder, Basis.Area),
        ))
        val r = AllocationEngine.allocate("mix", 10_000_000, p, ctx)
        assertTrue(r.valid)
        assertEquals(10_000_000L, r.allocatedTotal)
        assertEquals(2, r.components.size)
        assertEquals(900_000L, r.components[0].amount)
        assertEquals(9_100_000L, r.components[1].amount)
        assertEquals(100_000L, r.components[0].units.first { it.unitId == "1-1" }.share)
    }

    @Test
    fun `percent components must cover the amount`() {
        val p = AllocationPolicy(components = listOf(
            AllocationComponent(Portion.Percent(3000), Basis.Equal),
            AllocationComponent(Portion.Percent(7000), Basis.Area),
        ))
        val r = AllocationEngine.allocate("pct", 1_000_001, p, ctx)
        assertTrue(r.valid)
        assertEquals(1_000_001L, r.allocatedTotal)
        val incomplete = AllocationEngine.allocate("pct", 1_000, AllocationPolicy(components = listOf(AllocationComponent(Portion.Percent(3000), Basis.Equal))), ctx)
        assertTrue(IssueCode.PORTIONS_INCOMPLETE in codes(incomplete))
        val tooBig = AllocationEngine.allocate("fx", 1_000, AllocationPolicy(components = listOf(AllocationComponent(Portion.Fixed(2_000), Basis.Equal), AllocationComponent())), ctx)
        assertTrue(IssueCode.PORTIONS_EXCEED_AMOUNT in codes(tooBig))
    }

    @Test
    fun `subset of units`() {
        val r = AllocationEngine.allocate("sub", 1_000_000, AllocationPolicy.single(Basis.Equal, Eligibility.Selected(setOf("1-1", "1-2", "1-3"))), ctx)
        assertEquals(setOf("1-1", "1-2", "1-3"), r.shares.keys)
        assertEquals(334_000L + 333_000 + 333_000, r.allocatedTotal)
        assertEquals(334_000L, r.shares["1-1"])
        assertEquals(6, r.components.single().excludedUnits.size)
    }

    @Test
    fun `zero eligible units blocks`() {
        val r = AllocationEngine.allocate("none", 1_000_000, AllocationPolicy.single(Basis.Equal, Eligibility.Selected(emptySet())), ctx)
        assertFalse(r.valid)
        assertTrue(IssueCode.NO_ELIGIBLE_UNITS in codes(r))
    }

    @Test
    fun `zero basis blocks instead of dividing by zero`() {
        val r = AllocationEngine.allocate("w", 1_000_000, AllocationPolicy.single(Basis.Weighted(emptyMap())), ctx)
        assertFalse(r.valid)
        assertTrue(IssueCode.ZERO_BASIS in codes(r))
    }

    @Test
    fun `inactive units are excluded`() {
        val units = Fixtures.units.map { if (it.id == "4-2") it.copy(active = false) else it }
        val r = AllocationEngine.allocate("eq", 800_000, AllocationPolicy.single(Basis.Equal), Fixtures.ctx(unitList = units))
        assertEquals(8, r.shares.size)
        assertTrue("4-2" !in r.shares)
        assertEquals(100_000L, r.shares["1-1"])
    }

    @Test
    fun `unknown unit in policy blocks`() {
        val r = AllocationEngine.allocate("w", 1_000, AllocationPolicy.single(Basis.Weighted(mapOf("9-9" to 1L, "1-1" to 1L))), ctx)
        assertTrue(IssueCode.UNKNOWN_UNIT_IN_POLICY in codes(r))
        assertFalse(r.valid)
    }

    @Test
    fun `negative amount credit is allocated exactly`() {
        val r = AllocationEngine.allocate("credit", -1_000_001, AllocationPolicy.single(Basis.Equal), Fixtures.ctx(increment = 1))
        assertEquals(-1_000_001L, r.allocatedTotal)
        assertTrue(r.shares.values.all { it < 0 })
    }

    @Test
    fun `fund only policy charges nothing`() {
        val r = AllocationEngine.allocate("x", 3_000_000, AllocationPolicy.FUND_ONLY, ctx)
        assertTrue(r.shares.isEmpty())
        assertEquals(3_000_000L, r.fundBorne)
    }

    @Test
    fun `rate based charge per square metre`() {
        val r = AllocationEngine.rate("rate", 10_000, AllocationPolicy.single(Basis.Area), ctx)
        assertEquals(600_000L, r.shares["1-1"])
        assertEquals(9_580_000L, r.allocatedTotal)
        val perPerson = AllocationEngine.rate("rp", 100_000, AllocationPolicy.single(Basis.Occupants), ctx)
        assertEquals(500_000L, perPerson.shares["3-1"])
    }

    @Test
    fun `policy json round trip`() {
        val p = AllocationPolicy(components = listOf(
            AllocationComponent(Portion.Fixed(1), Basis.Consumption(CommonWater.Separate(SimpleBasis.AREA)), Eligibility.Selected(setOf("1-1"))),
            AllocationComponent(Portion.Remainder, Basis.Parking(SpaceFilter.SELECTED, setOf(ParkingRelation.RENTED), setOf("P1"), mapOf("P1" to 2L))),
        ))
        assertEquals(p, AllocationPolicy.decode(p.encode()))
        assertEquals(AllocationPolicy.FUND_ONLY, AllocationPolicy.decode(AllocationPolicy.FUND_ONLY.encode()))
    }
}
