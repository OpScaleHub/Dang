package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.opscalehub.dong.data.SampleData
import com.github.opscalehub.dong.data.db.BuildingEntity
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.ClickRow
import com.github.opscalehub.dong.ui.FormDialog
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.NumberInput
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.TextInput
import com.github.opscalehub.dong.ui.rememberCount

@Composable
fun BuildingsScreen(vm: AppViewModel, onOpened: () -> Unit) {
    val buildings by vm.buildings.collectAsState()
    val current by vm.building.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BuildingEntity?>(null) }

    ScreenList {
        item {
            SectionCard(if (buildings.isEmpty()) S.noBuildingTitle else S.buildings) {
                if (buildings.isEmpty()) Text(S.noBuildingBody, style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { creating = true }, Modifier.fillMaxWidth()) { Text(S.newBuilding) }
                    OutlinedButton(onClick = {
                        vm.run(S.sampleCreated, onSuccess = onOpened) { vm.select(SampleData.createBuilding(vm.repos)) }
                    }, Modifier.fillMaxWidth()) { Text(S.createSample) }
                    OutlinedButton(onClick = {
                        vm.run(S.sampleCreated, onSuccess = onOpened) {
                            // One transaction: the building appears only once its demo data is complete.
                            val id = vm.repos.tx {
                                SampleData.createBuilding(vm.repos, "ساختمان نمایشی (ساختگی)").also { SampleData.addDemoData(vm.repos, it) }
                            }
                            vm.select(id)
                        }
                    }, Modifier.fillMaxWidth()) { Text(S.createSampleDemo) }
                    RestoreButton(vm)
                }
            }
        }
        items(buildings.size) { i ->
            val b = buildings[i]
            SectionCard {
                ClickRow({ vm.select(b.id); onOpened() }) {
                    RadioButton(selected = b.id == current?.id, onClick = { vm.select(b.id); onOpened() })
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = MaterialTheme.typography.titleMedium)
                        if (b.address.isNotBlank()) Hint(b.address)
                        Hint("${S.currencyName}: ${b.currencyName}، ${S.roundingIncrement}: ${Fmt.number(b.roundingIncrement)}")
                    }
                    TextButton(onClick = { deleting = b }) { Text(S.delete) }
                }
            }
        }
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        var address by rememberSaveable { mutableStateOf("") }
        var currency by rememberSaveable { mutableStateOf(S.currencySuffix) }
        val rounding = rememberCount(1000)
        FormDialog(S.newBuilding, { creating = false }, {
            vm.run(S.saved, onSuccess = { creating = false; onOpened() }) {
                vm.select(vm.repos.buildings.create(name, address, rounding.value ?: 1, currency.trim().ifBlank { S.currencySuffix }))
            }
        }, canConfirm = name.isNotBlank() && (rounding.value ?: 0) >= 1) {
            TextInput(S.buildingName, name, { name = it }, error = if (name.isBlank()) S.required else null)
            TextInput(S.address, address, { address = it })
            TextInput(S.currencyName, currency, { currency = it })
            NumberInput(S.roundingIncrement, rounding, supporting = S.roundingHint)
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
