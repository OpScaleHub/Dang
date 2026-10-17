package com.github.opscalehub.dong.data

import com.github.opscalehub.dong.data.db.BuildingEntity
import com.github.opscalehub.dong.data.db.CashAccountEntity
import com.github.opscalehub.dong.data.db.CategoryEntity
import com.github.opscalehub.dong.data.db.ChargeItemEntity
import com.github.opscalehub.dong.data.db.ExpenseEntity
import com.github.opscalehub.dong.data.db.FundEntity
import com.github.opscalehub.dong.data.db.JournalEntryEntity
import com.github.opscalehub.dong.data.db.JournalLineEntity
import com.github.opscalehub.dong.data.db.MeterEntity
import com.github.opscalehub.dong.data.db.MeterReadingEntity
import com.github.opscalehub.dong.data.db.PeriodEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.data.db.SupplierEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.domain.period.PeriodResult

/** Everything the exporters need, loaded once. Pure data; exporters are pure functions of it. */
data class ReportData(
    val building: BuildingEntity,
    val units: List<UnitEntity>,
    /** Current occupant count per unit (latest record). */
    val occupants: Map<String, Int>,
    val funds: List<FundEntity>,
    val cashAccounts: List<CashAccountEntity>,
    val categories: List<CategoryEntity>,
    val suppliers: List<SupplierEntity>,
    val chargeItems: List<ChargeItemEntity>,
    val periods: List<PeriodEntity>,
    /** Draft: live preview; finalized: effective (latest revision or original snapshot). */
    val periodResults: Map<String, PeriodResult>,
    val expenses: List<ExpenseEntity>,
    val expensePaid: Map<String, Long>,
    val entries: List<JournalEntryEntity>,
    val lines: List<JournalLineEntity>,
    val reversedEntryIds: Set<String>,
    val meters: List<MeterEntity>,
    val readings: List<MeterReadingEntity>,
    val balances: BuildingBalances,
    val statements: Map<String, UnitStatement>,
    val generatedAt: Long,
) {
    fun unitLabel(id: String?) = units.firstOrNull { it.id == id }?.label ?: (id ?: "—")
    fun categoryName(id: String?) = categories.firstOrNull { it.id == id }?.name ?: "—"
    fun fundName(id: String?) = funds.firstOrNull { it.id == id }?.name ?: "—"
    fun supplierName(id: String?) = suppliers.firstOrNull { it.id == id }?.name ?: "—"
    fun cashName(id: String?) = cashAccounts.firstOrNull { it.id == id }?.name ?: "—"
    fun sourceName(id: String?) = expenses.firstOrNull { it.id == id }?.description ?: chargeItems.firstOrNull { it.id == id }?.name ?: (id ?: "—")
    fun meterLabel(id: String?) = meters.firstOrNull { it.id == id }?.label ?: (id ?: "—")
}

class ReportRepository(private val r: Repos) {

    suspend fun load(buildingId: String): ReportData {
        val db = r.db
        val b = db.buildingDao().building(buildingId) ?: fail(OpError.NOT_FOUND)
        val units = db.buildingDao().units(buildingId)
        val occ = db.buildingDao().occupancy(buildingId).groupBy { it.unitId }
            .mapValues { (_, l) -> l.filter { it.effectiveFrom <= r.clock.today() }.maxByOrNull { it.effectiveFrom }?.occupantCount ?: 0 }
        val periods = db.periodDao().periods(buildingId)
        val results = periods.associate { p ->
            p.id to (if (p.status == PeriodStatus.FINALIZED) r.periods.effectiveResult(p.id) else r.periods.preview(p.id))
        }
        val expenses = db.expenseDao().expenses(buildingId)
        val entries = db.financeDao().entries(buildingId)
        return ReportData(
            building = b, units = units, occupants = occ,
            funds = db.financeDao().funds(buildingId), cashAccounts = db.financeDao().cashAccounts(buildingId),
            categories = db.expenseDao().categories(buildingId), suppliers = db.expenseDao().suppliers(buildingId),
            chargeItems = db.expenseDao().chargeItems(buildingId),
            periods = periods, periodResults = results, expenses = expenses,
            expensePaid = expenses.associate { it.id to r.expenses.paid(it.id) },
            entries = entries, lines = db.financeDao().lines(buildingId),
            reversedEntryIds = entries.mapNotNull { it.reversesEntryId }.toSet(),
            meters = db.waterDao().meters(buildingId), readings = db.waterDao().readings(buildingId),
            balances = r.finance.balances(buildingId),
            statements = units.associate { it.id to r.finance.unitStatement(buildingId, it.id) },
            generatedAt = r.clock.now(),
        )
    }

    /** Statement for one period: rows in [start, end] plus the balance brought forward. */
    suspend fun periodStatements(buildingId: String, periodId: String): Map<String, UnitStatement> {
        val p = r.db.periodDao().period(periodId) ?: fail(OpError.NOT_FOUND)
        return r.db.buildingDao().units(buildingId).associate { it.id to r.finance.unitStatement(buildingId, it.id, p.startDate, p.endDate) }
    }
}
