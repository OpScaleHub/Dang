package com.github.opscalehub.dong.data

import com.github.opscalehub.dong.data.db.ExpenseStatus
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.JournalEntryEntity
import com.github.opscalehub.dong.data.db.PeriodEntity
import com.github.opscalehub.dong.domain.ledger.AccountKind
import com.github.opscalehub.dong.domain.ledger.EntryType

/**
 * Reporting math over [ReportData]. Pure functions (no Android, no DB) so every
 * number shown in a chart is unit-tested. Reversed entries and their reversals
 * are removed in pairs first, so a cancelled payment is neither a payment nor a
 * new charge.
 */
object Insights {

    /** Entries that count: not reversed and not reversals. */
    private fun effectiveEntries(d: ReportData): Map<String, JournalEntryEntity> {
        val reversed = d.entries.mapNotNull { it.reversesEntryId }.toSet()
        return d.entries.filter { it.id !in reversed && it.reversesEntryId == null }.associateBy { it.id }
    }

    // ------------------------------------------------------------------ FIFO settlement

    data class Debit(val unitId: String, val date: Long, val amount: Long, val periodId: String?, val categoryId: String?, var open: Long = amount)

    data class Settlement(val debits: List<Debit>, val unappliedCredit: Map<String, Long>) {
        fun settled(periodId: String): Long = debits.filter { it.periodId == periodId }.sumOf { it.amount - it.open }
        fun billed(periodId: String): Long = debits.filter { it.periodId == periodId }.sumOf { it.amount }
        fun openFor(unitId: String): List<Debit> = debits.filter { it.unitId == unitId && it.open > 0 }
    }

    /**
     * Receivable movements per unit in date order; credits settle the oldest open
     * debits first, surplus credit waits for later debits. Debits are charges,
     * debit adjustments, refunds and positive opening balances.
     */
    fun settle(d: ReportData): Settlement {
        val entries = effectiveEntries(d)
        val all = d.lines.filter { it.account == AccountKind.RECEIVABLE && it.entryId in entries && it.unitId != null }
            .map { it to entries.getValue(it.entryId) }
        // A period's charges and its corrections are netted per unit and category first, so a
        // downward correction lowers that period's bill instead of acting like a payment.
        val chargeTypes = setOf(EntryType.CHARGE, EntryType.PERIOD_CORRECTION)
        data class Move(val unitId: String, val date: Long, val createdAt: Long, val amount: Long, val periodId: String?, val categoryId: String?)
        val charges = all.filter { it.second.type in chargeTypes }
            .groupBy { Triple(it.first.unitId!!, it.second.periodId, it.first.categoryId) }
            .map { (k, l) ->
                val first = l.minWith(compareBy({ it.second.entryDate }, { it.second.createdAt })).second
                Move(k.first, first.entryDate, first.createdAt, l.sumOf { it.first.amountMinor }, k.second, k.third)
            }
        val others = all.filter { it.second.type !in chargeTypes }
            .map { (l, e) -> Move(l.unitId!!, e.entryDate, e.createdAt, l.amountMinor, e.periodId, l.categoryId) }
        val moves = (charges + others).filter { it.amount != 0L }
            .sortedWith(compareBy({ it.date }, { it.createdAt }, { -it.amount }))
        val debits = mutableListOf<Debit>()
        val pool = HashMap<String, Long>()
        for (m in moves) {
            val u = m.unitId
            if (m.amount > 0) {
                val deb = Debit(u, m.date, m.amount, m.periodId, m.categoryId)
                val use = minOf(pool[u] ?: 0, deb.open)
                deb.open -= use
                pool[u] = (pool[u] ?: 0) - use
                debits += deb
            } else {
                var credit = -m.amount
                for (deb in debits.filter { it.unitId == u && it.open > 0 }) {
                    if (credit == 0L) break
                    val use = minOf(credit, deb.open)
                    deb.open -= use
                    credit -= use
                }
                if (credit > 0) pool[u] = (pool[u] ?: 0) + credit
            }
        }
        return Settlement(debits, pool.filterValues { it > 0 })
    }

    data class Collection(val billed: Long, val collected: Long) {
        val outstanding: Long get() = billed - collected
        /** 0..1000 (per-mille) to avoid floats in callers. */
        val perMille: Int get() = if (billed <= 0) 0 else ((collected * 1000) / billed).toInt()
    }

    /** How much of a period's charges (incl. its corrections) has been paid, FIFO. */
    fun collection(d: ReportData, periodId: String, s: Settlement = settle(d)) = Collection(s.billed(periodId), s.settled(periodId))

    // ------------------------------------------------------------------ aging

    enum class AgeBucket { CURRENT, DAYS_31_60, DAYS_61_90, OVER_90 }

    data class UnitAging(val unitId: String, val buckets: Map<AgeBucket, Long>, val credit: Long) {
        val total: Long get() = buckets.values.sum()
        val oldestBucket: AgeBucket? get() = buckets.filterValues { it > 0 }.keys.maxOrNull()
    }

    fun bucketOf(ageDays: Long) = when {
        ageDays <= 30 -> AgeBucket.CURRENT
        ageDays <= 60 -> AgeBucket.DAYS_31_60
        ageDays <= 90 -> AgeBucket.DAYS_61_90
        else -> AgeBucket.OVER_90
    }

    /** Open (unpaid) amounts per unit by age of the charge, sorted by total owed. */
    fun aging(d: ReportData, today: Long, s: Settlement = settle(d)): List<UnitAging> =
        d.units.map { u ->
            val b = AgeBucket.entries.associateWith { 0L }.toMutableMap()
            s.openFor(u.id).forEach { deb -> b.merge(bucketOf(today - deb.date), deb.open, Long::plus) }
            UnitAging(u.id, b, s.unappliedCredit[u.id] ?: 0)
        }.sortedByDescending { it.total }

