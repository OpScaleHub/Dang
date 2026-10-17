package com.github.opscalehub.dong.domain.allocation

import com.github.opscalehub.dong.domain.model.OccupancyBasis
import com.github.opscalehub.dong.domain.model.OccupancyRecord
import com.github.opscalehub.dong.domain.model.ParkingAssignment
import com.github.opscalehub.dong.domain.model.ParkingSpace
import com.github.opscalehub.dong.domain.model.UnitInfo
import com.github.opscalehub.dong.domain.model.WaterTariff
import com.github.opscalehub.dong.domain.money.Allocator
import com.github.opscalehub.dong.domain.money.MoneyMath
import com.github.opscalehub.dong.domain.money.Weighted
import com.github.opscalehub.dong.domain.period.Issue
import com.github.opscalehub.dong.domain.period.IssueCode
import com.github.opscalehub.dong.domain.water.TariffCalculator
import com.github.opscalehub.dong.domain.water.TariffCharge
import com.github.opscalehub.dong.domain.water.WaterReport
import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
enum class QuantityKind { UNIT, OCCUPANTS, OCCUPANT_DAYS, AREA_CENTI_M2, LITERS, WEIGHT, EXPLICIT_AMOUNT, PARKING_SPACES }

@Serializable
enum class BasisKind { EQUAL, OCCUPANTS, AREA, CONSUMPTION, WEIGHTED, EXPLICIT, PARKING, WATER_TARIFF }

/** Explanation of one unit's share within one component. */
@Serializable
data class UnitShare(
    val unitId: String,
    val quantityKind: QuantityKind,
    val quantity: Long,
    val weight: Long,
    val share: Long,
    /** Minor units added by the largest-remainder rounding step. */
    val roundingAdjustment: Long = 0,
    val tariff: TariffCharge? = null,
)

@Serializable
enum class ComponentPart { WHOLE, METERED, COMMON }

@Serializable
data class ComponentResult(
    val index: Int,
    val part: ComponentPart,
    val basis: BasisKind,
    val amount: Long,
    val totalWeight: Long,
    val units: List<UnitShare>,
    val excludedUnits: List<String> = emptyList(),
    val excludedSpaces: List<String> = emptyList(),
    /** Part of this component borne by the fund instead of units. */
    val fundBorne: Long = 0,
)

@Serializable
data class AllocationResult(
    val sourceId: String,
    val amount: Long,
    val shares: Map<String, Long>,
    val fundBorne: Long,
    val components: List<ComponentResult>,
    val issues: List<Issue>,
) {
    val valid: Boolean get() = issues.none { it.blocking }
    val allocatedTotal: Long get() = shares.values.sum()
}

/** Everything the engine needs about one period, prepared by [com.github.opscalehub.dong.domain.period.PeriodCalculator]. */
class AllocationContext(
    val units: List<UnitInfo>,
    val occupancyQuantity: Map<String, Long?>,
    val occupancyKind: QuantityKind,
    val periodDays: Int,
    val water: WaterReport?,
    val spaces: List<ParkingSpace>,
    /** spaceId → assignment effective on the period's last day. */
    val effectiveAssignments: Map<String, ParkingAssignment>,
    val increment: Long,
) {
    val unitsById = units.associateBy { it.id }
    val activeUnits = units.filter { it.active }.sortedBy { it.sortOrder }

    companion object {
        fun occupancy(records: List<OccupancyRecord>, units: List<UnitInfo>, start: Long, end: Long, basis: OccupancyBasis): Map<String, Long?> =
            units.associate { u ->
                val recs = records.filter { it.unitId == u.id }.sortedBy { it.effectiveFrom }
                u.id to when (basis) {
                    OccupancyBasis.PERIOD_START -> recs.lastOrNull { it.effectiveFrom <= start }?.occupantCount?.toLong()
                    OccupancyBasis.PERIOD_END -> recs.lastOrNull { it.effectiveFrom <= end }?.occupantCount?.toLong()
                    OccupancyBasis.DAY_WEIGHTED -> {
                        if (recs.isEmpty() || recs.first().effectiveFrom > end) {
                            null
                        } else {
                            var sum = 0L
                            recs.forEachIndexed { i, r ->
                                val from = maxOf(r.effectiveFrom, start)
                                val to = minOf(if (i + 1 < recs.size) recs[i + 1].effectiveFrom - 1 else end, end)
                                if (to >= from) sum += (to - from + 1) * r.occupantCount
                            }
                            sum
                        }
                    }
                }
            }

        fun effectiveAssignments(assignments: List<ParkingAssignment>, basisDate: Long): Map<String, ParkingAssignment> =
            assignments
                .filter { it.effectiveFrom <= basisDate && (it.effectiveTo == null || it.effectiveTo >= basisDate) }
                .groupBy { it.spaceId }
                .mapValues { (_, list) -> list.maxBy { it.effectiveFrom } }
    }
}

