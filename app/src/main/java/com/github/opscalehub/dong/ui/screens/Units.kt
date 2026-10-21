package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.github.opscalehub.dong.data.Insights
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.share.BillShare
import com.github.opscalehub.dong.ui.EmptyState
import com.github.opscalehub.dong.ui.LocalMoney
import com.github.opscalehub.dong.ui.SectionTitle
import com.github.opscalehub.dong.ui.UnitChip
import com.github.opscalehub.dong.ui.charts.AgingBar
import com.github.opscalehub.dong.ui.charts.Segment
import com.github.opscalehub.dong.ui.charts.Sparkline
import com.github.opscalehub.dong.ui.charts.StackedBar
import com.github.opscalehub.dong.ui.theme.LocalViz
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.newId
import com.github.opscalehub.dong.data.db.OccupancyEntity
import com.github.opscalehub.dong.data.db.ParkingAssignmentEntity
import com.github.opscalehub.dong.data.db.ParkingSpaceEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.domain.allocation.QuantityKind
import com.github.opscalehub.dong.domain.model.ParkingRelation
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.BalanceText
import com.github.opscalehub.dong.ui.ClickRow
import com.github.opscalehub.dong.ui.DateInput
import com.github.opscalehub.dong.ui.Divider
import com.github.opscalehub.dong.ui.Dropdown
import com.github.opscalehub.dong.ui.FormDialog
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.KeyValue
import com.github.opscalehub.dong.ui.NumberInput
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.money
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SingleChoice
import com.github.opscalehub.dong.ui.SwitchRow
import com.github.opscalehub.dong.ui.TextInput
import com.github.opscalehub.dong.ui.rememberCount
import com.github.opscalehub.dong.ui.rememberCubic
import com.github.opscalehub.dong.ui.rememberDate

/** Current occupant count per unit (latest record effective today). */
fun currentOccupants(records: List<OccupancyEntity>, today: Long): Map<String, Int> =
    records.groupBy { it.unitId }.mapValues { (_, l) -> l.filter { it.effectiveFrom <= today }.maxByOrNull { it.effectiveFrom }?.occupantCount ?: 0 }

/** «، ۲ پارکینگ» for the unit's summary line; nothing when it has none. */
private fun parkingText(n: Int) = if (n > 0) "، ${Fmt.number(n.toLong())} ${S.parkingLabel}" else ""

