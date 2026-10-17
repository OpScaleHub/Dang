package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.github.opscalehub.dong.data.db.CategoryEntity
import com.github.opscalehub.dong.data.db.ChargeItemEntity
import com.github.opscalehub.dong.data.db.ExpenseEntity
import com.github.opscalehub.dong.data.db.ExpenseStatus
import com.github.opscalehub.dong.data.db.FundEntity
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.PeriodEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.data.db.SupplierEntity
import com.github.opscalehub.dong.data.db.WaterTariffEntity
import com.github.opscalehub.dong.data.newId
import com.github.opscalehub.dong.data.policy
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.domain.allocation.CommonWater
import com.github.opscalehub.dong.domain.ledger.EntryType
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.Badge
import com.github.opscalehub.dong.ui.ClickRow
import com.github.opscalehub.dong.ui.DateInput
import com.github.opscalehub.dong.ui.Dropdown
import com.github.opscalehub.dong.ui.FormDialog
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.KeyValue
import com.github.opscalehub.dong.ui.NumberInput
import com.github.opscalehub.dong.ui.PolicyEditor
import com.github.opscalehub.dong.ui.ReasonDialog
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.money
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SingleChoice
import com.github.opscalehub.dong.ui.SwitchRow
import com.github.opscalehub.dong.ui.TextInput
import com.github.opscalehub.dong.ui.policySummary
import com.github.opscalehub.dong.ui.rememberDate
import com.github.opscalehub.dong.ui.rememberMoney
import com.github.opscalehub.dong.ui.rememberPolicyEditor

@Composable
fun ExpensesScreen(vm: AppViewModel, nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val expenses by vm.expenses.collectAsState()
    val categories by vm.categories.collectAsState()
    val periods by vm.periods.collectAsState()
    val paid by derived(vm, expenses.size) { expenses.associate { it.id to vm.repos.expenses.paid(it.id) } }

    WithFab(S.newExpense, { nav.navigate(Routes.expense("new")) }) {
        ScreenList {
            item {
                SectionCard {
                    Hint(S.expenseVsPayment)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.navigate(Routes.CATEGORIES) }) { Text(S.categories) }
                        OutlinedButton(onClick = { nav.navigate(Routes.CHARGE_ITEMS) }) { Text(S.chargeItems) }
                    }
                }
            }
            if (expenses.isEmpty()) item { SectionCard { Hint(S.empty) } }
            items(expenses.size) { i ->
                val e = expenses[i]
                val p = paid?.get(e.id) ?: 0
                SectionCard {
                    ClickRow({ nav.navigate(Routes.expense(e.id)) }) {
                        Column(Modifier.weight(1f)) {
                            Text(e.description, style = MaterialTheme.typography.titleMedium)
                            Hint("${Fmt.date(e.date, building.calendar)}، ${categories.firstOrNull { it.id == e.categoryId }?.name ?: ""}، ${periods.firstOrNull { it.id == e.periodId }?.name ?: S.noPeriodAssigned}")
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(e.amountMinor), style = MaterialTheme.typography.titleSmall)
                            Hint(paymentStatus(e, p))
                        }
                    }
                }
            }
        }
    }
}

