package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.BuildingRepository
import com.github.opscalehub.dong.data.SampleData
import com.github.opscalehub.dong.data.db.BuildingEntity
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.i18n.MoneyFormat
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.ClickRow
import com.github.opscalehub.dong.ui.FormDialog
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.NumberFieldState
import com.github.opscalehub.dong.ui.NumberInput
import com.github.opscalehub.dong.ui.PersianDigitsTransformation
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SingleChoice
import com.github.opscalehub.dong.ui.TextInput
import com.github.opscalehub.dong.ui.UnitChip
import com.github.opscalehub.dong.ui.rememberCount

/** Creates the synthetic demo building in one transaction and selects it. */
fun loadDemo(vm: AppViewModel, onDone: () -> Unit = {}) = vm.run(S.sampleCreated, onSuccess = onDone) {
    val id = vm.repos.tx { SampleData.createBuilding(vm.repos, "ساختمان نمایشی (ساختگی)").also { SampleData.addDemoData(vm.repos, it) } }
    vm.select(id)
}

/** First run: short welcome, then the setup wizard, the demo, or restore. */
@Composable
fun WelcomeScreen(vm: AppViewModel) {
    var wizard by rememberSaveable { mutableStateOf(false) }
    if (wizard) {
        SetupWizard(vm, onDone = {}, onCancel = { wizard = false })
        return
    }
    ScreenList {
        item { com.github.opscalehub.dong.ui.UpdateCard(vm.updater) }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(S.appName, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(S.appTagline, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(S.noBuildingBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { wizard = true }, Modifier.fillMaxWidth().height(52.dp)) { Text(S.setupTitle) }
                OutlinedButton(onClick = { loadDemo(vm) }, Modifier.fillMaxWidth()) { Text(S.createSampleDemo) }
                RestoreButton(vm)
            }
        }
    }
}

/** One editable unit row in the wizard; all fields stay text until [toDraft]. */
data class UnitRow(val label: String, val floor: String, val area: String, val occupants: String) {
    /** Area may be left empty (unknown); everything else is required. */
    val valid: Boolean get() = label.isNotBlank() && Fmt.parseLong(floor) != null && (area.isBlank() || Fmt.parseCubicMeters(area) != null) &&
        Fmt.parseLong(occupants)?.let { it >= 0 } == true
    fun toDraft() = BuildingRepository.UnitDraft(label.trim(), Fmt.parseLong(floor)!!.toInt(), (Fmt.parseCubicMeters(area) ?: 0) / 10, Fmt.parseLong(occupants)!!.toInt())
}

/**
 * Four plain steps, one decision each: name → units → how the building charges → cash on hand.
 * Technical settings (rounding, currency) keep their defaults and live in advanced settings.
 * Creates everything atomically.
 */
