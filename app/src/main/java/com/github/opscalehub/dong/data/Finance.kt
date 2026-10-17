package com.github.opscalehub.dong.data

import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.data.db.CategoryEntity
import com.github.opscalehub.dong.data.db.ChargeItemEntity
import com.github.opscalehub.dong.data.db.ExpenseEntity
import com.github.opscalehub.dong.data.db.ExpenseStatus
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.JournalEntryEntity
import com.github.opscalehub.dong.data.db.PeriodEntity
import com.github.opscalehub.dong.data.db.PeriodRevisionEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.data.db.SupplierEntity
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.ledger.AccountKind
import com.github.opscalehub.dong.domain.ledger.Balances
import com.github.opscalehub.dong.domain.ledger.EntryType
import com.github.opscalehub.dong.domain.ledger.LedgerException
import com.github.opscalehub.dong.domain.ledger.PostingRules
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.domain.model.PeriodInput
import com.github.opscalehub.dong.domain.period.ChargeLine
import com.github.opscalehub.dong.domain.period.PeriodCalculator
import com.github.opscalehub.dong.domain.period.PeriodCorrection
import com.github.opscalehub.dong.domain.period.PeriodResult
import com.github.opscalehub.dong.domain.period.PeriodSnapshot

private inline fun <T> ledger(block: () -> T): T = try {
    block()
} catch (e: LedgerException) {
    fail(OpError.INVALID_AMOUNT, e.message ?: "")
}

class ExpenseRepository(private val r: Repos) {
    private val dao get() = r.db.expenseDao()

    suspend fun saveCategory(c: CategoryEntity): String = r.tx {
        if (c.name.isBlank()) fail(OpError.INVALID_INPUT)
        AllocationPolicy.decode(c.defaultPolicyJson) // validates JSON
        dao.upsertCategory(c.copy(name = c.name.trim()))
        // A category's default policy only seeds new expenses; existing expenses keep their own snapshot.
        r.journal.audit(c.buildingId, "SAVE_CATEGORY", "category", c.id, c.name)
        c.id
    }

    suspend fun saveSupplier(s: SupplierEntity): String = r.tx {
        if (s.name.isBlank()) fail(OpError.INVALID_INPUT)
        dao.upsertSupplier(s.copy(name = s.name.trim()))
        s.id
    }

    private suspend fun requireDraftPeriod(periodId: String?) {
        if (periodId == null) return
        val p = r.db.periodDao().period(periodId) ?: fail(OpError.NOT_FOUND, "period")
        if (p.status == PeriodStatus.FINALIZED) fail(OpError.PERIOD_FINALIZED)
    }

    /** Records an expense (incurred → payable). Optionally pays it at once from [payFromCashId]. */
    suspend fun create(e: ExpenseEntity, payFromCashId: String? = null, paymentReference: String = ""): String = r.tx {
        if (e.amountMinor == 0L) fail(OpError.INVALID_AMOUNT)
        if (e.description.isBlank()) fail(OpError.INVALID_INPUT, "description")
        dao.category(e.categoryId) ?: fail(OpError.NOT_FOUND, "category")
        AllocationPolicy.decode(e.policyJson)
        requireDraftPeriod(e.periodId)
        val entry = ledger {
            PostingRules.expenseIncurred(e.amountMinor, e.fundId, e.categoryId, e.supplierId)
        }.let { r.journal.post(e.buildingId, EntryType.EXPENSE, e.date, it, e.description, e.documentRef, e.periodId, e.id, supplierId = e.supplierId) }
        dao.insertExpense(e.copy(journalEntryId = entry, status = ExpenseStatus.ACTIVE, createdAt = r.clock.now()))
        if (payFromCashId != null && e.amountMinor > 0) paySupplier(e.id, e.amountMinor, payFromCashId, e.date, paymentReference)
        e.id
    }