object AllocationEngine {

    fun allocate(sourceId: String, amount: Long, policy: AllocationPolicy, ctx: AllocationContext): AllocationResult {
        if (policy.fundOnly) return AllocationResult(sourceId, amount, emptyMap(), amount, emptyList(), emptyList())
        if (amount < 0) {
            // Credit (e.g. supplier refund): allocate the magnitude and negate, keeping Σ exact.
            val pos = allocate(sourceId, -amount, policy, ctx)
            return pos.copy(
                amount = amount,
                shares = pos.shares.mapValues { -it.value },
                fundBorne = -pos.fundBorne,
                components = pos.components.map { c ->
                    c.copy(amount = -c.amount, fundBorne = -c.fundBorne, units = c.units.map { it.copy(share = -it.share, roundingAdjustment = -it.roundingAdjustment) })
                },
            )
        }
        val issues = mutableListOf<Issue>()
        val amounts = resolvePortions(sourceId, amount, policy.components, ctx.increment, issues)
            ?: return AllocationResult(sourceId, amount, emptyMap(), 0, emptyList(), issues)
        val shares = LinkedHashMap<String, Long>()
        val results = mutableListOf<ComponentResult>()
        var fundBorne = 0L
        policy.components.forEachIndexed { i, comp ->
            val compResults = allocateComponent(sourceId, i, amounts[i], comp, ctx, issues)
            for (r in compResults) {
                results += r
                fundBorne += r.fundBorne
                r.units.forEach { shares.merge(it.unitId, it.share, Long::plus) }
            }
        }
        val res = AllocationResult(sourceId, amount, shares, fundBorne, results, issues)
        if (res.valid) check(res.allocatedTotal + fundBorne == amount) { "allocation invariant violated for $sourceId" }
        return if (res.valid) res else res.copy(shares = emptyMap(), fundBorne = 0)
    }

    /** Fixed → percent → remainder. Returns per-component amounts or null on error. */
    internal fun resolvePortions(sourceId: String, amount: Long, comps: List<AllocationComponent>, inc: Long, issues: MutableList<Issue>): List<Long>? {
        if (comps.isEmpty()) {
            issues += Issue(IssueCode.PORTIONS_INCOMPLETE, sourceId)
            return null
        }
        val remainderCount = comps.count { it.portion is Portion.Remainder }
        if (remainderCount > 1) {
            issues += Issue(IssueCode.PORTIONS_INCOMPLETE, sourceId, args = mapOf("reason" to "multiple_remainders"))
            return null
        }
        val out = LongArray(comps.size)
        var fixed = 0L
        comps.forEachIndexed { i, c ->
            when (val p = c.portion) {
                is Portion.Fixed -> {
                    if (p.amountMinor < 0) {
                        issues += Issue(IssueCode.NEGATIVE_AMOUNT, sourceId)
                        return null
                    }
                    out[i] = p.amountMinor
                    fixed += p.amountMinor
                }
                is Portion.Percent -> if (p.basisPoints !in 1..10_000) {
                    issues += Issue(IssueCode.INVALID_PERCENT, sourceId, args = mapOf("bp" to p.basisPoints.toString()))
                    return null
                }
                Portion.Remainder -> Unit
            }
        }
        if (fixed > amount) {
            issues += Issue(IssueCode.PORTIONS_EXCEED_AMOUNT, sourceId)
            return null
        }
        val pctIdx = comps.indices.filter { comps[it].portion is Portion.Percent }
        val bps = pctIdx.map { (comps[it].portion as Portion.Percent).basisPoints.toLong() }
        if (remainderCount == 1) {
            pctIdx.forEachIndexed { k, i -> out[i] = MoneyMath.roundHalfUp(amount * bps[k], 10_000, inc) }
            val rest = amount - out.sum()
            if (rest < 0) {
                issues += Issue(IssueCode.PORTIONS_EXCEED_AMOUNT, sourceId)
                return null
            }
            out[comps.indexOfFirst { it.portion is Portion.Remainder }] = rest
        } else {
            when {
                bps.isEmpty() && fixed == amount -> Unit
                fixed == 0L && bps.sum() == 10_000L -> Allocator.split(amount, bps, inc).forEachIndexed { k, v -> out[pctIdx[k]] = v }
                else -> {
                    issues += Issue(IssueCode.PORTIONS_INCOMPLETE, sourceId)
                    return null
                }
            }
        }
        return out.toList()
    }

