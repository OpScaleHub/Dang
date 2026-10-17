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
    val valid: Boolean get() = label.isNotBlank() && Fmt.parseLong(floor) != null && Fmt.parseCubicMeters(area) != null && Fmt.parseLong(occupants)?.let { it >= 0 } == true
    fun toDraft() = BuildingRepository.UnitDraft(label.trim(), Fmt.parseLong(floor)!!.toInt(), Fmt.parseCubicMeters(area)!! / 10, Fmt.parseLong(occupants)!!.toInt())
}

/** Three steps: details → units → opening balances (optional). Creates everything atomically. */
@Composable
fun SetupWizard(vm: AppViewModel, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var toman by rememberSaveable { mutableStateOf(true) }
    val rounding = rememberCount(1000)
    val floors = rememberCount(4)
    val perFloor = rememberCount(2)
    val rows = remember { mutableStateListOf<UnitRow>() }
    // Opening amounts are typed in the display unit chosen in step 1.
    val mf = MoneyFormat(0, if (toman) 10 else 1, if (toman) S.showToman else S.showRial)
    val cash = remember(toman) { NumberFieldState("0", { mf.parse(it) }, grouped = true, unit = mf.unitName) }
    val reserve = remember(toman) { NumberFieldState("0", { mf.parse(it) }, grouped = true, unit = mf.unitName) }
    val unitsValid = rows.isNotEmpty() && rows.all { it.valid } && rows.map { it.label.trim() }.toSet().size == rows.size

    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
        Text("${Fmt.number((step + 1).toLong())} ${S.of} ۳ — " + listOf(S.setupStep1, S.setupStep2, S.setupStep3)[step],
            Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f)) {
            when (step) {
                0 -> ScreenList {
                    item { TextInput(S.buildingName, name, { name = it }, error = if (name.isBlank()) S.required else null) }
                    item { TextInput(S.address, address, { address = it }) }
                    item {
                        Text(S.displayCurrency, style = MaterialTheme.typography.labelLarge)
                        SingleChoice(listOf(true, false), toman, { toman = it }) { if (it) S.showToman else S.showRial }
                    }
                    item { NumberInput(S.roundingIncrement, rounding, supporting = S.roundingHint) }
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
                                    rows += UnitRow(Fmt.digits("$f-$u"), f.toString(), "100", "2")
                                }
                            }) { Text(S.generateUnitsButton) }
                            Hint(S.unitsHint)
                        }
                    }
                    items(rows.size) { i -> UnitRowEditor(rows[i], onChange = { rows[i] = it }, onRemove = { rows.removeAt(i) }) }
                    item { TextButton(onClick = { rows += UnitRow("", "1", "100", "2") }) { Text(S.addRow) } }
                }
                else -> ScreenList {
                    item { Hint(S.openingHint) }
                    item { NumberInput(S.openingCash, cash) }
                    item { NumberInput(S.openingReserve, reserve) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text(S.previousStep) } else if (onCancel != null) TextButton(onClick = onCancel) { Text(S.cancel) }
            Spacer(Modifier.weight(1f))
            when (step) {
                0 -> Button(onClick = { step = 1 }, enabled = name.isNotBlank() && (rounding.value ?: 0) >= 1) { Text(S.next) }
                1 -> Button(onClick = { step = 2 }, enabled = unitsValid) { Text(S.next) }
                else -> Button(onClick = {
                    vm.run(S.saved, onSuccess = onDone) {
                        val id = vm.repos.buildings.setup(name, address, toman, rounding.value ?: 1, rows.map { it.toDraft() }, cash.value ?: 0, reserve.value ?: 0)
                        vm.select(id)
                    }
                }, enabled = cash.valid && reserve.valid) { Text(S.finish) }
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