private enum class UnitFilter { ALL, OWES, SETTLED }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitsScreen(vm: AppViewModel, nav: NavController, collect: Boolean = false) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val units by vm.units.collectAsState()
    val occ by vm.occupancy.collectAsState()
    val assignments by vm.assignments.collectAsState()
    val report by derived(vm, building.id) { vm.reports.load(building.id) }
    var adding by remember { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(if (collect) UnitFilter.OWES else UnitFilter.ALL) }
    var paying by remember { mutableStateOf<UnitEntity?>(null) }
    var payingAny by remember { mutableStateOf(false) }
    val current = currentOccupants(occ, vm.today)
    val balances = report?.balances?.units.orEmpty()
    val viz = LocalViz.current

    WithFab(if (collect) S.payNow else S.newUnit, { if (collect) payingAny = true else adding = true }) {
        ScreenList {
            if (collect) item {
                val d = report ?: return@item
                val c = d.periods.filter { it.status == PeriodStatus.FINALIZED }.maxByOrNull { it.endDate }?.let { Insights.collection(d, it.id) }
                SectionCard(S.collectTitle, subtitle = S.collectHint) {
                    if (c != null && c.billed > 0) Text("${S.collected}: ${money(c.collected)} ${S.of} ${money(c.billed)}", style = MaterialTheme.typography.titleMedium)
                    if (balances.values.none { it > 0 }) Hint(S.allPaid, MaterialTheme.colorScheme.primary)
                }
            }
            if (!collect) item {
                val d = report ?: return@item
                val aging = Insights.aging(d, vm.today)
                SectionCard(S.debtAging, subtitle = S.signConvention) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(money(d.balances.outstandingDebts), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = if (d.balances.outstandingDebts > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Hint("${LocalMoney.current.unitName}، ${Fmt.number(balances.values.count { it > 0 }.toLong())} ${S.debtorUnits}")
                    }
                    val top = aging.filter { it.total > 0 }.take(5)
                    val max = top.maxOfOrNull { it.total } ?: 1
                    top.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(units.firstOrNull { it.id == a.unitId }?.label ?: "", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
                            AgingBar(Insights.AgeBucket.entries.map { a.buckets.getValue(it) }, max, Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(money(a.total), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (top.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(S.ageCurrent, S.age2, S.age3, S.age4).forEachIndexed { i, l ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(viz.aging[i]))
                                    Spacer(Modifier.width(4.dp))
                                    Text(l, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (!collect) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (!reordering) SingleChoice(UnitFilter.entries, filter, { filter = it }) {
                            when (it) { UnitFilter.ALL -> S.allUnits; UnitFilter.OWES -> S.owes; UnitFilter.SETTLED -> S.settledOrCredit }
                        }
                    }
                    if (units.size > 1) TextButton(onClick = { reordering = !reordering; filter = UnitFilter.ALL }) {
                        Text(if (reordering) S.reorderDone else S.reorderUnits)
                    }
                }
                if (reordering) Hint(S.moveHint)
            }
            val shown = units.filter { u ->
                val bal = balances[u.id] ?: 0
                when (filter) { UnitFilter.ALL -> true; UnitFilter.OWES -> bal > 0; UnitFilter.SETTLED -> bal <= 0 }
            }.let { l -> if (collect) l.sortedByDescending { balances[it.id] ?: 0 } else l }
            if (units.isEmpty()) item { EmptyState(S.empty, S.unitsHint, S.newUnit) { adding = true } }
            // Reordering: ▲▼ on each unit, saved at once (the whole list, so the order is always complete).
            if (reordering) items(units.size) { i ->
                val u = units[i]
                fun move(to: Int) = vm.run {
                    vm.repos.buildings.reorderUnits(building.id, units.map { it.id }.toMutableList().apply { add(to, removeAt(i)) })
                }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UnitChip(u.label)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${S.unit} ${u.label}", style = MaterialTheme.typography.titleMedium)
                            Hint("${S.floor} ${Fmt.number(u.floor.toLong())}")
                        }
                        MoveButtons(if (i > 0) ({ move(i - 1) }) else null, if (i < units.size - 1) ({ move(i + 1) }) else null)
                    }
                }
            }
            if (!reordering) items(shown.size) { i ->
                val u = shown[i]
                SectionCard {
                    ClickRow({ nav.navigate(Routes.unit(u.id)) }) {
                        UnitChip(u.label, if (u.active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${S.unit} ${u.label}" + if (!u.active) " (${S.inactive})" else "", style = MaterialTheme.typography.titleMedium)
                            Hint("${S.floor} ${Fmt.number(u.floor.toLong())}، ${Fa.quantity(QuantityKind.AREA_CENTI_M2, u.areaCentiM2)}، ${Fmt.number((current[u.id] ?: 0).toLong())} ${S.people}" +
                                parkingText(assignments.count { it.unitId == u.id && it.effectiveTo == null }))
                        }
                        BalanceText(balances[u.id] ?: 0)
                    }
                    if (collect) Button(onClick = { paying = u }, Modifier.fillMaxWidth()) { Text(S.payNow) }
                }
            }
        }
    }
    if (adding) UnitDialog(vm, null, building.id) { adding = false }
    paying?.let { u -> PaymentDialog(vm, building.id, units, building.calendar, 0, preset = u, close = { paying = null }) }
    if (payingAny) PaymentDialog(vm, building.id, units, building.calendar, 0, close = { payingAny = false })
}

/**
 * Add or edit a unit. Residents and parking are edited here too, as plain numbers: a change is
 * recorded from today (history is kept, closed months are unaffected), so a sale of a parking
 * space or a new resident needs no other screen.
 */
@Composable
private fun UnitDialog(vm: AppViewModel, unit: UnitEntity?, buildingId: String, close: () -> Unit) {
    val occ by vm.occupancy.collectAsState()
    val assignments by vm.assignments.collectAsState()
    val nowPeople = unit?.let { currentOccupants(occ, vm.today)[it.id] } ?: 1
    val nowParking = unit?.let { u -> assignments.count { it.unitId == u.id && it.effectiveTo == null } } ?: 0
    var label by rememberSaveable { mutableStateOf(unit?.label ?: "") }
    val floor = rememberCount(unit?.floor?.toLong() ?: 1)
    val area = rememberCubic(unit?.areaCentiM2?.times(10))
    var active by rememberSaveable { mutableStateOf(unit?.active ?: true) }
    var notes by rememberSaveable { mutableStateOf(unit?.notes ?: "") }
    val people = rememberCount(nowPeople.toLong())
    var parking by rememberSaveable { mutableStateOf(nowParking) }
    val areaCenti = area.value?.let { it / 10 }
    FormDialog(if (unit == null) S.newUnit else S.editUnit, close, {
        vm.run(S.saved, onSuccess = close) {
            val e = unit?.copy(label = label, floor = floor.value!!.toInt(), areaCentiM2 = areaCenti!!, active = active, notes = notes)
                ?: UnitEntity(newId(), buildingId, floor.value!!.toInt(), label, areaCenti!!, active, notes, vm.repos.buildings.nextUnitSortOrder(buildingId))
            vm.repos.tx {
                vm.repos.buildings.saveUnit(e)
                val n = people.value!!.toInt()
                if (unit == null) vm.repos.buildings.setOccupancy(e.id, vm.today - 3650, n)
                else if (n != nowPeople) vm.repos.buildings.setOccupancy(e.id, vm.today, n)
                if (parking != nowParking) vm.repos.buildings.setParkingCount(e.id, parking, vm.today)
            }
        }
    }, canConfirm = label.isNotBlank() && floor.valid && areaCenti != null && (people.value ?: -1) >= 0) {
        TextInput(S.unitLabel, label, { label = it }, error = if (label.isBlank()) S.required else null)
        NumberInput(S.floor, floor)
        NumberInput(S.area, area)
        NumberInput(S.occupants, people, supporting = if (unit == null) null else S.occupancyFromToday)
        Text(S.parkingLabel, style = MaterialTheme.typography.labelLarge)
        SingleChoice((0..maxOf(3, nowParking)).toList(), parking, { parking = it }) { if (it == 0) S.noParking else Fmt.number(it.toLong()) }
        if (unit != null && parking != nowParking) Hint(S.parkingFromToday)
        SwitchRow(S.active, active, { active = it }, S.unitActiveHint)
        TextInput(S.residentNotesHint, notes, { notes = it }, singleLine = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnitScreen(vm: AppViewModel, nav: NavController, unitId: String) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val units by vm.units.collectAsState()
    val occ by vm.occupancy.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val assignments by vm.assignments.collectAsState()
    val periods by vm.periods.collectAsState()
    val unit = units.firstOrNull { it.id == unitId } ?: return
    val report by derived(vm, unitId) { vm.reports.load(building.id) }
    val statement by derived(vm, unitId) { vm.repos.finance.unitStatement(building.id, unitId) }
    val lastClosed = periods.filter { it.status == PeriodStatus.FINALIZED }.maxByOrNull { it.endDate }
    var editing by remember { mutableStateOf(false) }
    var addingOcc by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mf = LocalMoney.current
    val viz = LocalViz.current
    val balance = statement?.closing ?: 0

    fun reminder() = S.reminderText.format(unit.label, building.name, mf.withUnit(balance.coerceAtLeast(0)))

    ScreenList {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UnitChip(unit.label)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${S.unit} ${unit.label}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Hint("${S.floor} ${Fmt.number(unit.floor.toLong())}، ${Fa.quantity(QuantityKind.AREA_CENTI_M2, unit.areaCentiM2)}، ${Fmt.number((currentOccupants(occ, vm.today)[unit.id] ?: 0).toLong())} ${S.people}" +
                            parkingText(assignments.count { it.unitId == unit.id && it.effectiveTo == null }))
                    }
                }
                Text(
                    when { balance > 0 -> S.owes; balance < 0 -> S.credit; else -> S.settled },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(if (balance == 0L) "—" else mf.withUnit(kotlin.math.abs(balance)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = if (balance > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { paying = true }) { Text(S.payNow) }
                    OutlinedButton(onClick = {
                        vm.run {
                            val bill = vm.repos.bills.bill(building.id, unitId, lastClosed?.id)
                            BillShare.shareBill(context, bill, mf, reminder(), lastClosed?.id, unitId)
                        }
                    }) { Text(S.shareBill) }
                    if (balance > 0) OutlinedButton(onClick = { BillShare.shareText(context, reminder()) }) { Text(S.shareReminder) }
                    TextButton(onClick = { editing = true }) { Text(S.edit) }
                }
            }
        }
        item {
            val d = report ?: return@item
            val p = lastClosed ?: return@item
            val comp = Insights.unitComposition(d, unitId, p.id)
            if (comp.isEmpty()) return@item
            // Colour follows the category, never its rank: the building's top five categories (by
            // all charges) own fixed slots in every unit's chart; everything else folds into «سایر».
            val slots = d.lines.filter { it.account == com.github.opscalehub.dong.domain.ledger.AccountKind.INCOME && it.categoryId != null }
                .groupBy { it.categoryId!! }.mapValues { (_, l) -> -l.sumOf { x -> x.amountMinor } }
                .entries.sortedByDescending { it.value }.take(5).map { it.key }
                .sortedBy { c -> d.categories.indexOfFirst { it.id == c } }
            val known = comp.filter { it.first in slots }.sortedBy { slots.indexOf(it.first) }
            val rest = comp.filter { it.first !in slots }.sumOf { it.second }
            val segs = known.map { (c, v) -> Segment(d.categoryName(c), v, viz.categorical[slots.indexOf(c)], mf.format(v)) } +
                if (rest > 0) listOf(Segment(S.flowOther, rest, viz.categorical[5], mf.format(rest))) else emptyList()
            SectionCard("${S.whereMoneyWent} — ${p.name}") {
                Text(mf.withUnit(comp.sumOf { it.second }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                StackedBar(segs)
            }
        }
        item {
            val d = report ?: return@item
            val hist = Insights.unitBalanceHistory(d, unitId)
            if (hist.size < 2) return@item
            SectionCard(S.balanceTrend, subtitle = hist.joinToString("  ") { "${it.first}: ${mf.format(it.second)}" }.take(160)) {
                Sparkline(hist.map { it.second }, description = S.balanceTrend)
            }
        }
        item { SectionTitle(S.history) }
        val rows = statement?.rows.orEmpty().asReversed().take(30)
        if (rows.isEmpty()) item { Hint(S.empty) }
        items(rows.size) { i ->
            val r = rows[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Fa.entryType(r.type) + (r.categoryId?.let { id -> report?.categoryName(id) }?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    Hint(Fmt.date(r.date, building.calendar) + if (r.memo.isNotBlank()) "، ${r.memo}" else "")
                }
                Text(Fmt.signed(r.amount > 0, mf.format(kotlin.math.abs(r.amount))),
                    color = if (r.amount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            TextButton(onClick = { showDetails = !showDetails }) { Text(S.details) }
            if (showDetails) {
                SectionCard(S.occupancyHistory, subtitle = S.occupancyHint) {
                    occ.filter { it.unitId == unitId }.sortedByDescending { it.effectiveFrom }.forEach { o ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${S.effectiveFrom} ${Fmt.date(o.effectiveFrom, building.calendar)}: ${Fmt.number(o.occupantCount.toLong())} ${S.people}", Modifier.weight(1f))
                            TextButton(onClick = { vm.run(S.done) { vm.repos.buildings.deleteOccupancy(o.id) } }) { Text(S.delete) }
                        }
                    }
                    OutlinedButton(onClick = { addingOcc = true }) { Text(S.addOccupancy) }
                }
                Spacer(Modifier.height(8.dp))
                SectionCard(S.parking) {
                    val mine = assignments.filter { it.unitId == unitId && it.effectiveTo == null }
                    if (mine.isEmpty()) Hint(S.empty)
                    mine.forEach { a -> Text("${spaces.firstOrNull { it.id == a.spaceId }?.label ?: ""}، ${Fa.relation(a.relation)}، ${if (a.inUse) S.inUse else S.notInUse}") }
                    if (unit.notes.isNotBlank()) Hint(unit.notes)
                }
            }
        }
    }
    if (editing) UnitDialog(vm, unit, building.id) { editing = false }
    if (paying) PaymentDialog(vm, building.id, units, building.calendar, 0, preset = unit, close = { paying = false })
    if (addingOcc) {
        val date = rememberDate(vm.today, building.calendar)
        val count = rememberCount()
        var note by rememberSaveable { mutableStateOf("") }
        FormDialog(S.addOccupancy, { addingOcc = false }, {
            vm.run(S.saved, onSuccess = { addingOcc = false }) { vm.repos.buildings.setOccupancy(unitId, date.value!!, count.value!!.toInt(), note) }
        }, canConfirm = date.value != null && (count.value ?: -1) >= 0) {
            DateInput(S.effectiveFrom, date, vm.today)
            NumberInput(S.occupants, count)
            TextInput(S.notes, note, { note = it })
        }
    }
}

@Composable
fun ParkingScreen(vm: AppViewModel) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val spaces by vm.spaces.collectAsState()
    val assignments by vm.assignments.collectAsState()
    val units by vm.units.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<ParkingSpaceEntity?>(null) }
    var ending by remember { mutableStateOf<ParkingAssignmentEntity?>(null) }

    WithFab(S.newSpace, { adding = true }) {
        ScreenList {
            item { SectionCard { Hint(S.parkingHint) } }
            items(spaces.size) { i ->
                val s = spaces[i]
                val current = assignments.filter { it.spaceId == s.id && (it.effectiveTo == null || it.effectiveTo >= vm.today) }.maxByOrNull { it.effectiveFrom }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${S.space} ${s.label}", style = MaterialTheme.typography.titleMedium)
                            if (current == null) Hint(S.unassigned) else {
                                Hint("${S.unit} ${units.firstOrNull { it.id == current.unitId }?.label ?: ""}، ${Fa.relation(current.relation)}، ${if (current.inUse) S.inUse else S.notInUse}")
                                Hint("${S.effectiveFrom} ${Fmt.date(current.effectiveFrom, building.calendar)}" + (current.vehicleNote.takeIf { it.isNotBlank() }?.let { "، $it" } ?: ""))
                            }
                        }
                        Column {
                            TextButton(onClick = { assigning = s }) { Text(S.assign) }
                            if (current != null) TextButton(onClick = { ending = current }) { Text(S.endAssignment) }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        var label by rememberSaveable { mutableStateOf("") }
        FormDialog(S.newSpace, { adding = false }, {
            vm.run(S.saved, onSuccess = { adding = false }) { vm.repos.buildings.saveSpace(ParkingSpaceEntity(newId(), building.id, label)) }
        }, canConfirm = label.isNotBlank()) { TextInput(S.spaceLabel, label, { label = it }) }
    }
    assigning?.let { s ->
        var unit by remember { mutableStateOf<UnitEntity?>(null) }
        var relation by remember { mutableStateOf(ParkingRelation.OWNED) }
        var inUse by remember { mutableStateOf(true) }
        var vehicle by rememberSaveable { mutableStateOf("") }
        val from = rememberDate(vm.today, building.calendar)
        FormDialog("${S.assign}: ${s.label}", { assigning = null }, {
            vm.run(S.saved, onSuccess = { assigning = null }) { vm.repos.buildings.assignSpace(s.id, unit!!.id, relation, inUse, vehicle, from.value!!) }
        }, canConfirm = unit != null && from.value != null) {
            Dropdown(S.unit, units.filter { it.active }, unit, { unit = it }, { it.label })
            Text(S.relation, style = MaterialTheme.typography.labelMedium)
            SingleChoice(ParkingRelation.entries, relation, { relation = it }) { Fa.relation(it) }
            SwitchRow(S.inUse, inUse, { inUse = it })
            TextInput(S.vehicle, vehicle, { vehicle = it })
            DateInput(S.effectiveFrom, from, vm.today)
        }
    }
    ending?.let { a ->
        val to = rememberDate(vm.today, building.calendar)
        FormDialog(S.endAssignment, { ending = null }, {
            vm.run(S.saved, onSuccess = { ending = null }) { vm.repos.buildings.endAssignment(a.id, to.value!!) }
        }, canConfirm = to.value != null) { DateInput(S.effectiveTo, to, vm.today) }
    }
}

