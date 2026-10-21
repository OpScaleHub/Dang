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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
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
import com.github.opscalehub.dong.ui.EmptyState
import com.github.opscalehub.dong.ui.SectionTitle
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
import com.github.opscalehub.dong.ui.LocalMoney
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
            if (expenses.isEmpty()) item { EmptyState(S.empty, S.expensesEmptyHint, S.newExpense) { nav.navigate(Routes.expense("new")) } }
            // Grouped by month, newest first, so it is obvious which bills are already settled in a closed month.
            val groups = expenses.groupBy { it.periodId }.entries.sortedByDescending { (pid, l) -> periods.firstOrNull { it.id == pid }?.startDate ?: l.maxOf { it.date } }
            groups.forEach { (pid, list) ->
                val per = periods.firstOrNull { it.id == pid }
                item {
                    val closed = per?.status == PeriodStatus.FINALIZED
                    SectionTitle((per?.name ?: S.noPeriodAssigned) + if (closed) " — ${S.closedMonth}" else "")
                    if (closed) Hint(S.closedMonthExpensesHint)
                }
                items(list.size) { i ->
                    val e = list[i]
                    val p = paid?.get(e.id) ?: 0
                    SectionCard {
                        ClickRow({ nav.navigate(Routes.expense(e.id)) }) {
                            Column(Modifier.weight(1f)) {
                                Text(e.description, style = MaterialTheme.typography.titleMedium)
                                Hint("${Fmt.date(e.date, building.calendar)}، ${categories.firstOrNull { it.id == e.categoryId }?.name ?: ""}")
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(e.amountMinor), style = MaterialTheme.typography.titleSmall)
                                Hint(paymentStatus(e, p), if (e.status == ExpenseStatus.ACTIVE && p < e.amountMinor) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun paymentStatus(e: ExpenseEntity, paid: Long) = when {
    e.status == ExpenseStatus.VOIDED -> S.voided
    paid == 0L -> S.supplierUnpaid
    paid < e.amountMinor -> S.supplierPartly
    else -> S.supplierPaid
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
    var saveAsDefault by rememberSaveable { mutableStateOf(false) }
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
    // The description is optional: a blank one is saved as the expense type's name.
    val valid = date.value != null && category != null && (amount.value ?: 0) != 0L && fund != null && pol != null
    val advancedMode by vm.ui.advanced.collectAsState()
    val meters by vm.meters.collectAsState()
    val chargeItems by vm.chargeItems.collectAsState()
    // A fixed-monthly-charge building pays bills from the fund: start there until a type is picked.
    LaunchedEffect(chargeItems) {
        if (existing == null && category == null && chargeItems.any { it.active }) policy.reset(AllocationPolicy.FUND_ONLY)
    }

    if (locked && existing != null) {
        LockedExpense(vm, existing, period, categories, suppliers, funds, units, paid ?: 0, payments.orEmpty(), onPay = { paying = true })
        PaySupplierDialog(vm, existing, paying, paid ?: 0, cash, building) { paying = false }
        return
    }
    var details by rememberSaveable { mutableStateOf(existing != null && (existing.supplierId != null || existing.documentRef.isNotBlank() || existing.notes.isNotBlank())) }
    ScreenList {
        item {
            SectionCard(if (existing == null) S.newExpense else S.editExpense, subtitle = S.expenseVsPayment) {
                // Big tappable expense types instead of a dropdown (recognition, not recall).
                Text(S.whatExpense, style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.filter { it.active || it.id == existing?.categoryId }.forEach { c ->
                        androidx.compose.material3.FilterChip(selected = category?.id == c.id, onClick = { category = c },
                            label = { Text(c.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp)) })
                    }
                }
                NumberInput(S.amount, amount)
                TextInput(S.descriptionOptional, description, { description = it }, supporting = S.descriptionHint)
                if (existing == null) SwitchRow(S.paidNow, payNow, { payNow = it })
                TextButton(onClick = { details = !details }) { Text(if (details) S.fewerDetails else S.moreDetails) }
                if (details) {
                    DateInput(S.date, date, vm.today)
                    Dropdown(S.period, listOf<PeriodEntity?>(null) + drafts, periodSel, { periodSel = it }, { it?.name ?: S.noPeriodAssigned })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dropdown(S.supplier, listOf<SupplierEntity?>(null) + suppliers, supplier, { supplier = it }, { it?.name ?: S.none }, Modifier.weight(1f))
                        TextButton(onClick = { addingSupplier = true }) { Text(S.add) }
                    }
                    Dropdown(S.fund, funds, fund, { fund = it }, { it.name })
                    TextInput(S.documentRef, docRef, { docRef = it })
                    TextInput(S.notes, notes, { notes = it }, singleLine = false)
                }
            }
        }
        item {
            SectionCard(S.howToSplit) {
                // Simple mode: a few named choices with what each one means. Advanced mode adds the
                // last-used policy and the full formula editor.
                val last = expenses.filter { it.categoryId == category?.id && it.id != existing?.id && it.status == ExpenseStatus.ACTIVE }.maxByOrNull { it.createdAt }
                val options = plainSplits(meters.isNotEmpty() || advancedMode, spaces.isNotEmpty(), chargeItems.any { it.active }) + buildList {
                    if (advancedMode) last?.let { add(Triple(S.presetLast, policySummary(it.policy()), it.policy())) }
                }
                // Multipliers: a weighted split edited as «۱×، ۲×…» per unit (e.g. elevator by floor).
                val active = units.filter { it.active }.sortedBy { it.sortOrder }
                val currentWeights = (pol?.components?.singleOrNull()?.basis as? Basis.Weighted)?.weights
                val weighted = currentWeights != null && pol.components.size == 1
                val custom = pol != null && !weighted && options.none { it.third == pol }
                options.forEach { (label, hint, p) -> SplitOption(label, hint, pol == p) { policy.reset(p); policyTouched = true } }
                SplitOption(S.splitWeighted, S.splitWeightedHint, weighted) {
                    if (!weighted) { policy.reset(AllocationPolicy.single(Basis.Weighted(active.associate { it.id to 1L }))); policyTouched = true }
                }
                if (weighted) {
                    com.github.opscalehub.dong.ui.MultiplierEditor(
                        active.map { com.github.opscalehub.dong.ui.MultiplierRow("${S.unit} ${it.label} (${S.floor} ${Fmt.number(it.floor.toLong())})", it.floor) },
                        active.map { currentWeights!![it.id] ?: 1L },
                    ) { w -> policy.reset(AllocationPolicy.single(Basis.Weighted(active.map { it.id }.zip(w).toMap()))); policyTouched = true }
                }
                if (custom) SplitOption(S.splitCustom, pol?.let { policySummary(it) } ?: "", true) {}
                // Remember this choice for the expense type, so next month needs no decision.
                val cat = category
                if (cat != null && pol != null && pol != cat.policy()) {
                    com.github.opscalehub.dong.ui.SwitchRow(S.saveAsDefault.format(cat.name), saveAsDefault, { saveAsDefault = it })
                }
                if (advancedMode) {
                    TextButton(onClick = { advanced = !advanced }) { Text(S.advanced) }
                    if (advanced) PolicyEditor(policy, units, spaces)
                }
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
                    // The preview runs under an internal source id; drop it so messages don't show «preview».
                    !pv.valid -> pv.issues.filter { it.blocking }.distinctBy { it.copy(sourceId = null) }
                        .forEach { Hint(Fa.issue(it.copy(sourceId = null), vm.names()), MaterialTheme.colorScheme.error) }
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
                        val named = if (e.description.isBlank()) e.copy(description = category!!.name) else e
                        if (saveAsDefault && pol != category!!.policy()) vm.repos.expenses.saveCategory(category!!.copy(defaultPolicyJson = pol!!.encode()))
                        if (existing == null) vm.repos.expenses.create(named, if (payNow) cash.firstOrNull()?.id else null) else vm.repos.expenses.update(named)
                    }
                }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text(S.save) }
                if (!valid) {
                    val missing = listOfNotNull(
                        S.category.takeIf { category == null }, S.amount.takeIf { (amount.value ?: 0) == 0L },
                        S.date.takeIf { date.value == null },
                    )
                    if (missing.isNotEmpty()) Hint(S.toSaveFill + " " + missing.joinToString("، "))
                }
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
    PaySupplierDialog(vm, existing, paying, paid ?: 0, cash, building) { paying = false }

}

/** The named split choices of simple mode, each with what it means (expense form and expense types). */
fun plainSplits(meters: Boolean, parking: Boolean, fixedCharge: Boolean) = buildList {
    add(Triple(S.splitEqual, S.splitEqualHint, AllocationPolicy.single(Basis.Equal)))
    add(Triple(S.splitPeople, S.splitPeopleHint, AllocationPolicy.single(Basis.Occupants)))
    add(Triple(S.splitArea, S.splitAreaHint, AllocationPolicy.single(Basis.Area)))
    if (meters) add(Triple(S.splitWater, S.splitWaterHint, com.github.opscalehub.dong.data.METERED_WATER))
    if (parking) add(Triple(S.splitParking, S.splitParkingHint, AllocationPolicy.single(Basis.Parking())))
    add(Triple(S.splitFund, if (fixedCharge) S.splitFundHintFixed else S.splitFundHint, AllocationPolicy.FUND_ONLY))
}

@Composable
fun CategoriesScreen(vm: AppViewModel) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val categories by vm.categories.collectAsState()
    val funds by vm.funds.collectAsState()
    val units by vm.units.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val meters by vm.meters.collectAsState()
    val chargeItems by vm.chargeItems.collectAsState()
    val advanced by vm.ui.advanced.collectAsState()
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    WithFab(S.newCategory, { creating = true }) {
        ScreenList {
            item { SectionCard { Hint(S.policyChangeNote) } }
            // The hidden type that only carries the monthly charge is noise in simple mode.
            val shown = if (advanced) categories else categories.filter { it.active || it.name != com.github.opscalehub.dong.data.MONTHLY_CHARGE }
            items(shown.size) { i ->
                val c = shown[i]
                SectionCard {
                    ClickRow({ editing = c }) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name + if (!c.active) " (${S.inactive})" else "", style = MaterialTheme.typography.titleMedium)
                            // The fund only matters when it is not the usual one.
                            val fund = funds.firstOrNull { it.id == c.defaultFundId }
                            Hint(splitName(c.policy(), meters.isNotEmpty(), spaces.isNotEmpty()) +
                                if (advanced || fund?.kind != FundKind.OPERATING) "، " + (fund?.name ?: "") else "")
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
            if (advanced) {
                Dropdown(S.fund, funds, fund, { fund = it }, { it.name })
                SwitchRow(S.isWater, water, { water = it })
            }
            SwitchRow(S.active, active, { active = it })
            Hint(S.policyChangeNote)
            if (advanced) PolicyEditor(policy, units, spaces)
            else {
                // Simple mode: the same named choices as the expense form; anything else stays as it is.
                Text(S.howToSplit, style = MaterialTheme.typography.labelLarge)
                val pol = policy.toPolicy()
                val opts = plainSplits(meters.isNotEmpty(), spaces.isNotEmpty(), chargeItems.any { it.active })
                opts.forEach { (label, hint, p) -> SplitOption(label, hint, pol == p) { policy.reset(p) } }
                if (pol != null && opts.none { it.third == pol }) SplitOption(splitName(pol, true, true), "", true) {}
            }
        }
    }
}

/** A policy in the words of the simple split choices, or the technical summary when it is none of them. */
fun splitName(p: AllocationPolicy, meters: Boolean, parking: Boolean): String =
    plainSplits(meters, parking, false).firstOrNull { it.third == p }?.first
        ?: if ((p.components.singleOrNull()?.basis as? Basis.Weighted) != null) S.splitWeighted else policySummary(p)

/** «هر واحد ماهی ۵۰۰٬۰۰۰ تومان»: a monthly charge as the residents would say it. */
@Composable
private fun chargeSummary(c: ChargeItemEntity): String? {
    if (c.mode != ChargeMode.RATE) return null
    val amount = LocalMoney.current.withUnit(c.amountMinor)
    return when (c.policy().components.singleOrNull()?.basis) {
        Basis.Equal -> S.chargePerUnitMonthly.format(amount)
        Basis.Occupants -> S.chargePerPersonMonthly.format(amount)
        Basis.Area -> S.chargePerM2Monthly.format(amount)
        is Basis.Weighted -> S.chargeWeightedMonthly.format(amount)
        else -> null
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

    val advanced by vm.ui.advanced.collectAsState()
    WithFab(if (advanced) S.newChargeItem else null, { creating = true }) {
        ScreenList {
            if (items.isEmpty()) item { SectionCard { Hint(S.empty) } }
            if (!advanced) item { SectionCard { Hint(S.chargeEditHint) } }
            items(items.size) { i ->
                val c = items[i]
                SectionCard {
                    ClickRow({ editing = c }) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name + if (!c.active) " (${S.inactive})" else "", style = MaterialTheme.typography.titleMedium)
                            val plain = chargeSummary(c)
                            if (plain != null && !advanced) Text(plain, style = MaterialTheme.typography.bodyLarge)
                            else Hint("${Fa.chargeMode(c.mode)}، ${if (c.mode != ChargeMode.WATER_TARIFF) money(c.amountMinor) else ""}، ${policySummary(c.policy())}")
                            if (advanced || c.activeTo != null) Hint("${S.effectiveFrom} ${Fmt.date(c.activeFrom, building.calendar)}" + (c.activeTo?.let { " ${S.effectiveTo} ${Fmt.date(it, building.calendar)}" } ?: ""))
                        }
                    }
                }
            }
        }
    }
    val target = editing ?: if (creating) ChargeItemEntity(newId(), building.id, "", categories.firstOrNull()?.id ?: "", funds.firstOrNull()?.id ?: "", ChargeMode.TOTAL, 0,
        AllocationPolicy().encode(), activeFrom = vm.today) else null
    // Simple mode: only the amount changes. Closed months keep what they charged; open ones use the new amount.
    if (target != null && !advanced) {
        val amount = rememberMoney(target.amountMinor)
        val close = { editing = null; creating = false }
        FormDialog(target.name, close, {
            vm.run(S.saved, onSuccess = close) { vm.repos.expenses.saveChargeItem(target.copy(amountMinor = amount.value!!)) }
        }, canConfirm = amount.valid && (amount.value ?: 0) > 0) {
            chargeSummary(target)?.let { Hint(it) }
            NumberInput(S.newMonthlyAmount, amount)
            Hint(S.chargeChangeNote)
        }
        return
    }
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

@Composable
private fun PaySupplierDialog(vm: AppViewModel, existing: ExpenseEntity?, open: Boolean, paid: Long,
                              cash: List<com.github.opscalehub.dong.data.db.CashAccountEntity>,
                              building: com.github.opscalehub.dong.data.db.BuildingEntity, close: () -> Unit) {
    if (!open || existing == null) return
    val amt = rememberMoney(existing.amountMinor - paid)
    var from by remember { mutableStateOf(cash.firstOrNull()) }
    val d = rememberDate(vm.today, building.calendar)
    var ref by rememberSaveable { mutableStateOf("") }
    FormDialog(S.paySupplier, close, {
        vm.run(S.saved, onSuccess = close) { vm.repos.expenses.paySupplier(existing.id, amt.value!!, from!!.id, d.value!!, ref) }
    }, canConfirm = (amt.value ?: 0) > 0 && from != null && d.value != null) {
        NumberInput(S.amount, amt)
        Dropdown(S.payFrom, cash, from, { from = it }, { it.name })
        DateInput(S.date, d, vm.today)
        TextInput(S.reference, ref, { ref = it })
    }
}

/**
 * An expense of a closed month, or a cancelled one: a summary, not a form. Shows what each unit
 * was actually charged in that month and the one thing still possible (paying the supplier),
 * and says how to deal with a mistake instead of letting edits fail on save.
 */
@Composable
private fun LockedExpense(
    vm: AppViewModel, e: ExpenseEntity, period: PeriodEntity?,
    categories: List<com.github.opscalehub.dong.data.db.CategoryEntity>, suppliers: List<SupplierEntity>,
    funds: List<com.github.opscalehub.dong.data.db.FundEntity>, units: List<com.github.opscalehub.dong.data.db.UnitEntity>,
    paid: Long, payments: List<com.github.opscalehub.dong.data.db.JournalEntryEntity>, onPay: () -> Unit,
) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val report by derived(vm, building.id, e.id) { vm.reports.load(building.id) }
    val voided = e.status == ExpenseStatus.VOIDED
    ScreenList {
        item {
            SectionCard(e.description) {
                Text(if (voided) S.voidedExpense else "${S.inMonth} ${period?.name ?: ""} — ${S.closedMonth}",
                    style = MaterialTheme.typography.labelLarge, color = if (voided) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                KeyValue(S.amount, money(e.amountMinor), strong = true)
                KeyValue(S.category, categories.firstOrNull { it.id == e.categoryId }?.name ?: "—")
                KeyValue(S.date, Fmt.date(e.date, building.calendar))
                e.supplierId?.let { id -> KeyValue(S.supplier, suppliers.firstOrNull { it.id == id }?.name ?: "—") }
                KeyValue(S.fund, funds.firstOrNull { it.id == e.fundId }?.name ?: "—")
                if (e.documentRef.isNotBlank()) KeyValue(S.documentRef, e.documentRef)
                if (e.notes.isNotBlank()) Hint(e.notes)
            }
        }
        if (!voided) item {
            SectionCard(S.lockedWhyTitle) {
                Text(S.lockedByFinalized, style = MaterialTheme.typography.bodyMedium)
                Text(S.lockedWhatToDo, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!voided && period != null) item {
            val lines = report?.periodResults?.get(period.id)?.lines.orEmpty().filter { it.sourceId == e.id }
            SectionCard(S.chargedInThatMonth, subtitle = period.name) {
                if (lines.isEmpty()) Hint(S.fundOnly)
                else com.github.opscalehub.dong.ui.charts.HBars(
                    units.filter { u -> lines.any { it.unitId == u.id } }.map { u ->
                        val v = lines.filter { it.unitId == u.id }.sumOf { it.amount }
                        com.github.opscalehub.dong.ui.charts.HBar("${S.unit} ${u.label}", v, money(v))
                    },
                )
                val fromFund = e.amountMinor - lines.sumOf { it.amount }
                if (fromFund > 0) Hint("${S.fundBorne}: ${money(fromFund)}")
            }
        }
        if (!voided) item {
            SectionCard(S.paySupplier, subtitle = S.paySupplierHint) {
                KeyValue(S.paid, money(paid))
                KeyValue(S.outstanding, money(e.amountMinor - paid), strong = true)
                payments.filter { it.type == EntryType.SUPPLIER_PAYMENT }.forEach { Hint("${Fmt.date(it.entryDate, building.calendar)}، ${it.reference}") }
                if (paid < e.amountMinor) Button(onClick = onPay, Modifier.fillMaxWidth()) { Text(S.paySupplier) }
            }
        }
    }
}

/** One split choice: a radio row with a plain explanation underneath. */
@Composable
private fun SplitOption(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
