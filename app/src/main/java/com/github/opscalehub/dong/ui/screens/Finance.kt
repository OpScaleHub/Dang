package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.db.CashAccountEntity
import com.github.opscalehub.dong.data.db.CategoryEntity
import com.github.opscalehub.dong.data.db.FundEntity
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.JournalEntryEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.domain.ledger.EntryType
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.BalanceText
import com.github.opscalehub.dong.ui.ClickRow
import com.github.opscalehub.dong.ui.DateInput
import com.github.opscalehub.dong.ui.Dropdown
import com.github.opscalehub.dong.ui.FormDialog
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.KeyValue
import com.github.opscalehub.dong.ui.NumberInput
import com.github.opscalehub.dong.ui.NumberFieldState
import com.github.opscalehub.dong.ui.ReasonDialog
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.money
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SingleChoice
import com.github.opscalehub.dong.ui.TextInput
import com.github.opscalehub.dong.ui.rememberDate
import com.github.opscalehub.dong.ui.rememberMoney

private enum class Action { PAYMENT, REFUND, ADJUST, FUND_TRANSFER, CASH_TRANSFER, OPENING }

private val reversible = setOf(EntryType.OPENING, EntryType.SUPPLIER_PAYMENT, EntryType.RESIDENT_PAYMENT, EntryType.REFUND, EntryType.ADJUSTMENT, EntryType.FUND_TRANSFER, EntryType.CASH_TRANSFER)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FinanceScreen(vm: AppViewModel, nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val units by vm.units.collectAsState()
    val entries by vm.entries.collectAsState()
    val balances by derived(vm, building.id) { vm.repos.finance.balances(building.id) }
    val amounts by derived(vm, building.id) { vm.repos.db.financeDao().lines(building.id).groupBy { it.entryId }.mapValues { (_, l) -> l.filter { it.amountMinor > 0 }.sumOf { it.amountMinor } } }
    var action by remember { mutableStateOf<Action?>(null) }
    var reversing by remember { mutableStateOf<JournalEntryEntity?>(null) }
    val reversed = entries.mapNotNull { it.reversesEntryId }.toSet()
    val hasOpening = entries.any { it.type == EntryType.OPENING && it.id !in reversed }
    val digits = building.minorDigits

    ScreenList {
        item {
            SectionCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { action = Action.PAYMENT }) { Text(S.newPayment) }
                    OutlinedButton(onClick = { action = Action.ADJUST }) { Text(S.adjustment) }
                    OutlinedButton(onClick = { action = Action.REFUND }) { Text(S.refund) }
                    OutlinedButton(onClick = { action = Action.FUND_TRANSFER }) { Text(S.fundTransfer) }
                    OutlinedButton(onClick = { action = Action.CASH_TRANSFER }) { Text(S.cashTransfer) }
                    if (!hasOpening) OutlinedButton(onClick = { action = Action.OPENING }) { Text(S.opening) }
                }
                Hint(S.paymentNotIncome)
            }
        }
        item {
            SectionCard(S.unitBalances, subtitle = S.signConvention) {
                units.forEach { u ->
                    ClickRow({ nav.navigate(Routes.unit(u.id)) }) {
                        Text("${S.unit} ${u.label}", Modifier.weight(1f))
                        balances?.units?.get(u.id)?.let { BalanceText(it, digits) }
                    }
                }
                balances?.let {
                    KeyValue(S.outstandingDebts, money(it.outstandingDebts), strong = true)
                    KeyValue(S.residentCredits, money(it.residentCredits))
                }
            }
        }
        item { Text(S.journal, style = MaterialTheme.typography.titleMedium) }
        items(entries.size) { i ->
            val e = entries[i]
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Fa.entryType(e.type) + if (e.id in reversed) "، ${S.reversedBadge}" else "", style = MaterialTheme.typography.bodyMedium)
                        Hint(listOfNotNull(Fmt.date(e.entryDate, building.calendar), e.unitId?.let { id -> "${S.unit} ${units.firstOrNull { it.id == id }?.label ?: ""}" },
                            e.memo.takeIf { it.isNotBlank() }, e.method.takeIf { it.isNotBlank() }, e.reference.takeIf { it.isNotBlank() }).joinToString("، "))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(amounts?.get(e.id) ?: 0), style = MaterialTheme.typography.titleSmall)
                        if (e.type in reversible && e.id !in reversed) TextButton(onClick = { reversing = e }) { Text(S.reverse) }
                    }
                }
            }
        }
    }

    when (action) {
        Action.PAYMENT -> PaymentDialog(vm, building.id, units, building.calendar, digits) { action = null }
        Action.REFUND -> RefundDialog(vm, building.id, units, building.calendar, digits) { action = null }
        Action.ADJUST -> AdjustDialog(vm, building.id, units, building.calendar, digits) { action = null }
        Action.FUND_TRANSFER -> TransferDialog(vm, building.id, building.calendar, digits, cash = false) { action = null }
        Action.CASH_TRANSFER -> TransferDialog(vm, building.id, building.calendar, digits, cash = true) { action = null }
        Action.OPENING -> OpeningDialog(vm, building.id, units, building.calendar, digits) { action = null }
        null -> Unit
    }
    reversing?.let { e ->
        ReasonDialog(S.reverse, S.reverseHint, { reversing = null }) { reason ->
            vm.run(S.done, onSuccess = { reversing = null }) { vm.repos.finance.reverse(e.id, reason) }
        }
    }
}

