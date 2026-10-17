package com.github.opscalehub.dong.domain.ledger

import com.github.opscalehub.dong.domain.period.ChargeLine
import kotlinx.serialization.Serializable

/** Fixed account kinds; dimensions (unit, supplier, fund, category, cash account) live on each posting. */
@Serializable
enum class AccountKind { CASH, RECEIVABLE, PAYABLE, FUND_EQUITY, INCOME, EXPENSE }

@Serializable
enum class EntryType {
    OPENING, EXPENSE, SUPPLIER_PAYMENT, CHARGE, RESIDENT_PAYMENT, REFUND, ADJUSTMENT,
    FUND_TRANSFER, CASH_TRANSFER, PERIOD_CORRECTION, REVERSAL,
}

/** One journal line. amount > 0 = debit, < 0 = credit. */
@Serializable
data class Posting(
    val account: AccountKind,
    val amount: Long,
    val cashAccountId: String? = null,
    val unitId: String? = null,
    val supplierId: String? = null,
    val fundId: String? = null,
    val categoryId: String? = null,
)

class LedgerException(message: String) : IllegalArgumentException(message)

/**
 * Double-entry posting rules (see docs/02-domain-model.md §3). Every function
 * returns a balanced list of postings or throws [LedgerException].
 */
object PostingRules {

    private fun positive(amount: Long) {
        if (amount <= 0) throw LedgerException("amount must be > 0")
    }

    fun balanced(p: List<Posting>): List<Posting> {
        if (p.isEmpty()) throw LedgerException("entry has no lines")
        if (p.sumOf { it.amount } != 0L) throw LedgerException("entry does not balance: ${p.sumOf { it.amount }}")
        return p.filter { it.amount != 0L }.ifEmpty { throw LedgerException("entry has only zero lines") }
    }

    /**
     * Opening balances. Unit balances: + owes, − credit. Supplier balances: + building owes.
     * Fund balances: + available. Must satisfy cash + receivables − payables = Σ funds.
     */
    fun opening(cash: Map<String, Long>, units: Map<String, Long>, suppliers: Map<String?, Long>, funds: Map<String, Long>): List<Posting> {
        val p = mutableListOf<Posting>()
        cash.forEach { (id, v) -> p += Posting(AccountKind.CASH, v, cashAccountId = id) }
        units.forEach { (id, v) -> p += Posting(AccountKind.RECEIVABLE, v, unitId = id) }
        suppliers.forEach { (id, v) -> p += Posting(AccountKind.PAYABLE, -v, supplierId = id) }
        funds.forEach { (id, v) -> p += Posting(AccountKind.FUND_EQUITY, -v, fundId = id) }
        return balanced(p)
    }

    fun expenseIncurred(amount: Long, fundId: String, categoryId: String, supplierId: String?): List<Posting> {
        if (amount == 0L) throw LedgerException("amount must not be 0")
        return balanced(listOf(
            Posting(AccountKind.EXPENSE, amount, fundId = fundId, categoryId = categoryId, supplierId = supplierId),
            Posting(AccountKind.PAYABLE, -amount, supplierId = supplierId),
        ))
    }

    fun supplierPayment(amount: Long, supplierId: String?, cashAccountId: String): List<Posting> {
        positive(amount)
        return balanced(listOf(
            Posting(AccountKind.PAYABLE, amount, supplierId = supplierId),
            Posting(AccountKind.CASH, -amount, cashAccountId = cashAccountId),
        ))
    }

    fun charges(lines: List<ChargeLine>): List<Posting> = balanced(lines.flatMap { l ->
        listOf(
            Posting(AccountKind.RECEIVABLE, l.amount, unitId = l.unitId, fundId = l.fundId, categoryId = l.categoryId),
            Posting(AccountKind.INCOME, -l.amount, unitId = l.unitId, fundId = l.fundId, categoryId = l.categoryId),
        )
    })