fun paymentStatus(e: ExpenseEntity, paid: Long) = when {
    e.status == ExpenseStatus.VOIDED -> S.voided
    paid == 0L -> S.unpaid
    paid < e.amountMinor -> S.partiallyPaid
    else -> S.fullyPaid
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ExpenseScreen(vm: AppViewModel, nav: NavController, expenseId: String) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val expenses by vm.expenses.collectAsState()
    val categories by vm.categories.collectAsState()
    val suppliers by vm.suppliers.collectAsState()
    val funds by vm.funds.collectAsState()
    val cash by vm.cashAccounts.collectAsState()
    val periods by vm.periods.collectAsState()
    val units by vm.units.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val existing = expenses.firstOrNull { it.id == expenseId }
    if (expenseId != "new" && existing == null) return
    val period = periods.firstOrNull { it.id == existing?.periodId }
    val locked = existing != null && (existing.status == ExpenseStatus.VOIDED || period?.status == PeriodStatus.FINALIZED)
    val drafts = periods.filter { it.status == PeriodStatus.DRAFT }

    val date = rememberDate(existing?.date ?: vm.today, building.calendar)
    var description by rememberSaveable { mutableStateOf(existing?.description ?: "") }
    var category by remember { mutableStateOf(categories.firstOrNull { it.id == existing?.categoryId }) }
    val amount = rememberMoney(existing?.amountMinor)
    var supplier by remember { mutableStateOf(suppliers.firstOrNull { it.id == existing?.supplierId }) }
    var fund by remember { mutableStateOf(funds.firstOrNull { it.id == existing?.fundId }) }
    var periodSel by remember { mutableStateOf<PeriodEntity?>(period ?: drafts.firstOrNull { (existing?.date ?: vm.today) in it.startDate..it.endDate }) }
    var docRef by rememberSaveable { mutableStateOf(existing?.documentRef ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var payNow by rememberSaveable { mutableStateOf(false) }
    val policy = rememberPolicyEditor(existing?.policy() ?: AllocationPolicy())
    var policyTouched by remember { mutableStateOf(existing != null) }
    var advanced by remember { mutableStateOf(false) }
    var addingSupplier by remember { mutableStateOf(false) }
    var voiding by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    val paid by derived(vm, existing?.id) { existing?.let { vm.repos.expenses.paid(it.id) } ?: 0L }
    val payments by derived(vm, existing?.id) { existing?.let { vm.repos.db.financeDao().entriesForExpense(it.id) } ?: emptyList() }

    // Selecting a category copies its default policy and fund (snapshot; later category edits don't change this expense).
    LaunchedEffect(category?.id) {
        val c = category ?: return@LaunchedEffect
        if (!policyTouched) policy.reset(c.policy())
        if (existing == null) fund = funds.firstOrNull { it.id == c.defaultFundId } ?: funds.firstOrNull { it.kind == FundKind.OPERATING }
        policyTouched = false
    }
    LaunchedEffect(funds) { if (fund == null) fund = funds.firstOrNull { it.id == existing?.fundId } ?: funds.firstOrNull { it.kind == FundKind.OPERATING } }
    // Lists may still be loading when an existing expense is opened.
    LaunchedEffect(categories, suppliers, periods) {
        if (existing == null) return@LaunchedEffect
        if (category == null) category = categories.firstOrNull { it.id == existing.categoryId }
        if (supplier == null) supplier = suppliers.firstOrNull { it.id == existing.supplierId }
        if (periodSel == null) periodSel = periods.firstOrNull { it.id == existing.periodId }
    }

    val pol = policy.toPolicy()
    val valid = date.value != null && description.isNotBlank() && category != null && (amount.value ?: 0) != 0L && fund != null && pol != null

    ScreenList {
        if (locked) item { SectionCard { Hint(if (existing?.status == ExpenseStatus.VOIDED) S.voided else S.lockedByFinalized, MaterialTheme.colorScheme.error) } }
        item {
            SectionCard(if (existing == null) S.newExpense else S.editExpense, subtitle = S.expenseVsPayment) {
                DateInput(S.date, date, vm.today)
                TextInput(S.description, description, { description = it }, error = if (description.isBlank()) S.required else null)
                Dropdown(S.category, categories.filter { it.active || it.id == existing?.categoryId }, category, { category = it }, { it.name })
                NumberInput(S.amount, amount, supporting = building.currencyName)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dropdown(S.supplier, listOf<SupplierEntity?>(null) + suppliers, supplier, { supplier = it }, { it?.name ?: S.none }, Modifier.weight(1f))
                    TextButton(onClick = { addingSupplier = true }) { Text(S.add) }
                }
                Dropdown(S.fund, funds, fund, { fund = it }, { it.name })
                Dropdown(S.period, listOf<PeriodEntity?>(null) + drafts, periodSel, { periodSel = it }, { it?.name ?: S.noPeriodAssigned })
                TextInput(S.documentRef, docRef, { docRef = it })
                TextInput(S.notes, notes, { notes = it }, singleLine = false)
                if (existing == null) SwitchRow(S.paidNow, payNow, { payNow = it })
            }
        }
        item {
            SectionCard(S.presets) {
                // Presets set the whole policy in one tap; the full editor stays under «پیشرفته».
                val last = expenses.filter { it.categoryId == category?.id && it.id != existing?.id && it.status == ExpenseStatus.ACTIVE }.maxByOrNull { it.createdAt }
                val presets = buildList {
                    category?.let { add(S.presetCategory to it.policy()) }
                    add(S.presetEqual to AllocationPolicy.single(Basis.Equal))
                    add(S.presetOccupants to AllocationPolicy.single(Basis.Occupants))
                    add(S.presetArea to AllocationPolicy.single(Basis.Area))
                    add(S.presetWater to AllocationPolicy.single(Basis.Consumption(CommonWater.Separate(com.github.opscalehub.dong.domain.allocation.SimpleBasis.EQUAL))))
                    last?.let { add(S.presetLast to it.policy()) }
                    add(S.presetFund to AllocationPolicy.FUND_ONLY)
                }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (label, p) ->
                        androidx.compose.material3.FilterChip(selected = pol == p, onClick = { policy.reset(p); policyTouched = true }, label = { Text(label) })
                    }
                }
                Text(pol?.let { policySummary(it) } ?: S.invalidNumber, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { advanced = !advanced }) { Text(S.advanced) }
                if (advanced) PolicyEditor(policy, units, spaces)
            }
        }
        item {
            val amt = amount.value
            val preview by derived(vm, amt, pol, periodSel?.id) {
                if (amt == null || amt == 0L || pol == null) null else vm.repos.expenses.previewAllocation(building.id, periodSel?.id, amt, pol)
            }
            SectionCard(S.whoPays) {
                val pv = preview
                when {
                    amt == null || amt == 0L -> Hint(S.previewNeedsAmount)
                    pol?.fundOnly == true -> Hint(S.fundOnly)
                    pv == null -> Hint(S.empty)
                    !pv.valid -> pv.issues.filter { it.blocking }.forEach { Hint(Fa.issue(it, vm.names()), MaterialTheme.colorScheme.error) }
                    else -> com.github.opscalehub.dong.ui.charts.HBars(
                        units.filter { it.id in pv.shares }.map { u -> com.github.opscalehub.dong.ui.charts.HBar("${S.unit} ${u.label}", pv.shares.getValue(u.id), money(pv.shares.getValue(u.id))) },
                    )
                }
                if (pv != null && pv.fundBorne != 0L && pol?.fundOnly != true) Hint("${S.fundBorne}: ${money(pv.fundBorne)}")
            }
        }
        if (!locked) {
            item {
                Button(onClick = {
                    val e = (existing ?: ExpenseEntity(newId(), building.id, policyJson = "", categoryId = "", fundId = "", date = 0, description = "", amountMinor = 0, createdAt = 0)).copy(
                        date = date.value!!, description = description.trim(), categoryId = category!!.id, amountMinor = amount.value!!,
                        supplierId = supplier?.id, fundId = fund!!.id, periodId = periodSel?.id, documentRef = docRef, notes = notes, policyJson = pol!!.encode(),
                    )
                    vm.run(S.saved, onSuccess = { nav.popBackStack() }) {
                        if (existing == null) vm.repos.expenses.create(e, if (payNow) cash.firstOrNull()?.id else null) else vm.repos.expenses.update(e)
                    }
                }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text(S.save) }
            }
        }
        if (existing != null) {
            item {
                SectionCard(S.paySupplier) {
                    KeyValue(S.paid, money(paid ?: 0))
                    KeyValue(S.outstanding, money(if (existing.status == ExpenseStatus.ACTIVE) existing.amountMinor - (paid ?: 0) else 0), strong = true)
                    payments?.filter { it.type == EntryType.SUPPLIER_PAYMENT }?.forEach { Hint("${Fmt.date(it.entryDate, building.calendar)}، ${it.reference}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (existing.status == ExpenseStatus.ACTIVE && (paid ?: 0) < existing.amountMinor) OutlinedButton(onClick = { paying = true }) { Text(S.paySupplier) }
                        if (!locked) OutlinedButton(onClick = { voiding = true }) { Text(S.voidExpense) }
                    }
                }
            }
        }
    }
    if (addingSupplier) {
        var name by rememberSaveable { mutableStateOf("") }
        FormDialog(S.newSupplier, { addingSupplier = false }, {
            val s = SupplierEntity(newId(), building.id, name)
            vm.run(S.saved, onSuccess = { supplier = s; addingSupplier = false }) { vm.repos.expenses.saveSupplier(s) }
        }, canConfirm = name.isNotBlank()) { TextInput(S.name, name, { name = it }) }
    }
    if (voiding && existing != null) {
        ReasonDialog(S.voidExpense, S.reverseHint, { voiding = false }) { reason ->
            vm.run(S.done, onSuccess = { voiding = false; nav.popBackStack() }) { vm.repos.expenses.void(existing.id, reason) }
        }
    }
    if (paying && existing != null) {
        val amt = rememberMoney(existing.amountMinor - (paid ?: 0))
        var from by remember { mutableStateOf(cash.firstOrNull()) }
        val d = rememberDate(vm.today, building.calendar)
        var ref by rememberSaveable { mutableStateOf("") }
        FormDialog(S.paySupplier, { paying = false }, {
            vm.run(S.saved, onSuccess = { paying = false }) { vm.repos.expenses.paySupplier(existing.id, amt.value!!, from!!.id, d.value!!, ref) }
        }, canConfirm = (amt.value ?: 0) > 0 && from != null && d.value != null) {
            NumberInput(S.amount, amt)
            Dropdown(S.payFrom, cash, from, { from = it }, { it.name })
            DateInput(S.date, d, vm.today)
            TextInput(S.reference, ref, { ref = it })
        }
    }
}