    private fun eligibleUnits(sourceId: String, e: Eligibility, ctx: AllocationContext, issues: MutableList<Issue>): Pair<List<UnitInfo>, List<String>> =
        when (e) {
            Eligibility.AllActive -> ctx.activeUnits to ctx.units.filter { !it.active }.map { it.id }
            is Eligibility.Selected -> {
                e.unitIds.filter { it !in ctx.unitsById }.forEach { issues += Issue(IssueCode.UNKNOWN_UNIT_IN_POLICY, sourceId, unitId = it) }
                ctx.activeUnits.filter { it.id in e.unitIds } to ctx.units.filter { it.id !in e.unitIds || !it.active }.map { it.id }
            }
        }

    private fun allocateComponent(
        sourceId: String,
        index: Int,
        amount: Long,
        comp: AllocationComponent,
        ctx: AllocationContext,
        issues: MutableList<Issue>,
    ): List<ComponentResult> {
        val (eligible, excluded) = eligibleUnits(sourceId, comp.eligibility, ctx, issues)
        val basis = comp.basis
        if (basis is Basis.Explicit) return listOf(explicit(sourceId, index, amount, basis, ctx, issues))
        if (eligible.isEmpty()) {
            issues += Issue(IssueCode.NO_ELIGIBLE_UNITS, sourceId)
            return emptyList()
        }
        if (basis is Basis.Consumption && basis.common != CommonWater.Proportional) {
            return consumptionWithCommon(sourceId, index, amount, basis.common, eligible, excluded, ctx, issues)
        }
        val q = quantities(sourceId, basis, eligible, ctx, issues) ?: return emptyList()
        return listOfNotNull(distribute(sourceId, index, ComponentPart.WHOLE, amount, q, excluded, ctx, issues))
    }

    /** Per-unit (quantityKind, quantity, weight) for a basis, or null if inputs are missing. */
    private class Quantities(val basis: BasisKind, val kind: QuantityKind, val values: List<Triple<UnitInfo, Long, Long>>, val excludedSpaces: List<String> = emptyList())

    private fun quantities(sourceId: String, basis: Basis, eligible: List<UnitInfo>, ctx: AllocationContext, issues: MutableList<Issue>): Quantities? {
        var ok = true
        val q = when (basis) {
            Basis.Equal -> Quantities(BasisKind.EQUAL, QuantityKind.UNIT, eligible.map { Triple(it, 1L, 1L) })
            Basis.Occupants -> Quantities(BasisKind.OCCUPANTS, ctx.occupancyKind, eligible.map { u ->
                val v = ctx.occupancyQuantity[u.id]
                if (v == null) {
                    issues += Issue(IssueCode.MISSING_OCCUPANCY, sourceId, unitId = u.id)
                    ok = false
                }
                Triple(u, v ?: 0, v ?: 0)
            })
            Basis.Area -> Quantities(BasisKind.AREA, QuantityKind.AREA_CENTI_M2, eligible.map { u ->
                if (u.areaCentiM2 <= 0) {
                    issues += Issue(IssueCode.MISSING_AREA, sourceId, unitId = u.id)
                    ok = false
                }
                Triple(u, u.areaCentiM2, u.areaCentiM2.coerceAtLeast(0))
            })
            is Basis.Consumption -> Quantities(BasisKind.CONSUMPTION, QuantityKind.LITERS, eligible.map { u ->
                val v = consumptionOf(u.id, ctx)
                if (v == null) {
                    issues += Issue(IssueCode.MISSING_CONSUMPTION, sourceId, unitId = u.id)
                    ok = false
                }
                Triple(u, v ?: 0, v ?: 0)
            })
            is Basis.Weighted -> {
                basis.weights.keys.filter { it !in ctx.unitsById }.forEach { issues += Issue(IssueCode.UNKNOWN_UNIT_IN_POLICY, sourceId, unitId = it) }
                if (basis.weights.values.any { it < 0 }) {
                    issues += Issue(IssueCode.NEGATIVE_AMOUNT, sourceId)
                    ok = false
                }
                Quantities(BasisKind.WEIGHTED, QuantityKind.WEIGHT, eligible.map { u ->
                    val w = (basis.weights[u.id] ?: 0L).coerceAtLeast(0)
                    Triple(u, w, w)
                })
            }
            is Basis.Parking -> parking(sourceId, basis, eligible, ctx, issues)
            is Basis.Explicit -> error("handled separately")
        }
        if (issues.any { it.code == IssueCode.UNKNOWN_UNIT_IN_POLICY && it.sourceId == sourceId }) ok = false
        return if (ok) q else null
    }