    /** Edits a draft expense. Financial changes reverse the old journal entry and post a new one. */
    suspend fun update(e: ExpenseEntity, reason: String = ""): Unit = r.tx {
        val old = dao.expense(e.id) ?: fail(OpError.NOT_FOUND)
        if (old.status != ExpenseStatus.ACTIVE) fail(OpError.INVALID_INPUT, "voided")
        requireDraftPeriod(old.periodId)
        requireDraftPeriod(e.periodId)
        if (e.amountMinor == 0L || e.description.isBlank()) fail(OpError.INVALID_INPUT)
        AllocationPolicy.decode(e.policyJson)
        val financial = old.amountMinor != e.amountMinor || old.fundId != e.fundId || old.categoryId != e.categoryId ||
            old.supplierId != e.supplierId || old.date != e.date
        var entryId = old.journalEntryId
        if (financial) {
            if (old.supplierId != e.supplierId && paid(old.id) != 0L) fail(OpError.HAS_PAYMENTS)
            if (e.amountMinor in 1 until paid(old.id)) fail(OpError.HAS_PAYMENTS)
            old.journalEntryId?.let { reverseInternal(it, "ویرایش هزینه: $reason", e.id) }
            entryId = r.journal.post(e.buildingId, EntryType.EXPENSE, e.date,
                ledger { PostingRules.expenseIncurred(e.amountMinor, e.fundId, e.categoryId, e.supplierId) },
                e.description, e.documentRef, e.periodId, e.id, supplierId = e.supplierId)
        }
        dao.updateExpense(e.copy(journalEntryId = entryId, status = old.status, createdAt = old.createdAt))
        r.journal.audit(e.buildingId, "UPDATE_EXPENSE", "expense", e.id, "${e.description}: مبلغ ${Fmt.money(old.amountMinor)} ← ${Fmt.money(e.amountMinor)} $reason")
    }

    /** Voids a draft expense (reverses its journal entry). Payments must be reversed first. */
    suspend fun void(expenseId: String, reason: String) = r.tx {
        if (reason.isBlank()) fail(OpError.REASON_REQUIRED)
        val e = dao.expense(expenseId) ?: fail(OpError.NOT_FOUND)
        if (e.status == ExpenseStatus.VOIDED) fail(OpError.ALREADY_REVERSED)
        requireDraftPeriod(e.periodId)
        if (paid(expenseId) != 0L) fail(OpError.HAS_PAYMENTS)
        e.journalEntryId?.let { reverseInternal(it, "ابطال هزینه: $reason", e.id) }
        dao.updateExpense(e.copy(status = ExpenseStatus.VOIDED))
        r.journal.audit(e.buildingId, "VOID_EXPENSE", "expense", e.id, reason)
    }

    private suspend fun reverseInternal(entryId: String, reason: String, expenseId: String?) {
        val fin = r.db.financeDao()
        val entry = fin.entry(entryId) ?: fail(OpError.NOT_FOUND)
        if (fin.reversalCount(entryId) > 0) fail(OpError.ALREADY_REVERSED)
        r.journal.post(entry.buildingId, EntryType.REVERSAL, r.clock.today(), PostingRules.reversal(fin.linesOf(entryId).map { it.toPosting() }),
            reason, entry.reference, entry.periodId, expenseId ?: entry.expenseId, entry.unitId, entry.supplierId, entryId)
    }

    /** Amount paid to the supplier against this expense (payments minus reversed payments). */
    suspend fun paid(expenseId: String): Long {
        val fin = r.db.financeDao()
        val entries = fin.entriesForExpense(expenseId)
        val paymentIds = entries.filter { it.type == EntryType.SUPPLIER_PAYMENT }.map { it.id }.toSet()
        return entries.filter { it.id in paymentIds || (it.type == EntryType.REVERSAL && it.reversesEntryId in paymentIds) }
            .sumOf { e -> fin.linesOf(e.id).filter { it.account == AccountKind.PAYABLE }.sumOf { it.amountMinor } }
    }

    /** Pays a supplier, against an expense or (expenseId = null) against the supplier's general balance. */
    suspend fun paySupplier(expenseId: String?, amount: Long, cashAccountId: String, date: Long, reference: String = "", supplierId: String? = null, buildingId: String? = null): String = r.tx {
        if (amount <= 0) fail(OpError.INVALID_AMOUNT)
        val e = expenseId?.let { dao.expense(it) ?: fail(OpError.NOT_FOUND) }
        if (e != null) {
            if (e.status != ExpenseStatus.ACTIVE) fail(OpError.INVALID_INPUT, "voided")
            if (paid(e.id) + amount > e.amountMinor) fail(OpError.INVALID_AMOUNT, "overpayment")
        }
        val b = e?.buildingId ?: buildingId ?: fail(OpError.INVALID_INPUT)
        val sup = e?.supplierId ?: supplierId
        r.journal.post(b, EntryType.SUPPLIER_PAYMENT, date, ledger { PostingRules.supplierPayment(amount, sup, cashAccountId) },
            e?.description ?: "پرداخت به تامین‌کننده", reference, e?.periodId, e?.id, supplierId = sup)
    }

