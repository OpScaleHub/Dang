package com.github.opscalehub.dong.domain.period

import com.github.opscalehub.dong.domain.Fixtures
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.domain.model.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodTest {

    private fun exp(id: String, amount: Long, basis: Basis = Basis.Equal) =
        Expense(id, "c", "op", id, amount, AllocationPolicy.single(basis), Fixtures.end)

    @Test
    fun `invalid dates block`() {
        val r = PeriodCalculator.calculate(Fixtures.input().copy(startDate = Fixtures.end, endDate = Fixtures.start))
        assertEquals(IssueCode.INVALID_PERIOD_DATES, r.issues.single().code)
        assertFalse(r.canFinalize)
    }

    @Test
    fun `empty period warns but does not block`() {
        val r = PeriodCalculator.calculate(Fixtures.input())
        assertTrue(r.issues.any { it.code == IssueCode.EMPTY_PERIOD })
        assertTrue(r.canFinalize)
    }

    @Test
    fun `invalid expense blocks finalization and produces no lines for it`() {
        val r = PeriodCalculator.calculate(Fixtures.input().copy(expenses = listOf(exp("ok", 900_000), exp("bad", 1_000, Basis.Weighted(emptyMap())))))
        assertFalse(r.canFinalize)
        assertTrue(r.lines.all { it.sourceId == "ok" })
        assertEquals(900_000L, r.total)
    }

    @Test
    fun `missing meter reading blocks finalization when a bill is split by meter`() {
        val r = PeriodCalculator.calculate(Fixtures.input().copy(readings = Fixtures.readings.filter { it.id != "r21" },
            expenses = listOf(exp("w", 1_000_000, Basis.Consumption()))))
        assertTrue(r.blockingIssues.any { it.code == IssueCode.MISSING_CLOSING_READING && it.meterId == "M-42" })
    }

    @Test
    fun `a meter nobody splits by never holds up the month`() {
        val r = PeriodCalculator.calculate(Fixtures.input().copy(readings = emptyList(), expenses = listOf(exp("e", 900_000))))
        assertTrue(r.canFinalize)
        assertEquals(900_000L, r.total)
    }

    @Test
    fun `correction diff posts only differences`() {
        val original = PeriodCalculator.calculate(Fixtures.input().copy(expenses = listOf(exp("w", 11_550_000, Basis.Consumption()))))
        // A reading for 1-1 is corrected after finalization: 322.7 → 324.7 m³.
        val fixed = Fixtures.readings.map { if (it.id == "r3") it.copy(id = "r3c", valueLiters = 324_700, supersedesId = "r3") else it } + Fixtures.readings.first { it.id == "r3" }
        val corrected = PeriodCalculator.calculate(Fixtures.input().copy(readings = fixed, expenses = listOf(exp("w", 11_550_000, Basis.Consumption()))))
        val diff = PeriodCorrection.diff(original.lines, corrected.lines)
        assertEquals(0L, diff.sumOf { it.amount }) // same bill, only redistributed
        assertTrue(diff.first { it.unitId == "1-1" }.amount > 0)
        assertTrue(diff.filter { it.unitId != "1-1" }.all { it.amount <= 0 })
        assertEquals(original.total + diff.sumOf { it.amount }, corrected.total)
    }
}
