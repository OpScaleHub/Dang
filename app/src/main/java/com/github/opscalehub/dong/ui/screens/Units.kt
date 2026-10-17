package com.github.opscalehub.dong.ui.screens

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

@Composable
fun UnitsScreen(vm: AppViewModel, nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val units by vm.units.collectAsState()
    val occ by vm.occupancy.collectAsState()
    val balances by derived(vm, building.id) { vm.repos.finance.balances(building.id) }
    var adding by remember { mutableStateOf(false) }
    val current = currentOccupants(occ, vm.today)

    WithFab(S.newUnit, { adding = true }) {
        ScreenList {
            item {
                SectionCard {
                    KeyValue(S.total, "${Fmt.number(units.size.toLong())} ${S.unit}، ${Fa.quantity(QuantityKind.AREA_CENTI_M2, units.filter { it.active }.sumOf { it.areaCentiM2 })}، ${Fmt.number(current.filterKeys { k -> units.any { it.id == k && it.active } }.values.sum().toLong())} نفر")
                    Hint(S.signConvention)
                }
            }
            items(units.size) { i ->
                val u = units[i]
                SectionCard {
                    ClickRow({ nav.navigate(Routes.unit(u.id)) }) {
                        Column(Modifier.weight(1f)) {
                            Text("${S.unit} ${u.label}" + if (!u.active) " (${S.inactive})" else "", style = MaterialTheme.typography.titleMedium)
                            Hint("${S.floor} ${Fmt.number(u.floor.toLong())}، ${Fa.quantity(QuantityKind.AREA_CENTI_M2, u.areaCentiM2)}، ${Fmt.number((current[u.id] ?: 0).toLong())} نفر")
                        }
                        balances?.units?.get(u.id)?.let { BalanceText(it, building.minorDigits) }
                    }
                }
            }
        }
    }
    if (adding) UnitDialog(vm, null, building.id) { adding = false }
}

@Composable
private fun UnitDialog(vm: AppViewModel, unit: UnitEntity?, buildingId: String, close: () -> Unit) {
    var label by rememberSaveable { mutableStateOf(unit?.label ?: "") }
    val floor = rememberCount(unit?.floor?.toLong() ?: 1)
    val area = rememberCubic(unit?.areaCentiM2?.times(10))
    var active by rememberSaveable { mutableStateOf(unit?.active ?: true) }
    var notes by rememberSaveable { mutableStateOf(unit?.notes ?: "") }
    val initialOcc = rememberCount(if (unit == null) 1 else null)
    val areaCenti = area.value?.let { it / 10 }
    FormDialog(if (unit == null) S.newUnit else S.editUnit, close, {
        vm.run(S.saved, onSuccess = close) {
            val e = unit?.copy(label = label, floor = floor.value!!.toInt(), areaCentiM2 = areaCenti!!, active = active, notes = notes)
                ?: UnitEntity(newId(), buildingId, floor.value!!.toInt(), label, areaCenti!!, active, notes, vm.repos.buildings.nextUnitSortOrder(buildingId))
            vm.repos.buildings.saveUnit(e)
            if (unit == null) initialOcc.value?.let { vm.repos.buildings.setOccupancy(e.id, vm.today - 3650, it.toInt()) }
        }
    }, canConfirm = label.isNotBlank() && floor.valid && areaCenti != null && (unit != null || initialOcc.valid)) {
        TextInput(S.unitLabel, label, { label = it }, error = if (label.isBlank()) S.required else null)
        NumberInput(S.floor, floor)
        NumberInput(S.area, area)
        if (unit == null) NumberInput(S.occupants, initialOcc, supporting = S.occupancyHint)
        SwitchRow(S.active, active, { active = it }, S.unitActiveHint)
        TextInput(S.residentNotesHint, notes, { notes = it }, singleLine = false)
    }
}