    suspend fun saveChargeItem(c: ChargeItemEntity): String = r.tx {
        if (c.name.isBlank() || c.amountMinor < 0) fail(OpError.INVALID_INPUT)
        if (c.mode == ChargeMode.WATER_TARIFF && c.amountMinor != 0L) fail(OpError.INVALID_INPUT)
        AllocationPolicy.decode(c.policyJson)
        dao.upsertChargeItem(c.copy(name = c.name.trim()))
        r.journal.audit(c.buildingId, "SAVE_CHARGE_ITEM", "charge_item", c.id, "${c.name}: ${Fa.chargeMode(c.mode)} ${Fmt.money(c.amountMinor)}")
        c.id
    }
}

/** Unit statement row; [amount] uses the receivable sign (+ charge, − payment/credit). */
data class StatementRow(val entryId: String, val date: Long, val type: EntryType, val memo: String, val categoryId: String?, val amount: Long, val balance: Long)

data class UnitStatement(
    val unitId: String,
    val opening: Long,
    val charges: Long,
    val waterCharges: Long,
    val otherCharges: Long,
    val payments: Long,
    val creditsAndAdjustments: Long,
    val refunds: Long,
    val closing: Long,
    val rows: List<StatementRow>,
)

data class BuildingBalances(
    val units: Map<String, Long>,
    val cash: Map<String, Long>,
    val funds: Map<String, Long>,
    val suppliers: Map<String?, Long>,
    val outstandingDebts: Long,
    val residentCredits: Long,
    val payablesTotal: Long,
    val cashTotal: Long,
    val trialBalance: Long,
    val netAssets: Long,
    val fundsTotal: Long,
) {
    /** Invariants I2 and I3 of docs/02. */
    val reconciles: Boolean get() = trialBalance == 0L && netAssets == fundsTotal
}

class FinanceRepository(private val r: Repos) {
    private val fin get() = r.db.financeDao()

    suspend fun addFund(buildingId: String, name: String, kind: FundKind): String = r.tx {
        if (name.isBlank() || kind == FundKind.OPERATING) fail(OpError.INVALID_INPUT)
        val id = newId()
        fin.upsertFund(com.github.opscalehub.dong.data.db.FundEntity(id, buildingId, name.trim(), kind))
        r.journal.audit(buildingId, "ADD_FUND", "fund", id, name)
        id
    }

    suspend fun addCashAccount(buildingId: String, name: String, kind: com.github.opscalehub.dong.data.db.CashKind): String = r.tx {
        if (name.isBlank()) fail(OpError.INVALID_INPUT)
        val id = newId()
        fin.upsertCashAccount(com.github.opscalehub.dong.data.db.CashAccountEntity(id, buildingId, name.trim(), kind))
        r.journal.audit(buildingId, "ADD_CASH_ACCOUNT", "cash_account", id, name)
        id
    }

    suspend fun operatingFundId(buildingId: String): String =
        fin.funds(buildingId).firstOrNull { it.kind == FundKind.OPERATING }?.id ?: fail(OpError.NOT_FOUND, "operating fund")

    /**
     * Opening balances; the operating fund is the balancing figure:
     * cash + unit balances − supplier balances − Σ other funds.
     */
    suspend fun postOpening(buildingId: String, date: Long, cash: Map<String, Long>, units: Map<String, Long>, suppliers: Map<String?, Long>, otherFunds: Map<String, Long>): String = r.tx {
        val existing = fin.entries(buildingId).filter { it.type == EntryType.OPENING }
        if (existing.any { e -> fin.reversalCount(e.id) == 0 }) fail(OpError.OPENING_EXISTS)
        val op = operatingFundId(buildingId)
        if (op in otherFunds) fail(OpError.INVALID_INPUT)
        val opAmount = cash.values.sum() + units.values.sum() - suppliers.values.sum() - otherFunds.values.sum()
        val postings = ledger {
            PostingRules.opening(cash.filterValues { it != 0L }, units.filterValues { it != 0L }, suppliers.filterValues { it != 0L },
                (otherFunds + (op to opAmount)).filterValues { it != 0L })
        }
        r.journal.post(buildingId, EntryType.OPENING, date, postings, "مانده‌های افتتاحیه")
    }