    // ------------------------------------------------------------------ cash flow

    enum class Flow { COLLECTIONS, SUPPLIER_PAYMENTS, REFUNDS, OTHER }

    data class CashFlow(val opening: Long, val flows: Map<Flow, Long>) {
        val closing: Long get() = opening + flows.values.sum()
    }

    /** Cash movement over [start]..[end] by kind; cash-to-cash transfers net to zero and are omitted. */
    fun cashFlow(d: ReportData, start: Long, end: Long): CashFlow {
        val entries = d.entries.associateBy { it.id }
        fun kind(e: JournalEntryEntity): Flow {
            val t = if (e.type == EntryType.REVERSAL) entries[e.reversesEntryId]?.type else e.type
            return when (t) {
                EntryType.RESIDENT_PAYMENT -> Flow.COLLECTIONS
                EntryType.SUPPLIER_PAYMENT -> Flow.SUPPLIER_PAYMENTS
                EntryType.REFUND -> Flow.REFUNDS
                else -> Flow.OTHER
            }
        }
        val cash = d.lines.filter { it.account == AccountKind.CASH }.map { it to entries.getValue(it.entryId) }
        val opening = cash.filter { it.second.entryDate < start }.sumOf { it.first.amountMinor }
        val inRange = cash.filter { it.second.entryDate in start..end && it.second.type != EntryType.CASH_TRANSFER }
        val flows = Flow.entries.associateWith { f -> inRange.filter { kind(it.second) == f }.sumOf { it.first.amountMinor } }
        return CashFlow(opening, flows)
    }

    // ------------------------------------------------------------------ expenses

    data class CategoryAmount(val categoryId: String, val amount: Long, val previous: Long)

    /** Active expenses assigned to [period] by category, with the previous period for comparison. */
    fun expensesByCategory(d: ReportData, period: PeriodEntity): List<CategoryAmount> {
        val prev = d.periods.filter { it.endDate < period.startDate }.maxByOrNull { it.endDate }
        fun sums(p: PeriodEntity?) = d.expenses.filter { p != null && it.periodId == p.id && it.status == ExpenseStatus.ACTIVE }
            .groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }
        val cur = sums(period)
        val old = sums(prev)
        return (cur.keys + old.keys).distinct().map { CategoryAmount(it, cur[it] ?: 0, old[it] ?: 0) }.sortedByDescending { it.amount }
    }

    // ------------------------------------------------------------------ monthly series

    data class MonthPoint(val periodId: String, val name: String, val endDate: Long, val billed: Long, val collected: Long, val expenses: Long, val reserve: Long)

    /** One point per period (oldest first), last [limit] periods. */
    fun monthly(d: ReportData, limit: Int = 12, s: Settlement = settle(d)): List<MonthPoint> {
        val reserveIds = d.funds.filter { it.kind == FundKind.RESERVE }.map { it.id }.toSet()
        val entries = d.entries.associateBy { it.id }
        val fundLines = d.lines.filter { it.fundId in reserveIds && it.account in setOf(AccountKind.FUND_EQUITY, AccountKind.INCOME, AccountKind.EXPENSE) }
        return d.periods.sortedBy { it.startDate }.takeLast(limit).map { p ->
            val reserve = -fundLines.filter { entries.getValue(it.entryId).entryDate <= p.endDate }.sumOf { it.amountMinor }
            val exp = d.expenses.filter { it.periodId == p.id && it.status == ExpenseStatus.ACTIVE }.sumOf { it.amountMinor }
            MonthPoint(p.id, p.name, p.endDate, s.billed(p.id), s.settled(p.id), exp, reserve)
        }
    }

    // ------------------------------------------------------------------ water

    data class WaterView(val unitLiters: Map<String, Long?>, val averageLiters: Long, val mainLiters: Long?, val unexplainedLiters: Long?) {
        /** Unexplained share of the main meter in per-mille, if known. */
        val unexplainedPerMille: Int? get() = if (mainLiters == null || mainLiters <= 0 || unexplainedLiters == null) null else ((unexplainedLiters * 1000) / mainLiters).toInt()
    }

    fun water(d: ReportData, periodId: String): WaterView? {
        val w = d.periodResults[periodId]?.water ?: return null
        if (w.meters.isEmpty()) return null
        val known = w.unitLiters.values.filterNotNull()
        return WaterView(w.unitLiters, if (known.isEmpty()) 0 else known.sum() / known.size, w.mainLiters, w.unallocatedLiters)
    }

    // ------------------------------------------------------------------ unit view

    /** What a unit was charged in a period, by category (effective result, incl. corrections). */
    fun unitComposition(d: ReportData, unitId: String, periodId: String): List<Pair<String, Long>> =
        d.periodResults[periodId]?.lines.orEmpty().filter { it.unitId == unitId }
            .groupBy { it.categoryId }.map { (c, l) -> c to l.sumOf { it.amount } }.filter { it.second != 0L }.sortedByDescending { it.second }

    /** Unit balance at the end of each period (oldest first). */
    fun unitBalanceHistory(d: ReportData, unitId: String, limit: Int = 12): List<Pair<String, Long>> {
        val entries = d.entries.associateBy { it.id }
        val lines = d.lines.filter { it.account == AccountKind.RECEIVABLE && it.unitId == unitId }
        return d.periods.sortedBy { it.startDate }.takeLast(limit).map { p ->
            p.name to lines.filter { entries.getValue(it.entryId).entryDate <= p.endDate }.sumOf { it.amountMinor }
        }
    }
}
