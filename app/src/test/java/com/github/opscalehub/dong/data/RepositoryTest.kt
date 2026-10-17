package com.github.opscalehub.dong.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.data.db.ExpenseStatus
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.data.db.SupplierEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.domain.ledger.EntryType
import com.github.opscalehub.dong.domain.period.IssueCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private val worlds = mutableListOf<World>()
    private fun world(name: String? = null) = World(name).also { worlds += it }

    @After
    fun close() = worlds.forEach { it.db.close() }

    private suspend fun expectError(code: OpError, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: OpException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun `building is created with funds cash box and categories`() = runBlocking {
        val w = world().setup()
        assertEquals(2, w.db.financeDao().funds(w.b).size)
        assertEquals(1, w.db.financeDao().cashAccounts(w.b).size)
        assertTrue(w.categories.size >= 10)
        assertEquals(9, w.db.buildingDao().units(w.b).size)
        assertEquals(95_800L, w.units.sumOf { it.areaCentiM2 })
        assertTrue(w.db.financeDao().audit(w.b).any { it.action == "CREATE_BUILDING" })
    }

    @Test
    fun `multiple buildings are independent`() = runBlocking {
        val w = world().setup("الف")
        val other = w.r.buildings.create("ب")
        w.r.buildings.saveUnit(UnitEntity(newId(), other, 1, "1", 5000, sortOrder = 1))
        w.r.finance.receivePayment(w.b, w.units[0].id, 1_000_000, w.cash, w.start)
        assertEquals(9, w.db.buildingDao().units(w.b).size)
        assertEquals(1, w.db.buildingDao().units(other).size)
        assertEquals(1_000_000L, w.r.finance.balances(w.b).cashTotal)
        assertEquals(0L, w.r.finance.balances(other).cashTotal)
        w.r.buildings.delete(other)
        assertNull(w.db.buildingDao().building(other))
        assertEquals(1_000_000L, w.r.finance.balances(w.b).cashTotal)
    }

    @Test
    fun `data survives database restart`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("restart.db")
        val w = World("restart.db").setup()
        w.r.finance.receivePayment(w.b, w.units[0].id, 2_500_000, w.cash, w.start)
        w.db.close()
        val reopened = DongDatabase.build(ctx, "restart.db")
        try {
            val r = Repos(reopened, FixedClock())
            assertEquals(9, reopened.buildingDao().units(w.b).size)
            assertEquals(-2_500_000L, r.finance.balances(w.b).units.getValue(w.units[0].id))
        } finally {
            reopened.close()
            ctx.deleteDatabase("restart.db")
        }
    }

    @Test
    fun `failed transaction rolls back every write`() = runBlocking {
        val w = world().setup()
        val before = w.db.financeDao().entries(w.b).size
        try {
            w.r.tx {
                w.r.finance.receivePayment(w.b, w.units[0].id, 1_000, w.cash, w.start)
                w.r.finance.receivePayment(w.b, w.units[0].id, -5, w.cash, w.start) // invalid → throws
            }
            fail("should throw")
        } catch (e: OpException) {
            assertEquals(OpError.INVALID_AMOUNT, e.code)
        }
        assertEquals(before, w.db.financeDao().entries(w.b).size)
        assertEquals(0L, w.r.finance.balances(w.b).cashTotal)
    }

    @Test
    fun `expense lifecycle with partial payments edits and void`() = runBlocking {
        val w = world().setup()
        val p = w.r.periods.create(w.b, "مهر", w.start, w.end)
        val sup = w.r.expenses.saveSupplier(SupplierEntity(newId(), w.b, "تاسیسات نمونه"))
        val e = w.expense("تعمیر و نگهداری ساختمان", 12_000_000, p, supplierId = sup)
        w.r.expenses.create(e)
        assertEquals(12_000_000L, w.r.finance.balances(w.b).payablesTotal)
        w.r.expenses.paySupplier(e.id, 5_000_000, w.cash, w.end)
        assertEquals(5_000_000L, w.r.expenses.paid(e.id))
        expectError(OpError.INVALID_AMOUNT) { w.r.expenses.paySupplier(e.id, 8_000_000, w.cash, w.end) } // overpayment
        // amount edit → reversal + new entry; payable follows
        w.r.expenses.update(e.copy(amountMinor = 11_000_000), "اصلاح فاکتور")
        assertEquals(6_000_000L, w.r.finance.balances(w.b).payablesTotal)
        assertEquals(1, w.db.financeDao().entriesForExpense(e.id).count { it.type == EntryType.REVERSAL })
        expectError(OpError.HAS_PAYMENTS) { w.r.expenses.void(e.id, "اشتباه") }
        val payment = w.db.financeDao().entriesForExpense(e.id).first { it.type == EntryType.SUPPLIER_PAYMENT }
        w.r.finance.reverse(payment.id, "پرداخت اشتباه")
        expectError(OpError.ALREADY_REVERSED) { w.r.finance.reverse(payment.id, "دوباره") }
        assertEquals(0L, w.r.expenses.paid(e.id))
        w.r.expenses.void(e.id, "اشتباه")
        assertEquals(ExpenseStatus.VOIDED, w.db.expenseDao().expense(e.id)!!.status)
        val bal = w.r.finance.balances(w.b)
        assertEquals(0L, bal.payablesTotal)
        assertEquals(0L, bal.cashTotal)
        assertTrue(bal.reconciles)
    }

    @Test
    fun `finalize posts charges stores snapshot and locks the period`() = runBlocking {
        val w = world().setup()
        w.addMeters()
        val p = w.r.periods.create(w.b, "مهر", w.start, w.end)
        w.r.expenses.create(w.expense("نظافت", 9_000_000, p), payFromCashId = null)
        w.r.expenses.create(w.expense("آب", 18_000_000, p))
        val preview = w.r.periods.preview(p)
        assertTrue(preview.blockingIssues.toString(), preview.canFinalize)
        assertEquals(27_000_000L, preview.total)
        val res = w.r.periods.finalize(p)
        assertEquals(27_000_000L, res.total)
        val period = w.db.periodDao().period(p)!!
        assertEquals(PeriodStatus.FINALIZED, period.status)
        assertNotNull(period.snapshotJson)
        val bal = w.r.finance.balances(w.b)
        assertEquals(27_000_000L, bal.outstandingDebts)
        assertTrue(bal.reconciles)

        // immutability
        expectError(OpError.PERIOD_FINALIZED) { w.r.periods.finalize(p) }
        expectError(OpError.PERIOD_FINALIZED) { w.r.expenses.create(w.expense("نظافت", 1_000, p)) }
        val meter = w.db.waterDao().meters(w.b).first { it.unitId == w.units[0].id }
        expectError(OpError.LOCKED_BY_FINALIZED_PERIOD) { w.r.water.addReading(meter.id, w.start + 3, 100_500) }
        // changing settings and default policies does not alter the finalized result
        w.r.buildings.updateSettings(w.db.buildingDao().building(w.b)!!.copy(roundingIncrement = 10_000))
        val clean = w.categories.getValue("نظافت")
        w.r.expenses.saveCategory(clean.copy(defaultPolicyJson = AllocationPolicy.single(Basis.Area).encode()))
        assertEquals(res, w.r.periods.preview(p))
        assertEquals(res.lines, w.r.periods.snapshot(p)!!.result.lines)
    }

    @Test
    fun `finalization with blocking issues writes nothing`() = runBlocking {
        val w = world().setup()
        val p = w.r.periods.create(w.b, "مهر", w.start, w.end)
        w.r.expenses.create(w.expense("آب", 1_000_000, p)) // no meters → consumption basis is missing
        val entries = w.db.financeDao().entries(w.b).size
        expectError(OpError.PERIOD_HAS_BLOCKING_ISSUES) { w.r.periods.finalize(p) }
        assertEquals(PeriodStatus.DRAFT, w.db.periodDao().period(p)!!.status)
        assertEquals(entries, w.db.financeDao().entries(w.b).size)
        assertTrue(w.r.periods.preview(p).issues.any { it.code == IssueCode.MISSING_CONSUMPTION })
    }

    @Test
    fun `overlapping periods are rejected`() = runBlocking {
        val w = world().setup()
        w.r.periods.create(w.b, "مهر", w.start, w.end)
        expectError(OpError.PERIOD_OVERLAP) { w.r.periods.create(w.b, "x", w.end, w.end + 30) }
    }

    @Test
    fun `correction after finalization posts linked differences only`() = runBlocking {
        val w = world().setup()
        w.addMeters()
        val p = w.r.periods.create(w.b, "مهر", w.start, w.end)
        w.r.expenses.create(w.expense("آب", 4_500_000, p, AllocationPolicy.single(Basis.Consumption())))
        val original = w.r.periods.finalize(p)
        // unit 1-1 meter closing reading was mistyped: 101,000 → 104,000 (3,000 L more)
        val meter = w.db.waterDao().meters(w.b).first { it.unitId == w.unit("1-1").id }
        val closing = w.db.waterDao().readings(w.b).first { it.meterId == meter.id && it.readingDate == w.end }
        expectError(OpError.NOTHING_TO_CORRECT) { w.r.periods.applyCorrection(p, "هیچ") }
        w.r.water.correctReading(closing.id, 104_000, "خطای ثبت")
        val prev = w.r.periods.previewCorrection(p)
        assertEquals(0L, prev.diff.sumOf { it.amount }) // the same bill is redistributed
        assertTrue(prev.diff.first { it.unitId == w.unit("1-1").id }.amount > 0)
        val totalsBefore = w.r.finance.balances(w.b).outstandingDebts
        w.r.periods.applyCorrection(p, "خطای ثبت قرائت")
        assertEquals(1, w.db.periodDao().revisions(p).size)
        // original snapshot untouched; effective result is the revision
        assertEquals(original.lines, w.r.periods.snapshot(p)!!.result.lines)
        assertEquals(prev.result.lines, w.r.periods.effectiveResult(p).lines)
        val bal = w.r.finance.balances(w.b)
        assertEquals(totalsBefore, bal.outstandingDebts)
        assertTrue(bal.reconciles)
        expectError(OpError.NOTHING_TO_CORRECT) { w.r.periods.applyCorrection(p, "دوباره") }
        assertTrue(w.db.financeDao().entries(w.b).any { it.type == EntryType.PERIOD_CORRECTION && it.periodId == p })
    }

    @Test
    fun `opening balances payments refunds adjustments and reserve transfers reconcile`() = runBlocking {
        val w = world().setup()
        val u12 = w.unit("1-2").id
        val u31 = w.unit("3-1").id
        w.r.finance.postOpening(w.b, w.start - 1, mapOf(w.cash to 60_000_000), mapOf(u12 to 1_200_000L, u31 to -500_000L), emptyMap(), mapOf(w.res to 40_000_000L))
        expectError(OpError.OPENING_EXISTS) { w.r.finance.postOpening(w.b, w.start, mapOf(w.cash to 1), emptyMap(), emptyMap(), emptyMap()) }
        var bal = w.r.finance.balances(w.b)
        assertEquals(20_700_000L, bal.funds.getValue(w.op))
        assertEquals(40_000_000L, bal.funds.getValue(w.res))
        w.r.finance.receivePayment(w.b, u12, 2_000_000, w.cash, w.start, method = "نقد") // overpays by 800,000
        w.r.finance.refund(w.b, u31, 500_000, w.cash, w.start, "استرداد بستانکاری")
        w.r.finance.adjust(w.b, u12, -300_000, w.op, w.categories.getValue("نظافت").id, w.start, "کسر هزینه نظافت")
        expectError(OpError.REASON_REQUIRED) { w.r.finance.adjust(w.b, u12, 100, w.op, null, w.start, " ") }
        w.r.finance.transferFunds(w.b, w.op, w.res, 5_000_000, w.start, "اندوخته")
        bal = w.r.finance.balances(w.b)
        assertEquals(-1_100_000L, bal.units.getValue(u12))
        assertEquals(0L, bal.units.getValue(u31))
        assertEquals(61_500_000L, bal.cashTotal)
        assertEquals(45_000_000L, bal.funds.getValue(w.res))
        assertTrue(bal.reconciles)
        val st = w.r.finance.unitStatement(w.b, u12)
        assertEquals(1_200_000L, st.opening)
        assertEquals(2_000_000L, st.payments)
        assertEquals(300_000L, st.creditsAndAdjustments)
        assertEquals(-1_100_000L, st.closing)
        assertEquals("نقد", w.db.financeDao().entries(w.b).first { it.type == EntryType.RESIDENT_PAYMENT }.method)
    }

    @Test
    fun `charges cannot be reversed directly`() = runBlocking {
        val w = world().setup()
        val p = w.r.periods.create(w.b, "مهر", w.start, w.end)
        w.r.expenses.create(w.expense("نظافت", 900_000, p))
        w.r.periods.finalize(p)
        val charge = w.db.periodDao().period(p)!!.chargeEntryId!!
        expectError(OpError.CANNOT_REVERSE) { w.r.finance.reverse(charge, "x") }
    }

    @Test
    fun `occupancy history drives day weighted charges`() = runBlocking {
        val w = world().setup()
        val p = w.r.periods.create(w.b, "مهر", w.start, w.end)
        // 1-2 goes from 1 to 3 occupants on day 16
        w.r.buildings.setOccupancy(w.unit("1-2").id, w.start + 15, 3)
        w.r.expenses.create(w.expense("نظافت", 8_100_000, p))
        val r = w.r.periods.preview(p)
        val unit12 = r.sources.single().allocation.components.single().units.first { it.unitId == w.unit("1-2").id }
        assertEquals(60L, unit12.quantity) // 15×1 + 15×3 occupant-days
        assertFalse(r.blockingIssues.isNotEmpty())
    }

    @Test
    fun `audit - deactivating a unit later does not change a finalized period on correction`() = runBlocking {
        val w = world().setup()
        val p = w.r.periods.create(w.b, "مهر", w.start, w.end)
        w.r.expenses.create(w.expense("هزینه‌های اداری", 900_000, p, AllocationPolicy.single(Basis.Equal)))
        w.r.periods.finalize(p)
        w.r.buildings.saveUnit(w.unit("4-2").copy(active = false))
        w.r.buildings.saveUnit(UnitEntity(newId(), w.b, 5, "5-1", 10_000, sortOrder = 10))
        assertTrue(w.r.periods.previewCorrection(p).diff.isEmpty())
    }

    @Test
    fun `audit - a new meter's first reading is its baseline and it is invisible to earlier periods`() = runBlocking {
        val w = world().setup()
        val p1 = w.r.periods.create(w.b, "مهر", w.start, w.end)
        w.r.periods.finalize(p1)
        val p2 = w.r.periods.create(w.b, "آبان", w.end + 1, w.end + 30)
        val m = w.r.water.saveMeter(com.github.opscalehub.dong.data.db.MeterEntity(newId(), w.b, w.units[0].id, com.github.opscalehub.dong.domain.model.MeterRole.UNIT, "جدید"))
        w.r.water.addReading(m, w.end + 2, 500) // user leaves the kind as REGULAR
        w.r.water.addReading(m, w.end + 30, 3_500)
        val first = w.db.waterDao().readings(w.b).first { it.meterId == m && it.readingDate == w.end + 2 }
        assertEquals(com.github.opscalehub.dong.domain.model.ReadingKind.INITIAL, first.kind)
        assertEquals(w.end + 2, w.db.waterDao().meter(m)!!.installedOn)
        val r2 = w.r.periods.preview(p2)
        assertTrue(r2.issues.none { it.meterId == m && it.blocking })
        assertEquals(3_000L, r2.water.unitLiters[w.units[0].id])
        // the earlier, finalized period can still be corrected (meter did not exist then)
        assertTrue(w.r.periods.previewCorrection(p1).result.canFinalize)
    }

    @Test
    fun `audit - credit notes cannot be paid to the supplier`() = runBlocking {
        val w = world().setup()
        val e = w.expense("هزینه‌های اداری", -200_000, null)
        w.r.expenses.create(e)
        expectError(OpError.INVALID_AMOUNT) { w.r.expenses.paySupplier(e.id, 100_000, w.cash, w.start) }
        assertTrue(w.r.finance.balances(w.b).reconciles)
    }
}
