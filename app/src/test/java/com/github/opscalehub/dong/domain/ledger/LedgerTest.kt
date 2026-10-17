package com.github.opscalehub.dong.domain.ledger

import com.github.opscalehub.dong.domain.period.ChargeLine
import com.github.opscalehub.dong.domain.period.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LedgerTest {
    private val cash = "cash"
    private val op = "op"
    private val res = "res"
    private val journal = mutableListOf<Posting>()
    private fun post(p: List<Posting>) {
        assertEquals("entry must balance", 0L, p.sumOf { it.amount })
        journal += p
    }
    private val b get() = Balances(journal)

    private fun assertInvariants() {
        assertEquals(0L, b.trialBalance)
        assertEquals(b.netAssets, b.fundsTotal(listOf(op, res)))
    }

    @Test
    fun `opening balances and closing cash`() {
        post(PostingRules.opening(mapOf(cash to 60_000_000), mapOf("1-2" to 1_200_000, "3-1" to -500_000), emptyMap(), mapOf(op to 20_700_000, res to 40_000_000)))
        assertEquals(60_000_000L, b.cash())
        assertEquals(1_200_000L, b.unit("1-2"))
        assertEquals(-500_000L, b.unit("3-1"))
        assertEquals(40_000_000L, b.fund(res))
        assertInvariants()
        assertThrows(LedgerException::class.java) { PostingRules.opening(mapOf(cash to 1), emptyMap(), emptyMap(), mapOf(op to 2)) }
    }

    @Test
    fun `charges, partial payment, overpayment and debts`() {
        post(PostingRules.charges(listOf(
            ChargeLine("1-1", SourceType.EXPENSE, "e1", "clean", op, 1_000_000),
            ChargeLine("1-2", SourceType.EXPENSE, "e1", "clean", op, 1_000_000),
        )))
        post(PostingRules.residentPayment(400_000, "1-1", cash)) // partial
        post(PostingRules.residentPayment(1_300_000, "1-2", cash)) // overpayment
        assertEquals(600_000L, b.unit("1-1"))
        assertEquals(-300_000L, b.unit("1-2"))
        assertEquals(600_000L, b.outstandingDebts(listOf("1-1", "1-2")))
        assertEquals(1_700_000L, b.cash())
        // Payments are not income: income equals the charges only.
        assertEquals(2_000_000L, b.incomeByCategory()["clean"])
        assertInvariants()
    }

    @Test
    fun `expense incurred and supplier payments`() {
        post(PostingRules.opening(mapOf(cash to 10_000_000), emptyMap(), emptyMap(), mapOf(op to 10_000_000)))
        post(PostingRules.expenseIncurred(12_000_000, op, "maint", "sup"))
        assertEquals(12_000_000L, b.payable("sup"))
        post(PostingRules.supplierPayment(6_000_000, "sup", cash))
        assertEquals(6_000_000L, b.payable("sup"))
        assertEquals(4_000_000L, b.cash())
        assertEquals(-2_000_000L, b.fund(op)) // expense not yet charged to residents
        assertEquals(12_000_000L, b.expensesByCategory()["maint"])
        assertInvariants()
    }

    @Test
    fun `refunds and adjustments`() {
        post(PostingRules.opening(mapOf(cash to 1_000_000), mapOf("3-1" to -500_000), emptyMap(), mapOf(op to 500_000)))
        post(PostingRules.refund(500_000, "3-1", cash))
        assertEquals(0L, b.unit("3-1"))
        assertEquals(500_000L, b.cash())
        post(PostingRules.adjustment(-300_000, "1-2", op, "clean")) // credit
        assertEquals(-300_000L, b.unit("1-2"))
        post(PostingRules.adjustment(100_000, "1-2", op, "clean")) // debit
        assertEquals(-200_000L, b.unit("1-2"))
        assertInvariants()
    }

    @Test
    fun `reserve contributions and transfers are not expenses`() {
        post(PostingRules.opening(mapOf(cash to 5_000_000), emptyMap(), emptyMap(), mapOf(op to 5_000_000)))
        post(PostingRules.charges(listOf(ChargeLine("1-1", SourceType.CHARGE_ITEM, "c1", "reserve", res, 1_000_000))))
        post(PostingRules.fundTransfer(2_000_000, op, res))
        assertEquals(3_000_000L, b.fund(res))
        assertEquals(3_000_000L, b.fund(op))
        assertEquals(emptyMap<String?, Long>(), b.expensesByCategory())
        assertEquals(5_000_000L, b.cash()) // fund transfer moves no cash
        post(PostingRules.expenseIncurred(1_500_000, res, "emergency", null))
        assertEquals(1_500_000L, b.fund(res))
        assertInvariants()
    }

    @Test
    fun `reversal nets to zero`() {
        val p = PostingRules.residentPayment(700_000, "2-1", cash)
        post(p)
        post(PostingRules.reversal(p))
        assertEquals(0L, b.unit("2-1"))
        assertEquals(0L, b.cash())
    }

    @Test
    fun `cash transfer between accounts`() {
        post(PostingRules.opening(mapOf(cash to 5_000_000), emptyMap(), emptyMap(), mapOf(op to 5_000_000)))
        post(PostingRules.cashTransfer(2_000_000, cash, "bank"))
        assertEquals(3_000_000L, b.cash(cash))
        assertEquals(2_000_000L, b.cash("bank"))
        assertEquals(5_000_000L, b.cash())
        assertInvariants()
    }

    @Test
    fun `invalid postings rejected`() {
        assertThrows(LedgerException::class.java) { PostingRules.residentPayment(0, "u", cash) }
        assertThrows(LedgerException::class.java) { PostingRules.residentPayment(-5, "u", cash) }
        assertThrows(LedgerException::class.java) { PostingRules.fundTransfer(5, op, op) }
        assertThrows(LedgerException::class.java) { PostingRules.balanced(listOf(Posting(AccountKind.CASH, 5))) }
    }
}