@Composable
fun UnitScreen(vm: AppViewModel, nav: NavController, unitId: String) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val units by vm.units.collectAsState()
    val occ by vm.occupancy.collectAsState()
    val spaces by vm.spaces.collectAsState()
    val assignments by vm.assignments.collectAsState()
    val unit = units.firstOrNull { it.id == unitId } ?: return
    val statement by derived(vm, unitId) { vm.repos.finance.unitStatement(building.id, unitId) }
    var editing by remember { mutableStateOf(false) }
    var addingOcc by remember { mutableStateOf(false) }

    ScreenList {
        item {
            SectionCard("${S.unit} ${unit.label}") {
                KeyValue(S.floor, Fmt.number(unit.floor.toLong()))
                KeyValue(S.area, Fa.quantity(QuantityKind.AREA_CENTI_M2, unit.areaCentiM2))
                KeyValue(S.status, if (unit.active) S.active else S.inactive)
                if (unit.notes.isNotBlank()) Hint(unit.notes)
                Row {
                    TextButton(onClick = { editing = true }) { Text(S.edit) }
                    TextButton(onClick = { nav.navigate(Routes.statement(unitId)) }) { Text(S.statement) }
                }
            }
        }
        item {
            SectionCard(S.balance) {
                statement?.let { st ->
                    KeyValue(S.opening, Fmt.money(st.opening, building.minorDigits))
                    KeyValue("شارژ آب", Fmt.money(st.waterCharges, building.minorDigits))
                    KeyValue("سایر شارژها", Fmt.money(st.otherCharges, building.minorDigits))
                    KeyValue(S.paid, Fmt.money(st.payments, building.minorDigits))
                    KeyValue("بستانکاری و تعدیل", Fmt.money(st.creditsAndAdjustments, building.minorDigits))
                    KeyValue(S.refund, Fmt.money(st.refunds, building.minorDigits))
                    Divider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(S.balance, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        BalanceText(st.closing, building.minorDigits)
                    }
                }
            }
        }
        item {
            SectionCard(S.occupancyHistory, subtitle = S.occupancyHint) {
                occ.filter { it.unitId == unitId }.sortedByDescending { it.effectiveFrom }.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${S.effectiveFrom} ${Fmt.date(o.effectiveFrom, building.calendar)}: ${Fmt.number(o.occupantCount.toLong())} نفر", Modifier.weight(1f))
                        TextButton(onClick = { vm.run(S.done) { vm.repos.buildings.deleteOccupancy(o.id) } }) { Text(S.delete) }
                    }
                    if (o.note.isNotBlank()) Hint(o.note)
                }
                OutlinedButton(onClick = { addingOcc = true }) { Text(S.addOccupancy) }
            }
        }
        item {
            SectionCard(S.parking) {
                val mine = assignments.filter { it.unitId == unitId && it.effectiveTo == null }
                if (mine.isEmpty()) Hint(S.empty)
                mine.forEach { a ->
                    Text("${spaces.firstOrNull { it.id == a.spaceId }?.label ?: ""}، ${Fa.relation(a.relation)}، ${if (a.inUse) S.inUse else S.notInUse}")
                }
            }
        }
    }
    if (editing) UnitDialog(vm, unit, building.id) { editing = false }
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

@Composable
fun StatementScreen(vm: AppViewModel, unitId: String) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val units by vm.units.collectAsState()
    val categories by vm.categories.collectAsState()
    val st by derived(vm, unitId) { vm.repos.finance.unitStatement(building.id, unitId) }
    val statement = st ?: return
    ScreenList {
        item {
            SectionCard("${S.statement} ${units.firstOrNull { it.id == unitId }?.label ?: ""}", subtitle = S.signConvention) {
                KeyValue(S.opening, Fmt.money(statement.opening, building.minorDigits))
                KeyValue(S.chargesTotal, Fmt.money(statement.charges, building.minorDigits))
                KeyValue(S.paid, Fmt.money(statement.payments, building.minorDigits))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S.balance, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    BalanceText(statement.closing, building.minorDigits)
                }
            }
        }
        items(statement.rows.size) { i ->
            val r = statement.rows[statement.rows.size - 1 - i]
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Fa.entryType(r.type) + (r.categoryId?.let { id -> categories.firstOrNull { it.id == id }?.name }?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                        Hint("${Fmt.date(r.date, building.calendar)}، ${r.memo}")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text((if (r.amount > 0) "+" else "−") + Fmt.money(kotlin.math.abs(r.amount), building.minorDigits),
                            color = if (r.amount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Hint("${S.balance}: ${Fmt.money(r.balance, building.minorDigits)}")
                    }
                }
            }
        }
    }
}