    suspend fun receivePayment(buildingId: String, unitId: String, amount: Long, cashAccountId: String, date: Long, reference: String = "", memo: String = "دریافت از واحد", method: String = ""): String = r.tx {
        r.journal.post(buildingId, EntryType.RESIDENT_PAYMENT, date, ledger { PostingRules.residentPayment(amount, unitId, cashAccountId) }, memo, reference, unitId = unitId, method = method)
    }

    suspend fun refund(buildingId: String, unitId: String, amount: Long, cashAccountId: String, date: Long, reason: String): String = r.tx {
        if (reason.isBlank()) fail(OpError.REASON_REQUIRED)
        r.journal.post(buildingId, EntryType.REFUND, date, ledger { PostingRules.refund(amount, unitId, cashAccountId) }, reason, unitId = unitId)
    }

    /** [amount] > 0 increases what the unit owes; < 0 credits the unit. A reason is mandatory. */
    suspend fun adjust(buildingId: String, unitId: String, amount: Long, fundId: String, categoryId: String?, date: Long, reason: String, periodId: String? = null): String = r.tx {
        if (reason.isBlank()) fail(OpError.REASON_REQUIRED)
        r.journal.post(buildingId, EntryType.ADJUSTMENT, date, ledger { PostingRules.adjustment(amount, unitId, fundId, categoryId) }, reason, periodId = periodId, unitId = unitId)
    }

    suspend fun transferFunds(buildingId: String, fromFund: String, toFund: String, amount: Long, date: Long, memo: String): String = r.tx {
        r.journal.post(buildingId, EntryType.FUND_TRANSFER, date, ledger { PostingRules.fundTransfer(amount, fromFund, toFund) }, memo.ifBlank { "انتقال بین صندوق‌ها" })
    }

    suspend fun transferCash(buildingId: String, fromCash: String, toCash: String, amount: Long, date: Long, memo: String): String = r.tx {
        r.journal.post(buildingId, EntryType.CASH_TRANSFER, date, ledger { PostingRules.cashTransfer(amount, fromCash, toCash) }, memo.ifBlank { "انتقال وجه" })
    }

    private val reversible = setOf(
        EntryType.OPENING, EntryType.SUPPLIER_PAYMENT, EntryType.RESIDENT_PAYMENT, EntryType.REFUND,
        EntryType.ADJUSTMENT, EntryType.FUND_TRANSFER, EntryType.CASH_TRANSFER,
    )

    /**
     * Reverses a posted entry with a linked, negated entry. Charges and corrections of finalized
     * periods cannot be reversed here (use a period correction); expenses are reversed by voiding.
     */
    suspend fun reverse(entryId: String, reason: String): String = r.tx {
        if (reason.isBlank()) fail(OpError.REASON_REQUIRED)
        val e = fin.entry(entryId) ?: fail(OpError.NOT_FOUND)
        if (e.type !in reversible) fail(OpError.CANNOT_REVERSE)
        if (fin.reversalCount(entryId) > 0) fail(OpError.ALREADY_REVERSED)
        r.journal.post(e.buildingId, EntryType.REVERSAL, r.clock.today(), PostingRules.reversal(fin.linesOf(entryId).map { it.toPosting() }),
            "برگشت: $reason", e.reference, e.periodId, e.expenseId, e.unitId, e.supplierId, entryId)
    }

    suspend fun isReversed(entryId: String) = fin.reversalCount(entryId) > 0

    suspend fun balances(buildingId: String): BuildingBalances {
        val lines = fin.lines(buildingId).map { it.toPosting() }
        return balancesOf(buildingId, lines)
    }