@Composable
fun SetupWizard(vm: AppViewModel, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    val floors = rememberCount(4)
    val perFloor = rememberCount(2)
    val rows = remember { mutableStateListOf<UnitRow>() }
    var fixed by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var basis by rememberSaveable { mutableStateOf(BuildingRepository.FixedBasis.PER_UNIT) }
    val mf = MoneyFormat(0, 10, S.showToman)
    val rate = remember { NumberFieldState("", { mf.parse(it) }, grouped = true, unit = mf.unitName) }
    val rateM2 = remember { NumberFieldState("", { mf.parse(it) }, grouped = true, unit = mf.unitName) }
    val ratePeople = remember { NumberFieldState("", { mf.parse(it) }, grouped = true, unit = mf.unitName) }
    val rateBase = remember { NumberFieldState("", { mf.parse(it) }, grouped = true, unit = mf.unitName) }
    var differ by rememberSaveable { mutableStateOf(false) }
    val weights = remember { mutableStateListOf<Long>() }
    val cash = remember { NumberFieldState("0", { mf.parse(it) }, grouped = true, unit = mf.unitName) }
    val unitsValid = rows.isNotEmpty() && rows.all { it.valid } && rows.map { it.label.trim() }.toSet().size == rows.size
    val areasKnown = rows.all { (Fmt.parseCubicMeters(it.area) ?: 0) > 0 }
    fun plan(): BuildingRepository.ChargePlan.Fixed = when (basis) {
        BuildingRepository.FixedBasis.PER_UNIT -> BuildingRepository.ChargePlan.Fixed(perUnit = rate.value ?: 0,
            unitWeights = if (differ) rows.indices.map { weights.getOrElse(it) { 1L } } else null)
        BuildingRepository.FixedBasis.PER_PERSON -> BuildingRepository.ChargePlan.Fixed(perPerson = rate.value ?: 0)
        BuildingRepository.FixedBasis.PER_M2 -> BuildingRepository.ChargePlan.Fixed(perM2 = rate.value ?: 0)
        BuildingRepository.FixedBasis.COMBINED -> BuildingRepository.ChargePlan.Fixed(perUnit = rateBase.value ?: 0, perPerson = ratePeople.value ?: 0, perM2 = rateM2.value ?: 0)
    }
    /** What unit [i] would pay each month under the plan (the same rounding-free sum the engine starts from). */
    fun exampleFor(i: Int): Long {
        val p = plan(); val r = rows[i].toDraft()
        return p.perUnit * (p.unitWeights?.getOrNull(i) ?: 1L) + p.perPerson * r.occupants + p.perM2 * r.areaCentiM2 / 100
    }
    val planValid = fixed == false || (fixed == true && plan().let { p ->
        p.perUnit + p.perPerson + p.perM2 > 0 && (p.perM2 == 0L || areasKnown) && (p.unitWeights?.any { it > 0 } ?: true)
    })
    val titles = listOf(S.setupStep1, S.setupStep2, S.setupStepCharge, S.setupStep3)

    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(progress = { (step + 1) / titles.size.toFloat() }, modifier = Modifier.fillMaxWidth())
        Text("${Fmt.number((step + 1).toLong())} ${S.of} ${Fmt.number(titles.size.toLong())} — ${titles[step]}",
            Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f)) {
            when (step) {
                0 -> ScreenList {
                    item { TextInput(S.buildingName, name, { name = it }, supporting = S.buildingNameHint) }
                    item { TextInput(S.addressOptional, address, { address = it }) }
                }
                1 -> ScreenList {
                    item {
                        SectionCard(S.generateUnits) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NumberInput(S.floorsCount, floors, Modifier.weight(1f))
                                NumberInput(S.unitsPerFloor, perFloor, Modifier.weight(1f))
                            }
                            OutlinedButton(onClick = {
                                rows.clear()
                                for (f in 1..(floors.value ?: 0).toInt().coerceIn(0, 60)) for (u in 1..(perFloor.value ?: 0).toInt().coerceIn(0, 20)) {
                                    rows += UnitRow(Fmt.digits("$f-$u"), f.toString(), "", "2")
                                }
                            }) { Text(S.generateUnitsButton) }
                            Hint(S.unitsHint)
                        }
                    }
                    items(rows.size) { i -> UnitRowEditor(rows[i], onChange = { rows[i] = it }, onRemove = { rows.removeAt(i) }) }
                    item { TextButton(onClick = { rows += UnitRow("", "1", "", "2") }) { Text(S.addRow) } }
                }
                2 -> ScreenList {
                    item { Text(S.chargeQuestion, style = MaterialTheme.typography.titleMedium) }
                    item {
                        ChoiceCard(S.chargeFixedTitle, S.chargeFixedBody, fixed == true) { fixed = true }
                    }
                    if (fixed == true) item {
                        SectionCard {
                            Text(S.chargeFixedHow, style = MaterialTheme.typography.labelLarge)
                            SingleChoice(BuildingRepository.FixedBasis.entries, basis, { basis = it }) {
                                when (it) {
                                    BuildingRepository.FixedBasis.PER_UNIT -> S.fixedPerUnit
                                    BuildingRepository.FixedBasis.PER_PERSON -> S.fixedPerPerson
                                    BuildingRepository.FixedBasis.PER_M2 -> S.fixedPerM2
                                    BuildingRepository.FixedBasis.COMBINED -> S.fixedCombined
                                }
                            }
                            if (basis == BuildingRepository.FixedBasis.COMBINED) {
                                Hint(S.combinedHint)
                                NumberInput(S.ratePerM2Short, rateM2)
                                NumberInput(S.ratePerPersonShort, ratePeople)
                                NumberInput(S.ratePerUnitBase, rateBase)
                            } else NumberInput(when (basis) {
                                BuildingRepository.FixedBasis.PER_PERSON -> S.ratePerPerson
                                BuildingRepository.FixedBasis.PER_M2 -> S.ratePerM2
                                else -> S.ratePerUnit
                            }, rate)
                            if (basis == BuildingRepository.FixedBasis.PER_UNIT) {
                                com.github.opscalehub.dong.ui.SwitchRow(S.unitsDiffer, differ, { differ = it }, supporting = S.unitsDifferHint)
                                if (differ) com.github.opscalehub.dong.ui.MultiplierEditor(
                                    rows.map { com.github.opscalehub.dong.ui.MultiplierRow("${S.unit} ${it.label}", Fmt.parseLong(it.floor)?.toInt() ?: 1) },
                                    rows.indices.map { weights.getOrElse(it) { 1L } },
                                ) { w -> weights.clear(); weights.addAll(w) }
                            }
                            val p = plan()
                            if (p.perM2 > 0 && !areasKnown) Hint(S.needAreas, MaterialTheme.colorScheme.error)
                            else if (rows.isNotEmpty() && p.perUnit + p.perPerson + p.perM2 > 0) Text(S.exampleShare.format(rows[0].label, mf.withUnit(exampleFor(0))),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                            Hint(S.chargeFixedNote)
                        }
                    }
                    item {
                        ChoiceCard(S.chargeSplitTitle, S.chargeSplitBody, fixed == false) { fixed = false }
                    }
                    item { Hint(S.chargeChangeLater) }
                }
                else -> ScreenList {
                    item { Hint(S.openingCashHint) }
                    item { NumberInput(S.openingCashSimple, cash) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text(S.previousStep) } else if (onCancel != null) TextButton(onClick = onCancel) { Text(S.cancel) }
            Spacer(Modifier.weight(1f))
            when (step) {
                0 -> Button(onClick = { step = 1 }, enabled = name.isNotBlank()) { Text(S.next) }
                1 -> Button(onClick = { step = 2 }, enabled = unitsValid) { Text(S.next) }
                2 -> Button(onClick = { step = 3 }, enabled = planValid) { Text(S.next) }
                else -> Button(onClick = {
                    vm.run(S.saved, onSuccess = onDone) {
                        val plan = if (fixed == true) plan() else BuildingRepository.ChargePlan.SplitBills
                        val id = vm.repos.buildings.setup(name, address, true, 1000, rows.map { it.toDraft() }, cash.value ?: 0, 0, plan)
                        vm.select(id)
                    }
                }, enabled = cash.valid) { Text(S.finish) }
            }
        }
    }
}