    private fun consumptionOf(unitId: String, ctx: AllocationContext): Long? {
        val w = ctx.water ?: return null
        if (unitId !in w.unitLiters) return null
        return w.unitLiters[unitId]
    }

    private fun parking(sourceId: String, b: Basis.Parking, eligible: List<UnitInfo>, ctx: AllocationContext, issues: MutableList<Issue>): Quantities {
        val eligibleIds = eligible.map { it.id }.toSet()
        val perUnit = HashMap<String, Long>()
        val excludedSpaces = mutableListOf<String>()
        for (s in ctx.spaces) {
            val a = ctx.effectiveAssignments[s.id]
            val include = s.active && a != null && a.relation in b.relations && a.unitId in eligibleIds && when (b.filter) {
                SpaceFilter.ALL_ASSIGNED -> true
                SpaceFilter.IN_USE -> a.inUse
                SpaceFilter.SELECTED -> s.id in b.spaceIds
            }
            if (include) {
                perUnit.merge(a!!.unitId, (b.spaceWeights[s.id] ?: 1L).coerceAtLeast(0), Long::plus)
            } else {
                excludedSpaces += s.id
            }
        }
        if (excludedSpaces.isNotEmpty()) {
            issues += Issue(IssueCode.PARKING_SPACES_EXCLUDED, sourceId, args = mapOf("count" to excludedSpaces.size.toString(), "spaces" to excludedSpaces.joinToString(",")))
        }
        return Quantities(BasisKind.PARKING, QuantityKind.PARKING_SPACES, eligible.map { u -> (perUnit[u.id] ?: 0L).let { Triple(u, it, it) } }, excludedSpaces)
    }

    private fun distribute(
        sourceId: String,
        index: Int,
        part: ComponentPart,
        amount: Long,
        q: Quantities,
        excluded: List<String>,
        ctx: AllocationContext,
        issues: MutableList<Issue>,
    ): ComponentResult? {
        val total = q.values.sumOf { it.third }
        if (amount == 0L) {
            return ComponentResult(index, part, q.basis, 0, total, q.values.map { (u, qty, w) -> UnitShare(u.id, q.kind, qty, w, 0) }, excluded, q.excludedSpaces)
        }
        if (total == 0L) {
            issues += Issue(IssueCode.ZERO_BASIS, sourceId, args = mapOf("basis" to q.basis.name))
            return null
        }
        q.values.filter { it.third == 0L }.forEach { issues += Issue(IssueCode.UNIT_ZERO_WEIGHT, sourceId, unitId = it.first.id, args = mapOf("basis" to q.basis.name)) }
        val out = Allocator.allocate(amount, q.values.map { (u, _, w) -> Weighted(u.id, u.sortOrder, w) }, ctx.increment)
        val detail = out.details.associateBy { it.unitId }
        return ComponentResult(
            index, part, q.basis, amount, total,
            q.values.map { (u, qty, w) -> UnitShare(u.id, q.kind, qty, w, out.shares.getValue(u.id), detail.getValue(u.id).roundingAdjustment) },
            excluded, q.excludedSpaces,
        )
    }