    suspend fun balancesOf(buildingId: String, lines: List<com.github.opscalehub.dong.domain.ledger.Posting>): BuildingBalances {
        val b = Balances(lines)
        val units = r.db.buildingDao().units(buildingId).associate { it.id to b.unit(it.id) }
        val funds = fin.funds(buildingId).associate { it.id to b.fund(it.id) }
        val cash = fin.cashAccounts(buildingId).associate { it.id to b.cash(it.id) }
        val suppliers = lines.filter { it.account == AccountKind.PAYABLE }.map { it.supplierId }.distinct().associateWith { b.payable(it, all = false) }
        return BuildingBalances(
            units, cash, funds, suppliers,
            outstandingDebts = units.values.filter { it > 0 }.sum(),
            residentCredits = -units.values.filter { it < 0 }.sum(),
            payablesTotal = b.payable(), cashTotal = b.cash(), trialBalance = b.trialBalance,
            netAssets = b.netAssets, fundsTotal = funds.values.sum(),
        )
    }

    /** Statement of a unit's receivable over [from]..[to] (inclusive epoch days; null = open). */
    suspend fun unitStatement(buildingId: String, unitId: String, from: Long? = null, to: Long? = null): UnitStatement {
        val entries = fin.entries(buildingId).associateBy { it.id }
        val waterCats = r.db.expenseDao().categories(buildingId).filter { it.isWater }.map { it.id }.toSet()
        val lines = fin.lines(buildingId).filter { it.account == AccountKind.RECEIVABLE && it.unitId == unitId }
            .map { it to entries.getValue(it.entryId) }
            .sortedWith(compareBy({ it.second.entryDate }, { it.second.createdAt }))
        val before = lines.filter { from != null && it.second.entryDate < from }
        val inRange = lines.filter { (from == null || it.second.entryDate >= from) && (to == null || it.second.entryDate <= to) }
        val opening = before.sumOf { it.first.amountMinor } + inRange.filter { it.second.type == EntryType.OPENING }.sumOf { it.first.amountMinor }
        var bal = before.sumOf { it.first.amountMinor }
        val rows = inRange.map { (l, e) ->
            bal += l.amountMinor
            StatementRow(e.id, e.entryDate, e.type, e.memo, l.categoryId, l.amountMinor, bal)
        }
        fun sum(pred: (Pair<com.github.opscalehub.dong.data.db.JournalLineEntity, JournalEntryEntity>) -> Boolean) = inRange.filter(pred).sumOf { it.first.amountMinor }
        val chargeTypes = setOf(EntryType.CHARGE, EntryType.PERIOD_CORRECTION)
        val water = sum { it.second.type in chargeTypes && it.first.categoryId in waterCats }
        val charges = sum { it.second.type in chargeTypes }
        val payments = -sum { it.second.type == EntryType.RESIDENT_PAYMENT || (it.second.type == EntryType.REVERSAL && entries[it.second.reversesEntryId]?.type == EntryType.RESIDENT_PAYMENT) }
        val refunds = sum { it.second.type == EntryType.REFUND || (it.second.type == EntryType.REVERSAL && entries[it.second.reversesEntryId]?.type == EntryType.REFUND) }
        val adjustments = -sum { it.second.type == EntryType.ADJUSTMENT || (it.second.type == EntryType.REVERSAL && entries[it.second.reversesEntryId]?.type in setOf(EntryType.ADJUSTMENT, EntryType.OPENING)) }
        val closing = bal
        check(opening + charges - payments - adjustments + refunds == closing) { "statement does not reconcile" }
        return UnitStatement(unitId, opening, charges, water, charges - water, payments, adjustments, refunds, closing, rows)
    }
}

class PeriodRepository(private val r: Repos) {
    private val dao get() = r.db.periodDao()

    suspend fun create(buildingId: String, name: String, start: Long, end: Long, due: Long? = null): String = r.tx {
        if (name.isBlank() || end < start) fail(OpError.INVALID_INPUT)
        if (dao.periods(buildingId).any { start <= it.endDate && end >= it.startDate }) fail(OpError.PERIOD_OVERLAP)
        val id = newId()
        dao.insertPeriod(PeriodEntity(id, buildingId, name.trim(), start, end, due, createdAt = r.clock.now()))
        r.journal.audit(buildingId, "CREATE_PERIOD", "period", id, "$name: ${Fmt.date(start)} تا ${Fmt.date(end)}")
        id
    }

