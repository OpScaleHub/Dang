package com.github.opscalehub.dong.data

import com.github.opscalehub.dong.data.db.BillShareEntity
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.share.Bill

/** What a month summary for the building's group or channel shows. */
data class MonthSummary(
    val building: String,
    val period: String,
    val billed: Long,
    val collected: Long,
    /** Expenses of the month by category name, largest first. */
    val expenses: List<Pair<String, Long>>,
    /** Per unit: label, this month's charge, current balance (> 0 owed). */
    val units: List<Triple<String, Long, Long>>,
    val cash: Long,
    val reserve: Long,
    val date: String,
)

/** Bills per unit, whether they were sent, and the month summary. */
class BillRepository(private val r: Repos) {
    private val reports = ReportRepository(r)
    private val dao get() = r.db.periodDao()

    /** Records that the bill of a closed [periodId] went to [unitId] (one record per hand-over). */
    suspend fun markSent(periodId: String, unitId: String, via: String) = r.tx {
        val p = dao.period(periodId) ?: fail(OpError.NOT_FOUND)
        if (p.status != PeriodStatus.FINALIZED) fail(OpError.PERIOD_NOT_FINALIZED)
        val u = r.db.buildingDao().unit(unitId) ?: fail(OpError.NOT_FOUND)
        if (u.buildingId != p.buildingId) fail(OpError.INVALID_INPUT)
        dao.insertShare(BillShareEntity(newId(), p.buildingId, periodId, unitId, r.clock.now(), via.take(100)))
    }

    suspend fun unmarkSent(periodId: String, unitId: String) = dao.deleteShares(periodId, unitId)

    /** The bill of [unitId] for [periodId] (charges of that month), or a balance-only bill when null. */
    suspend fun bill(buildingId: String, unitId: String, periodId: String?): Bill {
        val d = reports.load(buildingId)
        val b = r.db.buildingDao().building(buildingId) ?: fail(OpError.NOT_FOUND)
        val unit = d.units.firstOrNull { it.id == unitId } ?: fail(OpError.NOT_FOUND)
        val p = periodId?.let { id -> d.periods.firstOrNull { it.id == id } ?: fail(OpError.NOT_FOUND) }
        val total = r.finance.unitStatement(buildingId, unitId)
        val since = p?.let { r.finance.unitStatement(buildingId, unitId, it.startDate, null) }
        val lines = p?.let { Insights.unitComposition(d, unitId, it.id).map { (c, v) -> d.categoryName(c) to v } }.orEmpty()
        val month = lines.sumOf { it.second }
        return Bill(b.name, unit.label, p?.name, lines, since?.opening ?: 0, since?.payments ?: 0, total.closing, Fmt.date(r.clock.today(), b.calendar),
            later = since?.let { it.charges - month } ?: 0,
            adjust = since?.let { it.refunds - it.creditsAndAdjustments } ?: 0)
    }

    suspend fun monthSummary(buildingId: String, periodId: String): MonthSummary {
        val d = reports.load(buildingId)
        val b = r.db.buildingDao().building(buildingId) ?: fail(OpError.NOT_FOUND)
        val p = d.periods.firstOrNull { it.id == periodId } ?: fail(OpError.NOT_FOUND)
        val c = Insights.collection(d, p.id)
        val charges = d.periodResults[p.id]?.lines.orEmpty().groupBy { it.unitId }.mapValues { (_, l) -> l.sumOf { it.amount } }
        val reserve = d.funds.filter { it.kind == FundKind.RESERVE }.sumOf { d.balances.funds[it.id] ?: 0 }
        return MonthSummary(
            building = b.name, period = p.name, billed = c.billed, collected = c.collected,
            expenses = Insights.expensesByCategory(d, p).filter { it.amount > 0 }.map { d.categoryName(it.categoryId) to it.amount },
            units = d.units.filter { (charges[it.id] ?: 0) != 0L }.map { Triple(it.label, charges[it.id] ?: 0, d.balances.units[it.id] ?: 0) },
            cash = d.balances.cashTotal, reserve = reserve, date = Fmt.date(r.clock.today(), b.calendar),
        )
    }
}

/** Units that were charged in [periodId] (the ones that get a bill). */
fun billedUnits(d: ReportData, periodId: String): Set<String> =
    d.periodResults[periodId]?.lines.orEmpty().filter { it.amount != 0L }.mapNotNull { it.unitId }.toSet()
