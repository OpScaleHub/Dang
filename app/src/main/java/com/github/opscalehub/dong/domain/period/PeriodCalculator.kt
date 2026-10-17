package com.github.opscalehub.dong.domain.period

import com.github.opscalehub.dong.domain.allocation.AllocationContext
import com.github.opscalehub.dong.domain.allocation.AllocationEngine
import com.github.opscalehub.dong.domain.allocation.AllocationResult
import com.github.opscalehub.dong.domain.allocation.QuantityKind
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.domain.model.OccupancyBasis
import com.github.opscalehub.dong.domain.model.PeriodInput
import com.github.opscalehub.dong.domain.water.TariffCalculator
import com.github.opscalehub.dong.domain.water.WaterCalculator
import com.github.opscalehub.dong.domain.water.WaterReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SourceType { EXPENSE, CHARGE_ITEM }

/** One amount charged to one unit from one source; becomes a RECEIVABLE/INCOME posting pair. */
@Serializable
data class ChargeLine(
    val unitId: String,
    val sourceType: SourceType,
    val sourceId: String,
    val categoryId: String,
    val fundId: String,
    val amount: Long,
)

@Serializable
data class SourceResult(
    val sourceType: SourceType,
    val sourceId: String,
    val name: String,
    val categoryId: String,
    val fundId: String,
    val mode: ChargeMode? = null,
    val allocation: AllocationResult,
)

@Serializable
data class PeriodResult(
    val lines: List<ChargeLine>,
    val sources: List<SourceResult>,
    val water: WaterReport,
    val issues: List<Issue>,
) {
    val blockingIssues: List<Issue> get() = issues.filter { it.blocking }
    val canFinalize: Boolean get() = blockingIssues.isEmpty()
    val unitTotals: Map<String, Long> get() = lines.groupBy { it.unitId }.mapValues { (_, l) -> l.sumOf { it.amount } }
    val total: Long get() = lines.sumOf { it.amount }
    /** Expense amounts not charged to units (fund-only expenses and fund-borne water portions). */
    val fundBorneTotal: Long get() = sources.filter { it.sourceType == SourceType.EXPENSE }.sumOf { it.allocation.fundBorne }
}

/** The exact basis of a finalized period: complete input plus the computed result. */
@Serializable
data class PeriodSnapshot(
    val formatVersion: Int = 1,
    val calculatedAt: Long,
    val input: PeriodInput,
    val result: PeriodResult,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
        fun decode(text: String): PeriodSnapshot = json.decodeFromString(serializer(), text)
    }
}

object PeriodCalculator {

    fun calculate(input: PeriodInput): PeriodResult {
        val issues = mutableListOf<Issue>()
        if (input.endDate < input.startDate) {
            issues += Issue(IssueCode.INVALID_PERIOD_DATES)
            return PeriodResult(emptyList(), emptyList(), WaterReport(emptyList(), null, emptyMap(), emptyList(), emptyList()), issues)
        }
        val water = WaterCalculator.report(input.meters, input.readings, input.units.filter { it.active }.map { it.id }, input.startDate, input.endDate)
        val hasMeters = input.meters.isNotEmpty()
        if (hasMeters) issues += water.issues
        val ctx = AllocationContext(
            units = input.units,
            occupancyQuantity = AllocationContext.occupancy(input.occupancy, input.units, input.startDate, input.endDate, input.settings.occupancyBasis),
            occupancyKind = if (input.settings.occupancyBasis == OccupancyBasis.DAY_WEIGHTED) QuantityKind.OCCUPANT_DAYS else QuantityKind.OCCUPANTS,
            periodDays = input.days,
            water = if (hasMeters) water else null,
            spaces = input.spaces,
            effectiveAssignments = AllocationContext.effectiveAssignments(input.assignments, input.endDate),
            increment = input.settings.roundingIncrement,
        )
        val sources = mutableListOf<SourceResult>()
        for (e in input.expenses) {
            val r = AllocationEngine.allocate(e.id, e.amountMinor, e.policy, ctx)
            sources += SourceResult(SourceType.EXPENSE, e.id, e.description, e.categoryId, e.fundId, null, r)
        }
        for (c in input.chargeItems) {
            val r = when (c.mode) {
                ChargeMode.TOTAL -> AllocationEngine.allocate(c.id, c.amountMinor, c.policy, ctx)
                ChargeMode.RATE -> AllocationEngine.rate(c.id, c.amountMinor, c.policy, ctx)
                ChargeMode.WATER_TARIFF -> {
                    val t = c.tariffId?.let { id -> input.tariffs.firstOrNull { it.id == id } }
                        ?: TariffCalculator.select(input.tariffs, input.endDate)
                    AllocationEngine.waterTariff(c.id, t, c.policy, ctx)
                }
            }
            sources += SourceResult(SourceType.CHARGE_ITEM, c.id, c.name, c.categoryId, c.fundId, c.mode, r)
        }
        // Double-charge guard: tariff-charged water and a resident-allocated water bill cannot coexist.
        if (input.chargeItems.any { it.mode == ChargeMode.WATER_TARIFF }) {
            val waterCats = input.categories.filter { it.isWater }.map { it.id }.toSet()
            input.expenses.filter { it.categoryId in waterCats && !it.policy.fundOnly }.forEach {
                issues += Issue(IssueCode.DOUBLE_WATER_CHARGE, it.id)
            }
        }
        sources.forEach { issues += it.allocation.issues }
        val lines = sources.filter { it.allocation.valid }.flatMap { s ->
            s.allocation.shares.filter { it.value != 0L }.map { (unit, amt) -> ChargeLine(unit, s.sourceType, s.sourceId, s.categoryId, s.fundId, amt) }
        }
        if (input.expenses.isEmpty() && input.chargeItems.isEmpty()) issues += Issue(IssueCode.EMPTY_PERIOD)
        return PeriodResult(lines, sources, water, issues.distinct())
    }
}

object PeriodCorrection {
    /**
     * Differences between a finalized result and a recalculation, per unit and
     * source. Posting these lines (positive = additional charge, negative =
     * credit) brings the ledger in line with the corrected calculation while
     * leaving the original finalized charges untouched.
     */
    fun diff(original: List<ChargeLine>, corrected: List<ChargeLine>): List<ChargeLine> {
        data class K(val unit: String, val type: SourceType, val src: String, val cat: String, val fund: String)
        fun key(l: ChargeLine) = K(l.unitId, l.sourceType, l.sourceId, l.categoryId, l.fundId)
        val sums = LinkedHashMap<K, Long>()
        original.forEach { sums.merge(key(it), -it.amount, Long::plus) }
        corrected.forEach { sums.merge(key(it), it.amount, Long::plus) }
        return sums.filter { it.value != 0L }.map { (k, v) -> ChargeLine(k.unit, k.type, k.src, k.cat, k.fund, v) }
    }
}