    private fun explicit(sourceId: String, index: Int, amount: Long, b: Basis.Explicit, ctx: AllocationContext, issues: MutableList<Issue>): ComponentResult {
        var ok = true
        b.amounts.forEach { (uid, v) ->
            val u = ctx.unitsById[uid]
            if (u == null || !u.active) {
                issues += Issue(IssueCode.UNKNOWN_UNIT_IN_POLICY, sourceId, unitId = uid)
                ok = false
            }
            if (v < 0) {
                issues += Issue(IssueCode.NEGATIVE_AMOUNT, sourceId, unitId = uid)
                ok = false
            }
        }
        val sum = b.amounts.values.sum()
        if (sum != amount) {
            issues += Issue(IssueCode.EXPLICIT_SUM_MISMATCH, sourceId, args = mapOf("expected" to amount.toString(), "actual" to sum.toString()))
            ok = false
        }
        val units = if (!ok) emptyList() else ctx.activeUnits.filter { it.id in b.amounts }.map {
            val v = b.amounts.getValue(it.id)
            UnitShare(it.id, QuantityKind.EXPLICIT_AMOUNT, v, v, v)
        }
        return ComponentResult(index, ComponentPart.WHOLE, BasisKind.EXPLICIT, amount, sum, units)
    }

    private fun consumptionWithCommon(
        sourceId: String,
        index: Int,
        amount: Long,
        common: CommonWater,
        eligible: List<UnitInfo>,
        excluded: List<String>,
        ctx: AllocationContext,
        issues: MutableList<Issue>,
    ): List<ComponentResult> {
        val q = quantities(sourceId, Basis.Consumption(), eligible, ctx, issues) ?: return emptyList()
        val main = ctx.water?.mainLiters
        if (main == null || main <= 0) {
            issues += Issue(IssueCode.MAIN_METER_REQUIRED, sourceId)
            return emptyList()
        }
        val unitSum = q.values.sumOf { it.third }
        val metered = if (unitSum >= main) {
            if (unitSum > main) issues += Issue(IssueCode.UNIT_METERS_EXCEED_MAIN, sourceId, args = mapOf("liters" to (main - unitSum).toString()))
            amount
        } else {
            MoneyMath.roundHalfUp(BigInteger.valueOf(amount) * BigInteger.valueOf(unitSum), BigInteger.valueOf(main), ctx.increment)
        }
        val commonAmount = amount - metered
        val out = mutableListOf<ComponentResult>()
        distribute(sourceId, index, ComponentPart.METERED, metered, q, excluded, ctx, issues)?.let { out += it }
        when (common) {
            is CommonWater.Separate -> {
                val cq = quantities(sourceId, common.basis.toBasis(), eligible, ctx, issues) ?: return emptyList()
                distribute(sourceId, index, ComponentPart.COMMON, commonAmount, cq, excluded, ctx, issues)?.let { out += it }
            }
            CommonWater.FundBorne -> {
                if (commonAmount != 0L) issues += Issue(IssueCode.FUND_BORNE_PORTION, sourceId, args = mapOf("amount" to commonAmount.toString()))
                out += ComponentResult(index, ComponentPart.COMMON, BasisKind.CONSUMPTION, commonAmount, 0, emptyList(), excluded, fundBorne = commonAmount)
            }
            CommonWater.Proportional -> error("not reached")
        }
        return out
    }

    // ---------------------------------------------------------------- rate & tariff charges

    /** Charge = rate × quantity per unit, each rounded half-up to the increment. */
    fun rate(sourceId: String, rateMinor: Long, policy: AllocationPolicy, ctx: AllocationContext): AllocationResult {
        val issues = mutableListOf<Issue>()
        val comp = policy.components.singleOrNull()
        if (comp == null || comp.portion != Portion.Remainder || rateMinor < 0) {
            issues += Issue(if (rateMinor < 0) IssueCode.NEGATIVE_AMOUNT else IssueCode.RATE_BASIS_UNSUPPORTED, sourceId)
            return AllocationResult(sourceId, 0, emptyMap(), 0, emptyList(), issues)
        }
        val (eligible, excluded) = eligibleUnits(sourceId, comp.eligibility, ctx, issues)
        if (eligible.isEmpty()) {
            issues += Issue(IssueCode.NO_ELIGIBLE_UNITS, sourceId)
            return AllocationResult(sourceId, 0, emptyMap(), 0, emptyList(), issues)
        }
        val scale = when (comp.basis) {
            Basis.Equal -> 1L
            Basis.Occupants -> if (ctx.occupancyKind == QuantityKind.OCCUPANT_DAYS) ctx.periodDays.toLong() else 1L
            Basis.Area -> 100L
            is Basis.Parking -> 1L
            else -> {
                issues += Issue(IssueCode.RATE_BASIS_UNSUPPORTED, sourceId)
                return AllocationResult(sourceId, 0, emptyMap(), 0, emptyList(), issues)
            }
        }
        val q = quantities(sourceId, comp.basis, eligible, ctx, issues)
            ?: return AllocationResult(sourceId, 0, emptyMap(), 0, emptyList(), issues)
        val units = q.values.map { (u, qty, w) ->
            UnitShare(u.id, q.kind, qty, w, MoneyMath.roundHalfUp(BigInteger.valueOf(rateMinor) * BigInteger.valueOf(w), BigInteger.valueOf(scale), ctx.increment))
        }
        val total = units.sumOf { it.share }
        val res = ComponentResult(0, ComponentPart.WHOLE, q.basis, total, q.values.sumOf { it.third }, units, excluded, q.excludedSpaces)
        return AllocationResult(sourceId, total, units.associate { it.unitId to it.share }, 0, listOf(res), issues)
    }

