package com.github.opscalehub.dong.domain.water

import com.github.opscalehub.dong.domain.model.Meter
import com.github.opscalehub.dong.domain.model.MeterReading
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.domain.model.ReadingKind
import com.github.opscalehub.dong.domain.model.WaterTariff
import com.github.opscalehub.dong.domain.money.MoneyMath
import com.github.opscalehub.dong.domain.period.Issue
import com.github.opscalehub.dong.domain.period.IssueCode
import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
data class ChainStep(val readingId: String, val date: Long, val valueLiters: Long, val kind: ReadingKind, val deltaLiters: Long)

@Serializable
data class MeterConsumption(
    val meterId: String,
    val role: MeterRole,
    val unitId: String?,
    /** null when the chain is invalid or incomplete. */
    val liters: Long?,
    val openingReadingId: String? = null,
    val closingReadingId: String? = null,
    val steps: List<ChainStep> = emptyList(),
    /** Meter had no activity in the period (retired before it, or never read). */
    val inactive: Boolean = false,
)

@Serializable
data class WaterReport(
    val meters: List<MeterConsumption>,
    /** Σ main meters; null if there is no main meter or any main chain is invalid. */
    val mainLiters: Long?,
    /** Per unit; null = unit has a meter but its consumption could not be computed. */
    val unitLiters: Map<String, Long?>,
    val unitsWithoutMeter: List<String>,
    val issues: List<Issue>,
) {
    val unitSumLiters: Long get() = unitLiters.values.filterNotNull().sum()
    val complete: Boolean get() = unitLiters.values.none { it == null }
    /** main − Σ units, the consumption no unit meter explains. */
    val unallocatedLiters: Long? get() = mainLiters?.let { if (complete) it - unitSumLiters else null }
}

/**
 * Builds per-meter consumption for the inclusive period [start, end].
 *
 * Opening = last effective reading dated before [start], or an INITIAL reading
 * inside the period. Closing = last effective reading inside the period.
 * Corrections supersede readings; superseded readings are ignored. Negative
 * steps are accepted only for ROLLOVER readings on meters with a rollover
 * limit; INITIAL readings start a new baseline (meter reset/replacement).
 */
object WaterCalculator {

    fun effectiveReadings(readings: List<MeterReading>): List<MeterReading> {
        val superseded = readings.mapNotNull { it.supersedesId }.toSet()
        return readings.filter { it.id !in superseded }.sortedWith(compareBy({ it.date }, { it.enteredAt }, { it.id }))
    }

    fun meterConsumption(meter: Meter, readings: List<MeterReading>, start: Long, end: Long): Pair<MeterConsumption, List<Issue>> {
        val eff = effectiveReadings(readings.filter { it.meterId == meter.id })
        val before = eff.filter { it.date < start }
        val inside = eff.filter { it.date in start..end }
        val base = MeterConsumption(meter.id, meter.role, meter.unitId, null)
        val notYetInstalled = meter.installedOn != null && meter.installedOn > end
        val alreadyRetired = meter.retiredOn != null && meter.retiredOn < start
        if (notYetInstalled || alreadyRetired) return base.copy(liters = 0, inactive = true) to emptyList()
        if (before.lastOrNull()?.kind == ReadingKind.FINAL) {
            // Meter was removed before this period.
            return base.copy(liters = 0, inactive = true) to
                if (inside.isNotEmpty()) listOf(Issue(IssueCode.READING_AFTER_FINAL, meterId = meter.id, unitId = meter.unitId)) else emptyList()
        }
        if (before.isEmpty() && inside.isEmpty()) {
            return base.copy(inactive = true) to listOf(Issue(IssueCode.MISSING_READING, meterId = meter.id, unitId = meter.unitId))
        }
        val opening: MeterReading
        val rest: List<MeterReading>
        if (before.isNotEmpty()) {
            opening = before.last()
            rest = inside
        } else {
            val idx = inside.indexOfFirst { it.kind == ReadingKind.INITIAL }
            if (idx < 0) {
                return base to listOf(Issue(IssueCode.MISSING_OPENING_READING, meterId = meter.id, unitId = meter.unitId))
            }
            opening = inside[idx]
            rest = inside.drop(idx + 1)
            if (idx > 0) {
                // readings before the INITIAL one have no baseline; they cannot be used.
                return base to listOf(Issue(IssueCode.MISSING_OPENING_READING, meterId = meter.id, unitId = meter.unitId))
            }
        }
        if (rest.isEmpty()) {
            return base.copy(openingReadingId = opening.id) to
                listOf(Issue(IssueCode.MISSING_CLOSING_READING, meterId = meter.id, unitId = meter.unitId))
        }
        val issues = mutableListOf<Issue>()
        val steps = mutableListOf(ChainStep(opening.id, opening.date, opening.valueLiters, opening.kind, 0))
        var prev = opening
        var total = 0L
        var finalSeen = false
        var valid = true
        for (cur in rest) {
            if (finalSeen) {
                issues += Issue(IssueCode.READING_AFTER_FINAL, meterId = meter.id, unitId = meter.unitId, args = mapOf("readingId" to cur.id))
                valid = false
                break
            }
            val delta: Long? = when {
                cur.kind == ReadingKind.INITIAL -> 0L
                cur.valueLiters >= prev.valueLiters -> {
                    if (cur.kind == ReadingKind.ROLLOVER) {
                        issues += Issue(IssueCode.ROLLOVER_NOT_WRAPPED, meterId = meter.id, unitId = meter.unitId, args = mapOf("readingId" to cur.id))
                    }
                    cur.valueLiters - prev.valueLiters
                }
                cur.kind == ReadingKind.ROLLOVER -> {
                    val limit = meter.rolloverLimitLiters
                    if (limit == null || limit <= prev.valueLiters) {
                        issues += Issue(IssueCode.ROLLOVER_WITHOUT_LIMIT, meterId = meter.id, unitId = meter.unitId, args = mapOf("readingId" to cur.id))
                        null
                    } else {
                        limit - prev.valueLiters + cur.valueLiters
                    }
                }
                else -> {
                    issues += Issue(
                        IssueCode.NEGATIVE_CONSUMPTION, meterId = meter.id, unitId = meter.unitId,
                        args = mapOf("readingId" to cur.id, "previous" to prev.valueLiters.toString(), "current" to cur.valueLiters.toString()),
                    )
                    null
                }
            }
            if (delta == null) {
                valid = false
                break
            }
            total += delta
            steps += ChainStep(cur.id, cur.date, cur.valueLiters, cur.kind, delta)
            if (cur.kind == ReadingKind.FINAL) finalSeen = true
            prev = cur
        }
        return base.copy(
            liters = if (valid) total else null,
            openingReadingId = opening.id,
            closingReadingId = rest.last().id,
            steps = steps,
        ) to issues
    }