@Composable
fun PaymentDialog(vm: AppViewModel, b: String, units: List<UnitEntity>, cal: com.github.opscalehub.dong.domain.model.CalendarSystem, @Suppress("UNUSED_PARAMETER") digits: Int, preset: UnitEntity? = null, close: () -> Unit) {
    val cash by vm.cashAccounts.collectAsState()
    var unit by remember { mutableStateOf(preset) }
    val amount = rememberMoney(null)
    var account by remember { mutableStateOf<CashAccountEntity?>(cash.firstOrNull()) }
    val date = rememberDate(vm.today, cal)
    var method by remember { mutableStateOf(S.methods.first()) }
    var ref by rememberSaveable { mutableStateOf("") }
    FormDialog(S.newPayment, close, {
        vm.run(S.saved, onSuccess = close) { vm.repos.finance.receivePayment(b, unit!!.id, amount.value!!, account!!.id, date.value!!, ref, method = method) }
    }, canConfirm = unit != null && (amount.value ?: 0) > 0 && account != null && date.value != null) {
        Hint(S.paymentNotIncome)
        Dropdown(S.unit, units.filter { it.active }, unit, { unit = it }, { it.label })
        NumberInput(S.amount, amount)
        Dropdown(S.cashAccount, cash, account, { account = it }, { it.name })
        DateInput(S.date, date, vm.today)
        Text(S.method, style = MaterialTheme.typography.labelMedium)
        SingleChoice(S.methods, method, { method = it }) { it }
        TextInput(S.reference, ref, { ref = it })
    }
}

@Composable
private fun RefundDialog(vm: AppViewModel, b: String, units: List<UnitEntity>, cal: com.github.opscalehub.dong.domain.model.CalendarSystem, digits: Int, close: () -> Unit) {
    val cash by vm.cashAccounts.collectAsState()
    var unit by remember { mutableStateOf<UnitEntity?>(null) }
    val amount = rememberMoney(null)
    var account by remember { mutableStateOf<CashAccountEntity?>(cash.firstOrNull()) }
    val date = rememberDate(vm.today, cal)
    var reason by rememberSaveable { mutableStateOf("") }
    FormDialog(S.refund, close, {
        vm.run(S.saved, onSuccess = close) { vm.repos.finance.refund(b, unit!!.id, amount.value!!, account!!.id, date.value!!, reason) }
    }, canConfirm = unit != null && (amount.value ?: 0) > 0 && account != null && date.value != null && reason.isNotBlank()) {
        Dropdown(S.unit, units, unit, { unit = it }, { it.label })
        NumberInput(S.amount, amount)
        Dropdown(S.cashAccount, cash, account, { account = it }, { it.name })
        DateInput(S.date, date, vm.today)
        TextInput(S.reasonRequiredHint, reason, { reason = it })
    }
}