@Composable
fun CategoriesScreen(vm: AppViewModel) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val categories by vm.categories.collectAsState()
    val funds by vm.funds.collectAsState()
    val units by vm.units.collectAsState()
    val spaces by vm.spaces.collectAsState()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    WithFab(S.newCategory, { creating = true }) {
        ScreenList {
            item { SectionCard { Hint(S.policyChangeNote) } }
            items(categories.size) { i ->
                val c = categories[i]
                SectionCard {
                    ClickRow({ editing = c }) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name + if (!c.active) " (${S.inactive})" else "", style = MaterialTheme.typography.titleMedium)
                            Hint(policySummary(c.policy()) + "، " + (funds.firstOrNull { it.id == c.defaultFundId }?.name ?: ""))
                        }
                        if (c.isWater) Badge(S.water)
                    }
                }
            }
        }
    }
    val target = editing ?: if (creating) CategoryEntity(newId(), building.id, "", AllocationPolicy().encode(), funds.firstOrNull { it.kind == FundKind.OPERATING }?.id, sortOrder = categories.size) else null
    if (target != null) {
        var name by rememberSaveable(target.id) { mutableStateOf(target.name) }
        var fund by remember(target.id) { mutableStateOf(funds.firstOrNull { it.id == target.defaultFundId }) }
        var water by remember(target.id) { mutableStateOf(target.isWater) }
        var active by remember(target.id) { mutableStateOf(target.active) }
        val policy = rememberPolicyEditor(target.policy())
        val close = { editing = null; creating = false }
        FormDialog(if (creating) S.newCategory else S.edit, close, {
            vm.run(S.saved, onSuccess = close) {
                vm.repos.expenses.saveCategory(target.copy(name = name, defaultFundId = fund?.id, isWater = water, active = active, defaultPolicyJson = policy.toPolicy()!!.encode()))
            }
        }, canConfirm = name.isNotBlank() && policy.toPolicy() != null) {
            TextInput(S.name, name, { name = it })
            Dropdown(S.fund, funds, fund, { fund = it }, { it.name })
            SwitchRow(S.isWater, water, { water = it })
            SwitchRow(S.active, active, { active = it })
            Hint(S.policyChangeNote)
            PolicyEditor(policy, units, spaces)
        }
    }
}

