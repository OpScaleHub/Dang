package com.github.opscalehub.dong.domain

import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.domain.allocation.CommonWater
import com.github.opscalehub.dong.domain.allocation.SimpleBasis
import com.github.opscalehub.dong.domain.allocation.SpaceFilter
import com.github.opscalehub.dong.domain.ledger.Balances
import com.github.opscalehub.dong.domain.ledger.Posting
import com.github.opscalehub.dong.domain.ledger.PostingRules
import com.github.opscalehub.dong.domain.model.Category
import com.github.opscalehub.dong.domain.model.ChargeItem
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.domain.model.Expense
import com.github.opscalehub.dong.domain.period.PeriodCalculator
import com.github.opscalehub.dong.domain.period.PeriodSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the exact scenario of workbook/Dong-accounting-prototype.xlsx through
 * the Kotlin engine and compares every share and balance with
 * workbook/expected-results.json (produced by the independent exact-fraction
 * reference implementation in build_workbook.py, whose values the workbook
 * formulas are verified against by verify_workbook.py).
 */
class WorkbookScenarioTest {

    private val expected = Json.parseToJsonElement(
        listOf("../workbook/expected-results.json", "workbook/expected-results.json").map(::File).first { it.exists() }.readText(),
    ).jsonObject

    private val op = "عملیاتی"
    private val res = "اندوخته"
    private val weights = mapOf("2-1" to 1L, "2-2" to 1L, "3-1" to 2L, "3-2" to 2L, "4-1" to 3L, "4-2" to 3L)
    private val expenses = listOf(
        Expense("E1", "نظافت", op, "نظافت", 9_000_000, AllocationPolicy.single(Basis.Occupants), Fixtures.end),
        Expense("E2", "عمومی", op, "عمومی", 4_500_000, AllocationPolicy.single(Basis.Equal), Fixtures.end),
        Expense("E3", "نگهداری", op, "نگهداری", 12_000_000, AllocationPolicy.single(Basis.Area), Fixtures.end),
        Expense("E4", "آب", op, "آب", 18_000_000, AllocationPolicy.single(Basis.Consumption(CommonWater.Separate(SimpleBasis.EQUAL))), Fixtures.end),
        Expense("E5", "برق پارکینگ", op, "برق پارکینگ", 1_600_000, AllocationPolicy.single(Basis.Parking(SpaceFilter.IN_USE)), Fixtures.end),
        Expense("E6", "آسانسور", op, "آسانسور", 6_000_000, AllocationPolicy.single(Basis.Weighted(weights)), Fixtures.end),
        Expense("E7", "اختصاصی", op, "اختصاصی", 2_500_000, AllocationPolicy.single(Basis.Explicit(mapOf("2-1" to 1_500_000L, "4-2" to 1_000_000L))), Fixtures.end),
        Expense("E8", "اضطراری", res, "اضطراری", 3_000_000, AllocationPolicy.FUND_ONLY, Fixtures.end),
    )
    private val input = Fixtures.input().copy(
        categories = listOf(Category("آب", "آب", isWater = true)),
        expenses = expenses,
        chargeItems = listOf(ChargeItem("C1", "حق اندوخته", "اندوخته", res, ChargeMode.TOTAL, 9_000_000, AllocationPolicy.single(Basis.Equal))),
    )

    @Test
    fun `every share matches the workbook reference`() {
        val result = PeriodCalculator.calculate(input)
        assertTrue(result.blockingIssues.toString(), result.canFinalize)
        val lines = expected.getValue("lines").jsonObject
        for ((lineId, line) in lines) {
            val src = lineId.substringBefore("-")
            val part = when {
                lineId.endsWith("-a") -> 0
                lineId.endsWith("-b") -> 1
                else -> null
            }
            val alloc = result.sources.single { it.sourceId == src }.allocation
            val shares = if (part == null) alloc.shares else alloc.components[part].units.associate { it.unitId to it.share }
            val exp = line.jsonObject.getValue("shares").jsonObject
            for ((unit, v) in exp) {
                assertEquals("$lineId / $unit", v.jsonPrimitive.long, shares[unit] ?: 0L)
            }
        }
        val totals = expected.getValue("unitChargeTotals").jsonObject
        totals.forEach { (u, v) -> assertEquals("total $u", v.jsonPrimitive.long, result.unitTotals[u] ?: 0L) }
        assertEquals(3_000_000L, result.fundBorneTotal)
        val water = expected.getValue("waterLitres").jsonObject
        assertEquals(water.getValue("unallocated").jsonPrimitive.long, result.water.unallocatedLiters)
    }

    @Test
    fun `ledger balances match the workbook reference`() {
        val result = PeriodCalculator.calculate(input)
        val j = mutableListOf<Posting>()
        val cash = "cash"
        j += PostingRules.opening(mapOf(cash to 60_000_000), mapOf("1-2" to 1_200_000L, "3-1" to -500_000L), emptyMap(), mapOf(op to 20_700_000L, res to 40_000_000L))
        val suppliers = mapOf("E1" to "s1", "E3" to "s3", "E4" to "s4", "E5" to "s5", "E6" to "s6", "E7" to "s7", "E8" to "s8")
        expenses.forEach { j += PostingRules.expenseIncurred(it.amountMinor, it.fundId, it.categoryId, suppliers[it.id]) }
        listOf("E1" to 9_000_000L, "E2" to 4_500_000L, "E3" to 6_000_000L, "E5" to 1_600_000L, "E6" to 6_000_000L, "E7" to 2_500_000L, "E8" to 3_000_000L)
            .forEach { (e, amt) -> j += PostingRules.supplierPayment(amt, suppliers[e], cash) }
        j += PostingRules.charges(result.lines)
        listOf("1-1" to 3_000_000L, "1-2" to 2_000_000L, "2-1" to 9_000_000L, "2-2" to 4_500_000L, "3-1" to 10_000_000L,
            "3-2" to 4_000_000L, "4-1" to 5_000_000L, "4-2" to 4_600_000L).forEach { (u, a) -> j += PostingRules.residentPayment(a, u, cash) }
        j += PostingRules.adjustment(-300_000, "1-2", op, "نظافت")
        j += PostingRules.refund(500_000, "3-1", cash)
        j += PostingRules.fundTransfer(5_000_000, op, res)

        val b = Balances(j)
        assertEquals(0L, b.trialBalance)
        expected.getValue("unitClosingBalances").jsonObject.forEach { (u, v) -> assertEquals("closing $u", v.jsonPrimitive.long, b.unit(u)) }
        assertEquals(expected.getValue("cashClosing").jsonPrimitive.long, b.cash())
        assertEquals(expected.getValue("payableClosing").jsonPrimitive.long, b.payable())
        val funds = expected.getValue("funds").jsonObject
        assertEquals(funds.getValue(op).jsonPrimitive.long, b.fund(op))
        assertEquals(funds.getValue(res).jsonPrimitive.long, b.fund(res))
        assertEquals(b.netAssets, b.fund(op) + b.fund(res))
    }

    @Test
    fun `snapshot round trips losslessly`() {
        val result = PeriodCalculator.calculate(input)
        val snap = PeriodSnapshot(calculatedAt = 1, input = input, result = result)
        val back = PeriodSnapshot.decode(snap.encode())
        assertEquals(snap, back)
        // Recalculating from the stored input reproduces the stored result exactly.
        assertEquals(result, PeriodCalculator.calculate(back.input))
    }
}