@Composable
private fun AdjustDialog(vm: AppViewModel, b: String, units: List<UnitEntity>, cal: com.github.opscalehub.dong.domain.model.CalendarSystem, digits: Int, close: () -> Unit) {
    val funds by vm.funds.collectAsState()
    val categories by vm.categories.collectAsState()
    var unit by remember { mutableStateOf<UnitEntity?>(null) }
    var credit by remember { mutableStateOf(true) }
    val amount = rememberMoney(null)
    var fund by remember { mutableStateOf<FundEntity?>(funds.firstOrNull { it.kind == FundKind.OPERATING }) }
    var category by remember { mutableStateOf<CategoryEntity?>(null) }
    val date = rememberDate(vm.today, cal)
    var reason by rememberSaveable { mutableStateOf("") }
    FormDialog(S.adjustment, close, {
        val a = amount.value!!
        vm.run(S.saved, onSuccess = close) { vm.repos.finance.adjust(b, unit!!.id, if (credit) -a else a, fund!!.id, category?.id, date.value!!, reason) }
    }, canConfirm = unit != null && (amount.value ?: 0) > 0 && fund != null && date.value != null && reason.isNotBlank()) {
        Dropdown(S.unit, units, unit, { unit = it }, { it.label })
        Text(S.adjustmentDirection, style = MaterialTheme.typography.labelMedium)
        SingleChoice(listOf(true, false), credit, { credit = it }) { if (it) S.creditUnit else S.debitUnit }
        NumberInput(S.amount, amount)
        Dropdown(S.fund, funds, fund, { fund = it }, { it.name })
        Dropdown(S.category, listOf<CategoryEntity?>(null) + categories, category, { category = it }, { it?.name ?: S.none })
        DateInput(S.date, date, vm.today)
        TextInput(S.reasonRequiredHint, reason, { reason = it })
    }
}

@Composable
private fun TransferDialog(vm: AppViewModel, b: String, cal: com.github.opscalehub.dong.domain.model.CalendarSystem, digits: Int, cash: Boolean, close: () -> Unit) {
    val funds by vm.funds.collectAsState()
    val accounts by vm.cashAccounts.collectAsState()
    val options: List<Pair<String, String>> = if (cash) accounts.map { it.id to it.name } else funds.map { it.id to it.name }
    var from by remember { mutableStateOf(options.firstOrNull()) }
    var to by remember { mutableStateOf(options.getOrNull(1)) }
    val amount = rememberMoney(null)
    val date = rememberDate(vm.today, cal)
    var memo by rememberSaveable { mutableStateOf("") }
    FormDialog(if (cash) S.cashTransfer else S.fundTransfer, close, {
        vm.run(S.saved, onSuccess = close) {
            if (cash) vm.repos.finance.transferCash(b, from!!.first, to!!.first, amount.value!!, date.value!!, memo)
            else vm.repos.finance.transferFunds(b, from!!.first, to!!.first, amount.value!!, date.value!!, memo)
        }
    }, canConfirm = from != null && to != null && from != to && (amount.value ?: 0) > 0 && date.value != null) {
        Dropdown(S.from, options, from, { from = it }, { it.second })
        Dropdown(S.to, options, to, { to = it }, { it.second })
        NumberInput(S.amount, amount)
        DateInput(S.date, date, vm.today)
        TextInput(S.notes, memo, { memo = it })
    }
}

@Composable
private fun OpeningDialog(vm: AppViewModel, b: String, units: List<UnitEntity>, cal: com.github.opscalehub.dong.domain.model.CalendarSystem, digits: Int, close: () -> Unit) {
    val funds by vm.funds.collectAsState()
    val accounts by vm.cashAccounts.collectAsState()
    val date = rememberDate(vm.today, cal)
    val cash = accounts.associate { it.id to rememberMoney(0) }
    val others = funds.filter { it.kind != FundKind.OPERATING }.associate { it.id to rememberMoney(0) }
    val unitFields: Map<String, NumberFieldState> = units.associate { it.id to rememberMoney(0) }
    val all = cash.values + others.values + unitFields.values
    FormDialog(S.opening, close, {
        vm.run(S.saved, onSuccess = close) {
            vm.repos.finance.postOpening(b, date.value!!, cash.mapValues { it.value.value!! }, unitFields.mapValues { it.value.value!! }, emptyMap(), others.mapValues { it.value.value!! })
        }
    }, canConfirm = date.value != null && all.all { it.valid }) {
        Hint(S.openingHint)
        DateInput(S.date, date, vm.today)
        accounts.forEach { a -> NumberInput("${S.openingCash}: ${a.name}", cash.getValue(a.id)) }
        funds.filter { it.kind != FundKind.OPERATING }.forEach { f -> NumberInput("${S.openingReserve}: ${f.name}", others.getValue(f.id)) }
        Text(S.openingUnits, style = MaterialTheme.typography.labelLarge)
        units.forEach { u -> NumberInput("${S.unit} ${u.label}", unitFields.getValue(u.id)) }
    }
}
