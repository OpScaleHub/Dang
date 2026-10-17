package com.github.opscalehub.dong.domain.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AllocatorTest {

    private fun w(vararg weights: Long) = weights.mapIndexed { i, x -> Weighted("u$i", i, x) }

    @Test
    fun `equal split without remainder`() {
        val r = Allocator.allocate(900, w(1, 1, 1))
        assertEquals(listOf(300L, 300L, 300L), r.shares.values.toList())
    }

    @Test
    fun `remainder goes to largest fractional parts then sort order`() {
        // 100 / 3 = 33.33 each; leftover 1 goes to the first unit by sort order (tie).
        val r = Allocator.allocate(100, w(1, 1, 1))
        assertEquals(listOf(34L, 33L, 33L), r.shares.values.toList())
        assertEquals(100L, r.total)
    }

    @Test
    fun `largest remainder beats sort order`() {
        // weights 1,2,4 of 7: raw 1.428, 2.857, 5.714 → floors 1,2,5 (=8), leftover 2 → u1 (.857), u2 (.714)
        val r = Allocator.allocate(10, w(1, 2, 4))
        assertEquals(listOf(1L, 3L, 6L), r.shares.values.toList())
    }

    @Test
    fun `rounding increment keeps shares on multiples and sum exact`() {
        // 1,000,001 split 3 ways at increment 1000: floors 333,000 ×3 = 999,000, leftover 1,001 →
        // one increment to the first unit + residue 1 to the same (rank 1) unit.
        val r = Allocator.allocate(1_000_001, w(1, 1, 1), increment = 1000)
        assertEquals(listOf(334_001L, 333_000L, 333_000L), r.shares.values.toList())
        assertEquals(1_000_001L, r.total)
        assertEquals(1_001L, r.details[0].roundingAdjustment)
    }

    @Test
    fun `zero weight units get nothing`() {
        val r = Allocator.allocate(1000, w(0, 1, 3))
        assertEquals(listOf(0L, 250L, 750L), r.shares.values.toList())
    }

    @Test
    fun `large amounts do not overflow`() {
        val amount = 9_000_000_000_000L
        val r = Allocator.allocate(amount, w(9_000_000, 3_000_000, 1))
        assertEquals(amount, r.total)
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Allocator.allocate(100, w(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { Allocator.allocate(100, w(1, -1)) }
        assertThrows(IllegalArgumentException::class.java) { Allocator.allocate(-1, w(1)) }
        assertThrows(IllegalArgumentException::class.java) { Allocator.allocate(100, w(1), increment = 0) }
    }

    @Test
    fun `property - sum always equals amount`() {
        val rnd = java.util.Random(42)
        repeat(2_000) {
            val n = 1 + rnd.nextInt(12)
            val weights = (0 until n).map { rnd.nextInt(1000).toLong() }.toMutableList()
            if (weights.sum() == 0L) weights[0] = 1
            val amount = rnd.nextInt(50_000_000).toLong()
            val inc = listOf(1L, 10L, 1000L, 7L)[rnd.nextInt(4)]
            val r = Allocator.allocate(amount, w(*weights.toLongArray()), inc)
            assertEquals(amount, r.total)
            // every share except the rank-1 unit is a multiple of the increment
            assertEquals(true, r.shares.values.count { it % inc != 0L } <= 1)
        }
    }

    @Test
    fun `round half up`() {
        assertEquals(3L, MoneyMath.roundHalfUp(5, 2))
        assertEquals(2L, MoneyMath.roundHalfUp(7, 4))
        assertEquals(2000L, MoneyMath.roundHalfUp(1500, 1, 1000))
        assertEquals(1000L, MoneyMath.roundHalfUp(1499, 1, 1000))
    }
}
