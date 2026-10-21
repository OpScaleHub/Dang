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

    @Test
    fun `setup wizard creates building units opening and first month atomically`() = runBlocking {
        val w = world()
        val units = listOf(BuildingRepository.UnitDraft("۱-۱", 1, 6_000, 2), BuildingRepository.UnitDraft("۱-۲", 1, 8_000, 3))
        val b = w.r.buildings.setup("آزمون", "", true, 1000, units, 5_000_000, 2_000_000)
        val bld = w.db.buildingDao().building(b)!!
        assertEquals(10L, bld.displayDivisor)
        assertEquals("تومان", bld.displayCurrencyName)
        assertEquals(2, w.db.buildingDao().units(b).size)
        assertEquals(1, w.db.periodDao().periods(b).size)
        val bal = w.r.finance.balances(b)
        assertEquals(5_000_000L, bal.cashTotal)
        assertTrue(bal.reconciles)
        // Invalid input (duplicate label) leaves nothing behind.
        val before = w.db.buildingDao().buildings().size
        expectError(OpError.INVALID_INPUT) { w.r.buildings.setup("x", "", false, 1, units + units.first(), 0, 0) }
        assertEquals(before, w.db.buildingDao().buildings().size)
    }

    private val drafts = listOf(BuildingRepository.UnitDraft("۱-۱", 1, 6_000, 2), BuildingRepository.UnitDraft("۱-۲", 1, 8_000, 3))

    private suspend fun World.closeMonthWithBill(b: String, amount: Long): Map<String, Long> {
        val p = db.periodDao().periods(b).single()
        val cat = db.expenseDao().categories(b).first { it.active && it.name == "نظافت" }
        r.expenses.create(com.github.opscalehub.dong.data.db.ExpenseEntity(newId(), b, periodId = p.id, categoryId = cat.id, fundId = cat.defaultFundId!!,
            date = p.startDate + 3, description = "نظافت", amountMinor = amount, policyJson = cat.defaultPolicyJson, createdAt = 0), null)
        r.periods.finalize(p.id)
        return r.finance.balances(b).units
    }

    @Test
    fun `fixed monthly charge per unit charges each unit the rate and pays bills from the fund`() = runBlocking {
        val w = world()
        val b = w.r.buildings.setup("ثابت", "", true, 1000, drafts, 0, 0, BuildingRepository.ChargePlan.Fixed(BuildingRepository.FixedBasis.PER_UNIT, 5_000_000))
        val cats = w.db.expenseDao().categories(b)
        assertEquals(6, cats.count { it.active })
        assertTrue("every bill is paid from the fund", cats.filter { it.active }.all { it.policy().fundOnly })
        val charge = w.db.expenseDao().chargeItems(b).single()
        assertEquals(MONTHLY_CHARGE, charge.name)
        assertFalse(cats.first { it.id == charge.categoryId }.active)
        val units = w.closeMonthWithBill(b, 3_000_000)
        assertEquals(listOf(5_000_000L, 5_000_000L), w.db.buildingDao().units(b).map { units.getValue(it.id) })
        assertTrue(w.r.finance.balances(b).reconciles)
    }

    @Test
    fun `fixed monthly charge per person follows the number of residents`() = runBlocking {
        val w = world()
        val b = w.r.buildings.setup("نفری", "", true, 1000, drafts, 0, 0, BuildingRepository.ChargePlan.Fixed(BuildingRepository.FixedBasis.PER_PERSON, 1_000_000))
        val units = w.closeMonthWithBill(b, 2_000_000)
        assertEquals(listOf(2_000_000L, 3_000_000L), w.db.buildingDao().units(b).map { units.getValue(it.id) })
    }

    @Test
    fun `fixed charge per square metre needs every area and a positive rate`() = runBlocking {
        val w = world()
        val noArea = drafts + BuildingRepository.UnitDraft("۲-۱", 2, 0, 1)
        expectError(OpError.INVALID_INPUT) { w.r.buildings.setup("x", "", true, 1000, noArea, 0, 0, BuildingRepository.ChargePlan.Fixed(BuildingRepository.FixedBasis.PER_M2, 10_000)) }
        expectError(OpError.INVALID_AMOUNT) { w.r.buildings.setup("x", "", true, 1000, drafts, 0, 0, BuildingRepository.ChargePlan.Fixed(BuildingRepository.FixedBasis.PER_UNIT, 0)) }
        val b = w.r.buildings.setup("متری", "", true, 1000, drafts, 0, 0, BuildingRepository.ChargePlan.Fixed(BuildingRepository.FixedBasis.PER_M2, 10_000))
        val units = w.closeMonthWithBill(b, 1_000_000)
        assertEquals(listOf(600_000L, 800_000L), w.db.buildingDao().units(b).map { units.getValue(it.id) }) // 60 m², 80 m²
    }

    @Test
    fun `splitting bills starts with six everyday expense types`() = runBlocking {
        val w = world()
        val b = w.r.buildings.setup("تقسیم", "", true, 1000, drafts, 0, 0, BuildingRepository.ChargePlan.SplitBills)
        val cats = w.db.expenseDao().categories(b)
        assertEquals(listOf("آب", "برق و گاز مشاعات", "نظافت", "آسانسور", "تعمیرات", "سایر"), cats.sortedBy { it.sortOrder }.map { it.name })
        assertTrue(w.db.expenseDao().chargeItems(b).isEmpty())
        val units = w.closeMonthWithBill(b, 5_000_000) // cleaning by number of people: 2 and 3
        assertEquals(listOf(2_000_000L, 3_000_000L), w.db.buildingDao().units(b).map { units.getValue(it.id) })
    }

    @Test
    fun `combined monthly charge adds area and people rates and shows as one bill line`() = runBlocking {
        val w = world()
        // 60 m² with 2 people, 80 m² with 3 people; 20,000 per m² + 1,000,000 per person (Rial).
        val b = w.r.buildings.setup("ترکیبی", "", true, 1000, drafts, 0, 0, BuildingRepository.ChargePlan.Fixed(perPerson = 1_000_000, perM2 = 20_000))
        assertEquals(2, w.db.expenseDao().chargeItems(b).size)
        val units = w.closeMonthWithBill(b, 1_000_000)
        val ids = w.db.buildingDao().units(b).sortedBy { it.sortOrder }.map { it.id }
        assertEquals(listOf(3_200_000L, 4_600_000L), ids.map { units.getValue(it) })
        val p = w.db.periodDao().periods(b).single()
        val bill = w.r.bills.bill(b, ids[0], p.id)
        assertEquals(listOf(MONTHLY_CHARGE to 3_200_000L), bill.lines)
    }

    @Test
    fun `per unit charge with multipliers and their validation`() = runBlocking {
        val w = world()
        val bad = BuildingRepository.ChargePlan.Fixed(perUnit = 1_000_000, unitWeights = listOf(0, 0))
        expectError(OpError.INVALID_INPUT) { w.r.buildings.setup("x", "", true, 1000, drafts, 0, 0, bad) }
        expectError(OpError.INVALID_INPUT) { w.r.buildings.setup("x", "", true, 1000, drafts, 0, 0, bad.copy(unitWeights = listOf(1))) }
        val b = w.r.buildings.setup("ضریب", "", true, 1000, drafts, 0, 0, bad.copy(unitWeights = listOf(1, 3)))
        val units = w.closeMonthWithBill(b, 1_000_000)
        val ids = w.db.buildingDao().units(b).sortedBy { it.sortOrder }.map { it.id }
        assertEquals(listOf(1_000_000L, 3_000_000L), ids.map { units.getValue(it) })
    }

    @Test
    fun `setup with unit water meters and parking creates them all at once`() = runBlocking {
        val w = world()
        val units = listOf(BuildingRepository.UnitDraft("۱-۱", 1, 6_000, 2, parking = 1), BuildingRepository.UnitDraft("۱-۲", 1, 8_000, 3, parking = 2),
            BuildingRepository.UnitDraft("۲-۱", 2, 0, 1))
        val b = w.r.buildings.setup("کنتوری", "", true, 1000, units, 0, 0, BuildingRepository.ChargePlan.Fixed(BuildingRepository.FixedBasis.PER_UNIT, 1_000_000),
            BuildingRepository.SharedBills(BuildingRepository.WaterSetup.METERED, gas = true))
        val ids = w.db.buildingDao().units(b).sortedBy { it.sortOrder }.map { it.id }
        val meters = w.db.waterDao().meters(b)
        assertEquals(1, meters.count { it.role == com.github.opscalehub.dong.domain.model.MeterRole.MAIN })
        assertEquals(ids.toSet(), meters.mapNotNull { it.unitId }.toSet())
        val cats = w.db.expenseDao().categories(b).associateBy { it.name }
        // Metered water is charged by reading even with a fixed monthly charge; gas without areas goes equally.
        assertEquals(METERED_WATER, cats.getValue("آب").policy())
        assertTrue(cats.getValue("گاز").policy().fundOnly)
        assertTrue("برق مشاعات" in cats)
        val spaces = w.db.buildingDao().spaces(b)
        assertEquals(listOf("۱", "۲", "۳"), spaces.map { it.label }.sorted())
        val open = w.db.buildingDao().assignments(b).filter { it.effectiveTo == null }
        assertEquals(listOf(1, 2, 0), ids.map { id -> open.count { it.unitId == id } })
        // Running the generator again adds nothing.
        assertEquals(0, w.r.water.generateMeters(b))
    }

    @Test
    fun `a building whose units pay their own water has no water type and gets meters later`() = runBlocking {
        val w = world()
        val b = w.r.buildings.setup("بی‌آب", "", true, 1000, drafts, 0, 0, BuildingRepository.ChargePlan.SplitBills,
            BuildingRepository.SharedBills(BuildingRepository.WaterSetup.OWN))
        assertTrue(w.db.expenseDao().categories(b).none { it.isWater })
        assertEquals(3, w.r.water.generateMeters(b)) // main + two units
        assertEquals(METERED_WATER, w.db.expenseDao().categories(b).single { it.isWater }.policy())
    }

    @Test
    fun `parking count follows a sale from one unit to another`() = runBlocking {
        val w = world()
        val b = w.r.buildings.setup("پارکینگ", "", true, 1000, drafts.mapIndexed { i, d -> d.copy(parking = if (i == 0) 2 else 0) }, 0, 0)
        val (a, c) = w.db.buildingDao().units(b).sortedBy { it.sortOrder }.map { it.id }
        val day = w.clock.today()
        w.r.buildings.setParkingCount(a, 1, day)   // sells one…
        w.r.buildings.setParkingCount(c, 1, day)   // …and the neighbour buys it: the freed space is reused
        assertEquals(2, w.db.buildingDao().spaces(b).size)
        val open = w.db.buildingDao().assignments(b).filter { it.effectiveTo == null }
        assertEquals(1, open.count { it.unitId == a })
        assertEquals(1, open.count { it.unitId == c })
        w.r.buildings.setParkingCount(c, 2, day)
        assertEquals(listOf("۱", "۲", "۳"), w.db.buildingDao().spaces(b).map { it.label }.sorted())
        expectError(OpError.INVALID_INPUT) { w.r.buildings.setParkingCount(c, -1, day) }
    }
}