    /**
     * Water-tariff charge: each eligible unit pays the tariff on its own
     * consumption. If the tariff has a common rate, the unallocated volume is
     * priced and shared according to the policy's [Basis.Consumption.common].
     */
    fun waterTariff(sourceId: String, tariff: WaterTariff?, policy: AllocationPolicy, ctx: AllocationContext): AllocationResult {
        val issues = mutableListOf<Issue>()
        fun fail() = AllocationResult(sourceId, 0, emptyMap(), 0, emptyList(), issues)
        if (tariff == null) {
            issues += Issue(IssueCode.NO_TARIFF, sourceId)
            return fail()
        }
        if (!TariffCalculator.validate(tariff)) {
            issues += Issue(IssueCode.INVALID_TARIFF, sourceId)
            return fail()
        }
        val comp = policy.components.firstOrNull() ?: AllocationComponent(basis = Basis.Consumption())
        val (eligible, excluded) = eligibleUnits(sourceId, comp.eligibility, ctx, issues)
        if (eligible.isEmpty()) {
            issues += Issue(IssueCode.NO_ELIGIBLE_UNITS, sourceId)
            return fail()
        }
        val q = quantities(sourceId, Basis.Consumption(), eligible, ctx, issues) ?: return fail()
        val units = q.values.map { (u, qty, _) ->
            val c = TariffCalculator.charge(tariff, qty, ctx.increment)
            UnitShare(u.id, QuantityKind.LITERS, qty, qty, c.totalMinor, tariff = c)
        }
        val results = mutableListOf(ComponentResult(0, ComponentPart.METERED, BasisKind.WATER_TARIFF, units.sumOf { it.share }, q.values.sumOf { it.third }, units, excluded))
        val shares = LinkedHashMap(units.associate { it.unitId to it.share })
        var fundBorne = 0L
        val unallocated = ctx.water?.unallocatedLiters
        if (tariff.commonRateMinorPerM3 > 0) {
            if (unallocated == null) {
                issues += Issue(IssueCode.MAIN_METER_REQUIRED, sourceId)
                return fail()
            }
            if (unallocated > 0) {
                val commonAmount = MoneyMath.roundHalfUp(unallocated * tariff.commonRateMinorPerM3, 1000, ctx.increment)
                when (val c = (comp.basis as? Basis.Consumption)?.common ?: CommonWater.FundBorne) {
                    is CommonWater.Separate -> {
                        val cq = quantities(sourceId, c.basis.toBasis(), eligible, ctx, issues) ?: return fail()
                        val r = distribute(sourceId, 0, ComponentPart.COMMON, commonAmount, cq, excluded, ctx, issues) ?: return fail()
                        results += r
                        r.units.forEach { shares.merge(it.unitId, it.share, Long::plus) }
                    }
                    CommonWater.Proportional -> {
                        val r = distribute(sourceId, 0, ComponentPart.COMMON, commonAmount, q, excluded, ctx, issues) ?: return fail()
                        results += r
                        r.units.forEach { shares.merge(it.unitId, it.share, Long::plus) }
                    }
                    CommonWater.FundBorne -> {
                        fundBorne = commonAmount
                        issues += Issue(IssueCode.FUND_BORNE_PORTION, sourceId, args = mapOf("amount" to commonAmount.toString()))
                        results += ComponentResult(0, ComponentPart.COMMON, BasisKind.WATER_TARIFF, commonAmount, 0, emptyList(), excluded, fundBorne = commonAmount)
                    }
                }
            }
        }
        val res = AllocationResult(sourceId, shares.values.sum(), shares, fundBorne, results, issues)
        return if (res.valid) res else fail()
    }
}