    fun residentPayment(amount: Long, unitId: String, cashAccountId: String): List<Posting> {
        positive(amount)
        return balanced(listOf(
            Posting(AccountKind.CASH, amount, cashAccountId = cashAccountId, unitId = unitId),
            Posting(AccountKind.RECEIVABLE, -amount, unitId = unitId),
        ))
    }

    fun refund(amount: Long, unitId: String, cashAccountId: String): List<Posting> {
        positive(amount)
        return balanced(listOf(
            Posting(AccountKind.RECEIVABLE, amount, unitId = unitId),
            Posting(AccountKind.CASH, -amount, cashAccountId = cashAccountId, unitId = unitId),
        ))
    }

    /** [amount] > 0 increases what the unit owes (debit); < 0 is a credit to the unit. */
    fun adjustment(amount: Long, unitId: String, fundId: String, categoryId: String?): List<Posting> {
        if (amount == 0L) throw LedgerException("amount must not be 0")
        return balanced(listOf(
            Posting(AccountKind.RECEIVABLE, amount, unitId = unitId, fundId = fundId, categoryId = categoryId),
            Posting(AccountKind.INCOME, -amount, unitId = unitId, fundId = fundId, categoryId = categoryId),
        ))
    }

    fun fundTransfer(amount: Long, fromFundId: String, toFundId: String): List<Posting> {
        positive(amount)
        if (fromFundId == toFundId) throw LedgerException("transfer to the same fund")
        return balanced(listOf(
            Posting(AccountKind.FUND_EQUITY, amount, fundId = fromFundId),
            Posting(AccountKind.FUND_EQUITY, -amount, fundId = toFundId),
        ))
    }

    fun cashTransfer(amount: Long, fromCashId: String, toCashId: String): List<Posting> {
        positive(amount)
        if (fromCashId == toCashId) throw LedgerException("transfer to the same cash account")
        return balanced(listOf(
            Posting(AccountKind.CASH, amount, cashAccountId = toCashId),
            Posting(AccountKind.CASH, -amount, cashAccountId = fromCashId),
        ))
    }

    fun reversal(original: List<Posting>): List<Posting> = balanced(original.map { it.copy(amount = -it.amount) })
}

/** Balance queries over a set of postings, using the sign conventions of docs/02 §2. */
class Balances(private val postings: List<Posting>) {
    /** + = unit owes, − = unit has credit. */
    fun unit(unitId: String): Long = postings.filter { it.account == AccountKind.RECEIVABLE && it.unitId == unitId }.sumOf { it.amount }
    fun cash(cashAccountId: String? = null): Long =
        postings.filter { it.account == AccountKind.CASH && (cashAccountId == null || it.cashAccountId == cashAccountId) }.sumOf { it.amount }
    /** + = building owes the supplier. */
    fun payable(supplierId: String? = null, all: Boolean = supplierId == null): Long =
        -postings.filter { it.account == AccountKind.PAYABLE && (all || it.supplierId == supplierId) }.sumOf { it.amount }
    /** + = money available to the fund. */
    fun fund(fundId: String): Long = -postings.filter {
        it.fundId == fundId && it.account in setOf(AccountKind.FUND_EQUITY, AccountKind.INCOME, AccountKind.EXPENSE)
    }.sumOf { it.amount }

    val receivables: Long get() = postings.filter { it.account == AccountKind.RECEIVABLE }.sumOf { it.amount }
    /** Σ positive unit balances: what residents owe in total. */
    fun outstandingDebts(unitIds: Collection<String>): Long = unitIds.sumOf { unit(it).coerceAtLeast(0) }
    fun fundsTotal(fundIds: Collection<String>): Long = fundIds.sumOf { fund(it) }
    val trialBalance: Long get() = postings.sumOf { it.amount }
    val netAssets: Long get() = cash() + receivables - payable()
    fun expensesByCategory(): Map<String?, Long> =
        postings.filter { it.account == AccountKind.EXPENSE }.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amount } }
    fun incomeByCategory(): Map<String?, Long> =
        postings.filter { it.account == AccountKind.INCOME }.groupBy { it.categoryId }.mapValues { (_, l) -> -l.sumOf { it.amount } }
}