    fun report(meters: List<Meter>, readings: List<MeterReading>, unitIds: List<String>, start: Long, end: Long): WaterReport {
        val issues = mutableListOf<Issue>()
        val consumptions = meters.map { m ->
            val (c, iss) = meterConsumption(m, readings, start, end)
            issues += iss
            c
        }
        val mains = consumptions.filter { it.role == MeterRole.MAIN && !it.inactive }
        val mainLiters = when {
            mains.isEmpty() -> null
            mains.any { it.liters == null } -> null
            else -> mains.sumOf { it.liters!! }
        }
        if (meters.none { it.role == MeterRole.MAIN }) issues += Issue(IssueCode.NO_MAIN_METER)
        val unitLiters = LinkedHashMap<String, Long?>()
        val withoutMeter = mutableListOf<String>()
        for (u in unitIds) {
            val ms = consumptions.filter { it.role == MeterRole.UNIT && it.unitId == u }
            if (ms.isEmpty()) {
                withoutMeter += u
                continue
            }
            val active = ms.filter { !(it.inactive && it.liters == 0L) }
            unitLiters[u] = when {
                active.isEmpty() -> null
                active.any { it.liters == null } -> null
                else -> active.sumOf { it.liters!! }
            }
        }
        val report = WaterReport(consumptions, mainLiters, unitLiters, withoutMeter, issues)
        report.unallocatedLiters?.let { un ->
            if (un < 0) issues += Issue(IssueCode.UNIT_METERS_EXCEED_MAIN, args = mapOf("liters" to un.toString()))
            else if (un > 0) issues += Issue(IssueCode.UNALLOCATED_WATER, args = mapOf("liters" to un.toString()))
        }
        return report.copy(issues = issues.toList())
    }
}

@Serializable
data class TierUse(val fromLiters: Long, val toLiters: Long, val priceMinorPerM3: Long, val liters: Long)

@Serializable
data class TariffCharge(val liters: Long, val tiers: List<TierUse>, val consumptionMinor: Long, val surchargeMinor: Long, val fixedFeeMinor: Long, val totalMinor: Long)

object TariffCalculator {

    fun validate(t: WaterTariff): Boolean {
        if (t.tiers.isEmpty() || t.fixedFeeMinor < 0 || t.surchargeBp < 0 || t.commonRateMinorPerM3 < 0) return false
        if (t.tiers.any { it.priceMinorPerM3 < 0 }) return false
        if (t.tiers.last().upToLiters != null) return false
        val bounds = t.tiers.dropLast(1).map { it.upToLiters ?: return false }
        return bounds.zipWithNext().all { (a, b) -> b > a } && bounds.all { it > 0 }
    }

    /** Tariff applicable for a period ending on [periodEnd]: latest effectiveFrom ≤ end. */
    fun select(tariffs: List<WaterTariff>, periodEnd: Long): WaterTariff? =
        tariffs.filter { it.effectiveFrom <= periodEnd }.maxByOrNull { it.effectiveFrom }

    /**
     * fixed fee + Σ tier(litres × price / 1000) + surcharge, all as one exact
     * rational, rounded half-up once to [increment].
     */
    fun charge(t: WaterTariff, liters: Long, increment: Long): TariffCharge {
        require(validate(t)) { "invalid tariff" }
        val uses = mutableListOf<TierUse>()
        var lower = 0L
        var num = BigInteger.ZERO // minor × litres
        for (tier in t.tiers) {
            val upper = tier.upToLiters ?: Long.MAX_VALUE
            val inTier = (minOf(liters, upper) - lower).coerceAtLeast(0)
            if (inTier > 0) {
                uses += TierUse(lower, lower + inTier, tier.priceMinorPerM3, inTier)
                num += BigInteger.valueOf(inTier) * BigInteger.valueOf(tier.priceMinorPerM3)
            }
            if (liters <= upper) break
            lower = upper
        }
        val thousand = BigInteger.valueOf(1000)
        val tenK = BigInteger.valueOf(10_000)
        val consumption = MoneyMath.roundHalfUp(num, thousand, 1)
        val withSurcharge = num * BigInteger.valueOf(10_000L + t.surchargeBp)
        val totalNum = withSurcharge + BigInteger.valueOf(t.fixedFeeMinor) * thousand * tenK
        val total = MoneyMath.roundHalfUp(totalNum, thousand * tenK, increment)
        val surcharge = MoneyMath.roundHalfUp(num * BigInteger.valueOf(t.surchargeBp.toLong()), thousand * tenK, 1)
        return TariffCharge(liters, uses, consumption, surcharge, t.fixedFeeMinor, total)
    }
}