/** A large tappable option: title, one-line explanation, selected state. */
@Composable
private fun ChoiceCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedCard(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UnitRowEditor(r: UnitRow, onChange: (UnitRow) -> Unit, onRemove: () -> Unit) {
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(r.label, { onChange(r.copy(label = it)) }, Modifier.weight(1.1f), label = { Text(S.unit) }, singleLine = true, isError = r.label.isBlank())
        OutlinedTextField(r.floor, { onChange(r.copy(floor = it)) }, Modifier.weight(0.8f), label = { Text(S.floor) }, singleLine = true, keyboardOptions = num, visualTransformation = PersianDigitsTransformation)
        OutlinedTextField(r.area, { onChange(r.copy(area = it)) }, Modifier.weight(1f), label = { Text(S.areaShort) }, singleLine = true, keyboardOptions = num, visualTransformation = PersianDigitsTransformation)
        OutlinedTextField(r.occupants, { onChange(r.copy(occupants = it)) }, Modifier.weight(0.8f), label = { Text(S.people) }, singleLine = true, keyboardOptions = num, visualTransformation = PersianDigitsTransformation)
        TextButton(onClick = onRemove, modifier = Modifier.width(48.dp)) { Text("✕") }
    }
}

@Composable
fun BuildingsScreen(vm: AppViewModel, nav: NavController) {
    val buildings by vm.buildings.collectAsState()
    val current by vm.building.collectAsState()
    var deleting by remember { mutableStateOf<BuildingEntity?>(null) }

    ScreenList {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate(Routes.SETUP) }, Modifier.fillMaxWidth()) { Text(S.setupTitle) }
                OutlinedButton(onClick = { loadDemo(vm) { nav.popBackStack() } }, Modifier.fillMaxWidth()) { Text(S.createSampleDemo) }
            }
        }
        items(buildings.size) { i ->
            val b = buildings[i]
            SectionCard {
                ClickRow({ vm.select(b.id); nav.popBackStack() }) {
                    RadioButton(selected = b.id == current?.id, onClick = { vm.select(b.id); nav.popBackStack() })
                    UnitChip(b.name.take(1))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = MaterialTheme.typography.titleMedium)
                        if (b.address.isNotBlank()) Hint(b.address)
                    }
                    TextButton(onClick = { deleting = b }) { Text(S.delete) }
                }
            }
        }
    }
    deleting?.let { b ->
        var typed by remember { mutableStateOf("") }
        FormDialog(S.deleteBuilding, { deleting = null }, {
            vm.run(S.done, onSuccess = { deleting = null }) { vm.repos.buildings.delete(b.id) }
        }, canConfirm = typed.trim() == b.name, confirmText = S.delete) {
            Text(S.deleteBuildingWarn)
            TextInput(S.buildingName, typed, { typed = it })
        }
    }
}