    suspend fun updateDraft(p: PeriodEntity) = r.tx {
        val old = dao.period(p.id) ?: fail(OpError.NOT_FOUND)
        if (old.status != PeriodStatus.DRAFT) fail(OpError.PERIOD_FINALIZED)
        if (p.name.isBlank() || p.endDate < p.startDate) fail(OpError.INVALID_INPUT)
        if (dao.periods(p.buildingId).any { it.id != p.id && p.startDate <= it.endDate && p.endDate >= it.startDate }) fail(OpError.PERIOD_OVERLAP)
        dao.updatePeriod(old.copy(name = p.name.trim(), startDate = p.startDate, endDate = p.endDate, dueDate = p.dueDate))
    }

    suspend fun deleteDraft(periodId: String) = r.tx {
        val p = dao.period(periodId) ?: fail(OpError.NOT_FOUND)
        if (p.status != PeriodStatus.DRAFT) fail(OpError.PERIOD_FINALIZED)
        r.db.expenseDao().activeExpensesForPeriod(periodId).forEach { r.db.expenseDao().updateExpense(it.copy(periodId = null)) }
        dao.deleteDraft(periodId)
        r.journal.audit(p.buildingId, "DELETE_PERIOD", "period", p.id, p.name)
    }

    /** The draft period containing [date], if any (default period for new expenses). */
    suspend fun draftFor(buildingId: String, date: Long): PeriodEntity? =
        dao.periods(buildingId).firstOrNull { it.status == PeriodStatus.DRAFT && date in it.startDate..it.endDate }

    /** Assembles the complete calculation input for a period from current data. */
    suspend fun buildInput(periodId: String): PeriodInput {
        val p = dao.period(periodId) ?: fail(OpError.NOT_FOUND)
        val db = r.db
        val b = db.buildingDao().building(p.buildingId) ?: fail(OpError.NOT_FOUND)
        val items = db.expenseDao().chargeItems(p.buildingId).filter { it.active && it.activeFrom <= p.endDate && (it.activeTo == null || it.activeTo >= p.startDate) }
        return PeriodInput(
            periodId = p.id, startDate = p.startDate, endDate = p.endDate, settings = b.settings(),
            units = db.buildingDao().units(p.buildingId).map { it.toDomain() },
            occupancy = db.buildingDao().occupancy(p.buildingId).map { it.toDomain() },
            spaces = db.buildingDao().spaces(p.buildingId).map { it.toDomain() },
            assignments = db.buildingDao().assignments(p.buildingId).map { it.toDomain() },
            meters = db.waterDao().meters(p.buildingId).map { it.toDomain() },
            readings = db.waterDao().readings(p.buildingId).map { it.toDomain() },
            categories = db.expenseDao().categories(p.buildingId).map { it.toDomain() },
            expenses = db.expenseDao().activeExpensesForPeriod(p.id).map { it.toDomain() },
            chargeItems = items.map { it.toDomain() },
            tariffs = db.waterDao().tariffs(p.buildingId).map { it.toDomain() },
        )
    }

    /** Draft: live calculation. Finalized: the stored snapshot result, exactly as finalized. */
    suspend fun preview(periodId: String): PeriodResult {
        val p = dao.period(periodId) ?: fail(OpError.NOT_FOUND)
        return if (p.status == PeriodStatus.FINALIZED) PeriodSnapshot.decode(p.snapshotJson!!).result else PeriodCalculator.calculate(buildInput(periodId))
    }

    suspend fun snapshot(periodId: String): PeriodSnapshot? = dao.period(periodId)?.snapshotJson?.let { PeriodSnapshot.decode(it) }