@Composable
fun ChargeItemsScreen(vm: AppViewModel) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val items by vm.chargeItems.collectAsState()
    val categories by vm.categories.collectAsState()
    val funds by vm.funds.collectAsState()
    val tariffs by vm.tariffs.collectAsState()
    val units by vm.units.collectAsState()
    val spaces by vm.spaces.collectAsState()
    var editing by remember { mutableStateOf<ChargeItemEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    WithFab(S.newChargeItem, { creating = true }) {
        ScreenList {
            if (items.isEmpty()) item { SectionCard { Hint(S.empty) } }
            items(items.size) { i ->
                val c = items[i]
                SectionCard {
                    ClickRow({ editing = c }) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name + if (!c.active) " (${S.inactive})" else "", style = MaterialTheme.typography.titleMedium)
                            Hint("${Fa.chargeMode(c.mode)}، ${if (c.mode != ChargeMode.WATER_TARIFF) money(c.amountMinor) else ""}، ${policySummary(c.policy())}")
                            Hint("${S.effectiveFrom} ${Fmt.date(c.activeFrom, building.calendar)}" + (c.activeTo?.let { " ${S.effectiveTo} ${Fmt.date(it, building.calendar)}" } ?: ""))
                        }
                    }
                }
            }
        }
    }
    val target = editing ?: if (creating) ChargeItemEntity(newId(), building.id, "", categories.firstOrNull()?.id ?: "", funds.firstOrNull()?.id ?: "", ChargeMode.TOTAL, 0,
        AllocationPolicy().encode(), activeFrom = vm.today) else null
    if (target != null) {
        var name by rememberSaveable(target.id) { mutableStateOf(target.name) }
        var cat by remember(target.id) { mutableStateOf(categories.firstOrNull { it.id == target.categoryId }) }
        var fund by remember(target.id) { mutableStateOf(funds.firstOrNull { it.id == target.fundId }) }
        var mode by remember(target.id) { mutableStateOf(target.mode) }
        val amount = rememberMoney(target.amountMinor)
        var tariff by remember(target.id) { mutableStateOf<WaterTariffEntity?>(tariffs.firstOrNull { it.id == target.tariffId }) }
        val from = rememberDate(target.activeFrom, building.calendar)
        val to = rememberDate(target.activeTo, building.calendar)
        var active by remember(target.id) { mutableStateOf(target.active) }
        val policy = rememberPolicyEditor(target.policy().let { if (target.mode == ChargeMode.WATER_TARIFF) it else it })
        val close = { editing = null; creating = false }
        val pol = if (mode == ChargeMode.WATER_TARIFF) policy.toPolicy()?.let { p ->
            if (p.components.firstOrNull()?.basis is Basis.Consumption) p else AllocationPolicy.single(Basis.Consumption(CommonWater.FundBorne), p.components.firstOrNull()?.eligibility ?: com.github.opscalehub.dong.domain.allocation.Eligibility.AllActive)
        } else policy.toPolicy()
        FormDialog(if (creating) S.newChargeItem else S.edit, close, {
            vm.run(S.saved, onSuccess = close) {
                vm.repos.expenses.saveChargeItem(target.copy(name = name, categoryId = cat!!.id, fundId = fund!!.id, mode = mode,
                    amountMinor = if (mode == ChargeMode.WATER_TARIFF) 0 else amount.value!!, policyJson = pol!!.encode(), tariffId = tariff?.id,
                    activeFrom = from.value!!, activeTo = to.value, active = active))
            }
        }, canConfirm = name.isNotBlank() && cat != null && fund != null && from.value != null && pol != null && (mode == ChargeMode.WATER_TARIFF || amount.valid)) {
            TextInput(S.name, name, { name = it })
            Dropdown(S.category, categories, cat, { cat = it }, { it.name })
            Dropdown(S.fund, funds, fund, { fund = it }, { f: FundEntity -> f.name })
            Text(S.chargeMode, style = MaterialTheme.typography.labelMedium)
            SingleChoice(ChargeMode.entries, mode, { mode = it }) { Fa.chargeMode(it) }
            when (mode) {
                ChargeMode.TOTAL -> NumberInput(S.totalAmount, amount)
                ChargeMode.RATE -> NumberInput(S.rateAmount, amount)
                ChargeMode.WATER_TARIFF -> Dropdown(S.tariff, listOf<WaterTariffEntity?>(null) + tariffs, tariff, { tariff = it }, { it?.name ?: S.autoTariff })
            }
            DateInput(S.effectiveFrom, from, vm.today)
            DateInput(S.effectiveTo, to, vm.today, allowEmpty = true)
            SwitchRow(S.active, active, { active = it })
            PolicyEditor(policy, units, spaces, allowFundOnly = false)
        }
    }
}
