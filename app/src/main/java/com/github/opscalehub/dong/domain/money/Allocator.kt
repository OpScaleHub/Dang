package com.github.opscalehub.dong.domain.money

import java.math.BigInteger

/** A unit taking part in an allocation with an integer weight. */
data class Weighted(val unitId: String, val sortKey: Int, val weight: Long)

/** How a single unit's share was derived; enough to explain the number on a statement. */
data class ShareDetail(
    val unitId: String,
    val weight: Long,
    /** Exact raw share = [rawNumerator] / [rawDenominator] minor units. */
    val rawNumerator: BigInteger,
    val rawDenominator: BigInteger,
    /** Share after flooring to the rounding increment. */
    val floored: Long,
    /** Extra minor units given by the largest-remainder step (0 for most units). */
    val roundingAdjustment: Long,
    val share: Long,
)

data class AllocationOutcome(val shares: Map<String, Long>, val details: List<ShareDetail>, val totalWeight: Long) {
    val total: Long get() = shares.values.sum()
}

/**
 * Largest-remainder (Hamilton) allocation with a rounding increment.
 *
 * raw_i = amount·w_i/W. Each unit first gets ⌊raw_i/q⌋·q. The leftover
 * L = amount − Σ floors is handed out as ⌊L/q⌋ increments of q to the units
 * with the largest fractional remainders (ties: lower sortKey first), and the
 * final sub-increment residue L mod q goes to the first unit in that ranking.
 * Σ shares == amount always. Mirrors the formulas in the prototype workbook.
 */
object Allocator {

    fun allocate(amount: Long, units: List<Weighted>, increment: Long = 1): AllocationOutcome {
        require(increment >= 1) { "increment must be >= 1" }
        require(amount >= 0) { "amount must be >= 0 (use a negative-amount wrapper for credits)" }
        require(units.all { it.weight >= 0 }) { "weights must be >= 0" }
        require(units.map { it.unitId }.toSet().size == units.size) { "duplicate unit in allocation" }
        val totalWeight = units.sumOf { it.weight }
        require(totalWeight > 0) { "total weight must be > 0" }

        val a = BigInteger.valueOf(amount)
        val q = BigInteger.valueOf(increment)
        val w = BigInteger.valueOf(totalWeight)
        val den = w * q
        data class Work(val u: Weighted, val num: BigInteger, val floorUnits: BigInteger, val rem: BigInteger)
        val work = units.map { u ->
            val num = a * BigInteger.valueOf(u.weight)
            val (quot, rem) = num.divideAndRemainder(den).let { it[0] to it[1] }
            Work(u, num, quot, rem)
        }
        val floors = work.associate { it.u.unitId to it.floorUnits.toLong() * increment }
        val leftover = amount - floors.values.sum()
        val increments = leftover / increment
        val residue = leftover % increment
        val ranked = work.sortedWith(compareByDescending<Work> { it.rem }.thenBy { it.u.sortKey })
        val extra = HashMap<String, Long>()
        ranked.forEachIndexed { i, wk ->
            var e = 0L
            if (i < increments) e += increment
            if (i == 0) e += residue
            if (e != 0L) extra[wk.u.unitId] = e
        }
        val details = work.map { wk ->
            val f = floors.getValue(wk.u.unitId)
            val adj = extra[wk.u.unitId] ?: 0L
            ShareDetail(wk.u.unitId, wk.u.weight, wk.num, w, f, adj, f + adj)
        }
        val shares = LinkedHashMap<String, Long>()
        details.forEach { shares[it.unitId] = it.share }
        check(shares.values.sum() == amount) { "allocation invariant violated" }
        return AllocationOutcome(shares, details, totalWeight)
    }

    /** Splits [amount] into parts proportional to [weights] (e.g. components); same rounding as [allocate]. */
    fun split(amount: Long, weights: List<Long>, increment: Long = 1): List<Long> {
        val out = allocate(amount, weights.mapIndexed { i, wt -> Weighted("#$i", i, wt) }, increment)
        return weights.indices.map { out.shares.getValue("#$it") }
    }
}

object MoneyMath {
    /** Rounds numerator/denominator half-up to a multiple of [increment]. Non-negative inputs only. */
    fun roundHalfUp(numerator: BigInteger, denominator: BigInteger, increment: Long = 1): Long {
        require(denominator.signum() > 0) { "denominator must be > 0" }
        require(numerator.signum() >= 0) { "numerator must be >= 0" }
        val d = denominator * BigInteger.valueOf(increment)
        val (q, r) = numerator.divideAndRemainder(d).let { it[0] to it[1] }
        val up = r.shiftLeft(1) >= d
        return (if (up) q + BigInteger.ONE else q).toLong() * increment
    }

    fun roundHalfUp(numerator: Long, denominator: Long, increment: Long = 1): Long =
        roundHalfUp(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator), increment)
}