    /** Validates, stores the immutable snapshot and posts the CHARGE entry — atomically. */
    suspend fun finalize(periodId: String): PeriodResult = r.tx {
        val p = dao.period(periodId) ?: fail(OpError.NOT_FOUND)
        if (p.status == PeriodStatus.FINALIZED) fail(OpError.PERIOD_FINALIZED)
        val input = buildInput(periodId)
        val result = PeriodCalculator.calculate(input)
        if (!result.canFinalize) fail(OpError.PERIOD_HAS_BLOCKING_ISSUES, result.blockingIssues.joinToString { it.code.name })
        val entryId = if (result.lines.isEmpty()) null else r.journal.post(
            p.buildingId, EntryType.CHARGE, p.endDate, PostingRules.charges(result.lines), "شارژ دوره ${p.name}", periodId = p.id,
        )
        val snap = PeriodSnapshot(calculatedAt = r.clock.now(), input = input, result = result)
        dao.updatePeriod(p.copy(status = PeriodStatus.FINALIZED, finalizedAt = r.clock.now(), snapshotJson = snap.encode(), chargeEntryId = entryId))
        r.journal.audit(p.buildingId, "FINALIZE_PERIOD", "period", p.id, "${p.name}: ${Fmt.number(result.lines.size.toLong())} ردیف شارژ، جمع ${Fmt.money(result.total)}")
        result
    }

    /** Lines currently in effect for a finalized period: the latest revision's result, else the original snapshot. */
    suspend fun effectiveResult(periodId: String): PeriodResult {
        val p = dao.period(periodId) ?: fail(OpError.NOT_FOUND)
        if (p.status != PeriodStatus.FINALIZED) fail(OpError.PERIOD_NOT_FINALIZED)
        val last = dao.revisions(periodId).maxByOrNull { it.revision }
        return PeriodSnapshot.decode(last?.snapshotJson ?: p.snapshotJson!!).result
    }

    data class CorrectionPreview(val input: PeriodInput, val result: PeriodResult, val diff: List<ChargeLine>)

    /**
     * Recalculates a finalized period with its snapshotted rules (settings, expenses, policies,
     * charge items, tariffs) but current units, occupancy, parking and meter readings — the inputs
     * that can be legitimately corrected after the fact.
     */
    suspend fun previewCorrection(periodId: String): CorrectionPreview {
        val p = dao.period(periodId) ?: fail(OpError.NOT_FOUND)
        if (p.status != PeriodStatus.FINALIZED) fail(OpError.PERIOD_NOT_FINALIZED)
        val original = PeriodSnapshot.decode(p.snapshotJson!!).input
        val current = buildInput(periodId)
        val input = original.copy(
            units = current.units, occupancy = current.occupancy, spaces = current.spaces,
            assignments = current.assignments, meters = current.meters, readings = current.readings,
        )
        val result = PeriodCalculator.calculate(input)
        return CorrectionPreview(input, result, PeriodCorrection.diff(effectiveResult(periodId).lines, result.lines))
    }

    suspend fun applyCorrection(periodId: String, reason: String): String = r.tx {
        if (reason.isBlank()) fail(OpError.REASON_REQUIRED)
        val p = dao.period(periodId) ?: fail(OpError.NOT_FOUND)
        val prev = previewCorrection(periodId)
        if (!prev.result.canFinalize) fail(OpError.PERIOD_HAS_BLOCKING_ISSUES, prev.result.blockingIssues.joinToString { it.code.name })
        if (prev.diff.isEmpty()) fail(OpError.NOTHING_TO_CORRECT)
        val postings = prev.diff.flatMap { l ->
            listOf(
                com.github.opscalehub.dong.domain.ledger.Posting(AccountKind.RECEIVABLE, l.amount, unitId = l.unitId, fundId = l.fundId, categoryId = l.categoryId),
                com.github.opscalehub.dong.domain.ledger.Posting(AccountKind.INCOME, -l.amount, unitId = l.unitId, fundId = l.fundId, categoryId = l.categoryId),
            )
        }
        val revision = (dao.revisions(periodId).maxOfOrNull { it.revision } ?: 0) + 1
        val entryId = r.journal.post(p.buildingId, EntryType.PERIOD_CORRECTION, r.clock.today(), postings, "اصلاحیه $revision دوره ${p.name}: $reason", periodId = p.id)
        val snap = PeriodSnapshot(calculatedAt = r.clock.now(), input = prev.input, result = prev.result)
        val id = newId()
        dao.insertRevision(PeriodRevisionEntity(id, periodId, revision, r.clock.now(), reason, snap.encode(), entryId))
        r.journal.audit(p.buildingId, "CORRECT_PERIOD", "period", p.id, "اصلاحیه ${Fmt.number(revision.toLong())}: $reason (${Fmt.number(prev.diff.size.toLong())} ردیف)")
        id
    }
}
