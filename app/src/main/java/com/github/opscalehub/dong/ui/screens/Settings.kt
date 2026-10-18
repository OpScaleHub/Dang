package com.github.opscalehub.dong.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.github.opscalehub.dong.BuildConfig
import com.github.opscalehub.dong.data.BuildingRestoreStatus
import com.github.opscalehub.dong.data.RestoreMode
import com.github.opscalehub.dong.data.RestorePreview
import com.github.opscalehub.dong.data.db.CashAccountEntity
import com.github.opscalehub.dong.data.db.CashKind
import com.github.opscalehub.dong.data.db.FundEntity
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.newId
import com.github.opscalehub.dong.domain.model.CalendarSystem
import com.github.opscalehub.dong.domain.model.OccupancyBasis
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.io.BackupCodec
import com.github.opscalehub.dong.io.ProblemCode
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.Divider
import com.github.opscalehub.dong.ui.FormDialog
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.KeyValue
import com.github.opscalehub.dong.ui.NumberInput
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SingleChoice
import com.github.opscalehub.dong.ui.TextInput
import com.github.opscalehub.dong.ui.rememberCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun problemText(c: ProblemCode) = when (c) {
    ProblemCode.NOT_A_BACKUP -> "این فایل، پشتیبان «دنگ» نیست."
    ProblemCode.UNSUPPORTED_VERSION -> "این پشتیبان با نسخه جدیدتری از برنامه ساخته شده است."
    ProblemCode.CHECKSUM_MISMATCH -> "فایل آسیب دیده یا دستکاری شده است (کد کنترل مطابقت ندارد)."
    ProblemCode.MALFORMED -> "ساختار فایل نامعتبر است."
    ProblemCode.TOO_LARGE -> "حجم فایل بیش از حد مجاز است."
    ProblemCode.DUPLICATE_ID -> "شناسه تکراری در فایل وجود دارد."
    ProblemCode.BROKEN_REFERENCE -> "ارجاع نامعتبر بین داده‌های فایل وجود دارد."
    ProblemCode.UNBALANCED_ENTRY -> "سند مالی تراز نشده در فایل وجود دارد."
    ProblemCode.TRIAL_BALANCE -> "تراز آزمایشی فایل صفر نیست."
    ProblemCode.INVALID_VALUE -> "مقدار نامعتبر در فایل وجود دارد."
    ProblemCode.BAD_SNAPSHOT -> "نسخه ثابت یک دوره قطعی قابل خواندن نیست."
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val funds by vm.funds.collectAsState()
    val cash by vm.cashAccounts.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addingFund by remember { mutableStateOf(false) }
    var addingCash by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = vm.backup.export()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no stream") }
                vm.message(S.backupDone)
            } catch (e: Exception) {
                vm.message(S.unexpectedError)
            }
        }
    }
    ScreenList {
        item { BuildingSettingsCard(vm) }
        item {
            SectionCard(S.fundsAndCash) {
                funds.forEach { KeyValue(it.name, when (it.kind) { FundKind.OPERATING -> "عملیاتی"; FundKind.RESERVE -> "اندوخته"; FundKind.OTHER -> "سایر" }) }
                Divider()
                cash.forEach { KeyValue(it.name, if (it.kind == CashKind.BANK) "بانک" else "صندوق نقدی") }
                OutlinedButton(onClick = { addingFund = true }) { Text(S.newFund) }
                OutlinedButton(onClick = { addingCash = true }) { Text(S.newCashAccount) }
            }
        }
        item {
            SectionCard(S.backupTitle, subtitle = S.backupNote) {
                Button(onClick = { backupLauncher.launch("dang-backup-${Fmt.date(vm.today, building.calendar, persian = false).replace('/', '-')}.json") }, Modifier.fillMaxWidth()) { Text(S.backupNow) }
                RestoreButton(vm)
            }
        }
        item {
            var auto by remember { mutableStateOf(vm.updater.autoCheck) }
            SectionCard(S.updTitle) {
                KeyValue(S.version, Fmt.digits(BuildConfig.VERSION_NAME))
                com.github.opscalehub.dong.ui.SwitchRow(S.updAuto, auto, { auto = it; vm.updater.autoCheck = it }, supporting = S.updAutoHint)
                OutlinedButton(onClick = { vm.updater.check(manual = true) }, Modifier.fillMaxWidth()) { Text(S.updCheckNow) }
                com.github.opscalehub.dong.ui.UpdateCard(vm.updater, inSettings = true)
            }
        }
        item {
            SectionCard(S.privacyTitle) {
                Text(S.privacyBody, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (addingFund) {
        var name by rememberSaveable { mutableStateOf("") }
        var kind by remember { mutableStateOf(FundKind.OTHER) }
        FormDialog(S.newFund, { addingFund = false }, {
            vm.run(S.saved, onSuccess = { addingFund = false }) { vm.repos.finance.addFund(building.id, name, kind) }
        }, canConfirm = name.isNotBlank()) {
            TextInput(S.name, name, { name = it })
            SingleChoice(listOf(FundKind.RESERVE, FundKind.OTHER), kind, { kind = it }) { if (it == FundKind.RESERVE) "اندوخته" else "سایر" }
        }
    }
    if (addingCash) {
        var name by rememberSaveable { mutableStateOf("") }
        var kind by remember { mutableStateOf(CashKind.BANK) }
        FormDialog(S.newCashAccount, { addingCash = false }, {
            vm.run(S.saved, onSuccess = { addingCash = false }) { vm.repos.finance.addCashAccount(building.id, name, kind) }
        }, canConfirm = name.isNotBlank()) {
            TextInput(S.name, name, { name = it })
            SingleChoice(CashKind.entries, kind, { kind = it }) { if (it == CashKind.BANK) "بانک" else "صندوق نقدی" }
        }
    }
}

/** "Restore from backup" button with file picker, bounded read and the preview/confirm dialog. */
@Composable
fun RestoreButton(vm: AppViewModel, modifier: Modifier = Modifier.fillMaxWidth()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // Untrusted input: bounded read (no API-33 readNBytes), parsed as data only.
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > BackupCodec.MAX_BYTES) error("too large")
                        }
                        String(out.toByteArray(), Charsets.UTF_8)
                    } ?: error("no stream")
                }
                preview = vm.backup.preview(text)
            } catch (e: Exception) {
                vm.message(problemText(ProblemCode.NOT_A_BACKUP))
            }
        }
    }

    OutlinedButton(onClick = { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier) { Text(S.restore) }
    preview?.let { pv -> RestoreDialog(vm, pv) { preview = null } }
}

@Composable
private fun BuildingSettingsCard(vm: AppViewModel) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    var name by rememberSaveable(building.id) { mutableStateOf(building.name) }
    var address by rememberSaveable(building.id) { mutableStateOf(building.address) }
    var currencyCode by rememberSaveable(building.id) { mutableStateOf(building.currencyCode) }
    var currencyName by rememberSaveable(building.id) { mutableStateOf(building.currencyName) }
    val digits = rememberCount(building.minorDigits.toLong())
    val rounding = rememberCount(building.roundingIncrement)
    var occ by remember(building.id) { mutableStateOf(building.occupancyBasis) }
    var cal by remember(building.id) { mutableStateOf(building.calendar) }
    // Display only: amounts stay stored exactly; Toman = Rial ÷ 10.
    var toman by remember(building.id) { mutableStateOf(building.displayDivisor == 10L) }
    SectionCard(S.buildingSettings, subtitle = S.settingsNote) {
        TextInput(S.buildingName, name, { name = it })
        TextInput(S.address, address, { address = it })
        TextInput(S.currencyCode, currencyCode, { currencyCode = it })
        TextInput(S.currencyName, currencyName, { currencyName = it })
        NumberInput(S.minorDigits, digits)
        NumberInput(S.roundingIncrement, rounding, supporting = S.roundingHint)
        Text(S.displayCurrency, style = MaterialTheme.typography.labelMedium)
        SingleChoice(listOf(true, false), toman, { toman = it }) { if (it) S.showToman else S.showRial }
        Text(S.occupancyBasis, style = MaterialTheme.typography.labelMedium)
        SingleChoice(OccupancyBasis.entries, occ, { occ = it }) { Fa.occupancyBasis(it) }
        Text(S.calendar, style = MaterialTheme.typography.labelMedium)
        SingleChoice(CalendarSystem.entries, cal, { cal = it }) { Fa.calendar(it) }
        Button(onClick = {
            vm.run(S.saved) {
                vm.repos.buildings.updateSettings(building.copy(name = name.trim(), address = address.trim(), currencyCode = currencyCode.trim(),
                    currencyName = currencyName.trim(), minorDigits = digits.value!!.toInt(), roundingIncrement = rounding.value!!, occupancyBasis = occ, calendar = cal,
                    displayCurrencyName = if (toman) S.showToman else "", displayDivisor = if (toman) 10 else 1))
            }
        }, enabled = name.isNotBlank() && digits.valid && (digits.value ?: -1) in 0..4 && (rounding.value ?: 0) >= 1) { Text(S.save) }
    }
}

@Composable
private fun RestoreDialog(vm: AppViewModel, pv: RestorePreview, close: () -> Unit) {
    val modes = remember { mutableStateMapOf<String, RestoreMode>().apply {
        pv.buildings.forEach { put(it.buildingId, if (it.status == BuildingRestoreStatus.NEW) RestoreMode.ADD_MISSING else RestoreMode.SKIP) }
    } }
    var typed by rememberSaveable { mutableStateOf("") }
    val replacing = modes.values.any { it == RestoreMode.REPLACE }
    FormDialog(S.restorePreview, close, {
        vm.run(S.restoreDone, onSuccess = close) {
            val r = vm.backup.apply(pv, modes.toMap())
            pv.buildings.firstOrNull { modes[it.buildingId] != RestoreMode.SKIP }?.let { vm.select(it.buildingId) }
            vm.message("${S.restoreDone} ${Fmt.number(r.inserted.toLong())} ${S.records}")
        }
    }, canConfirm = pv.valid && modes.values.any { it != RestoreMode.SKIP } && (!replacing || typed.trim() == S.replaceWord), confirmText = S.applyRestore) {
        if (!pv.valid) {
            Text(S.restoreInvalid, color = MaterialTheme.colorScheme.error)
            pv.problems.forEach { Hint("• " + problemText(it.code), MaterialTheme.colorScheme.error) }
            return@FormDialog
        }
        Hint("${S.version} ${Fmt.digits(pv.header.appVersion)}، ${Fmt.dateTime(pv.header.createdAt)}")
        pv.buildings.forEach { bp ->
            Column {
                Text(bp.name, style = MaterialTheme.typography.titleSmall)
                Hint(when (bp.status) { BuildingRestoreStatus.NEW -> S.restoreNew; BuildingRestoreStatus.IDENTICAL -> S.restoreIdentical; BuildingRestoreStatus.CONFLICT -> S.restoreConflict })
                Hint("${Fmt.number(bp.records.toLong())} ${S.records}، ${Fmt.number(bp.newRecords.toLong())} ${S.newRecords}، ${Fmt.number(bp.differing.toLong())} ${S.differingRecords}")
                val options = buildList {
                    add(RestoreMode.SKIP)
                    if (bp.canAddMissing) add(RestoreMode.ADD_MISSING)
                    if (bp.status != BuildingRestoreStatus.NEW) add(RestoreMode.REPLACE)
                }
                SingleChoice(options, modes[bp.buildingId] ?: RestoreMode.SKIP, { modes[bp.buildingId] = it }) {
                    when (it) { RestoreMode.SKIP -> S.modeSkip; RestoreMode.ADD_MISSING -> S.modeAddMissing; RestoreMode.REPLACE -> S.modeReplace }
                }
            }
            Divider()
        }
        if (replacing) {
            Text(S.replaceWarn, color = MaterialTheme.colorScheme.error)
            TextInput(S.replaceWord, typed, { typed = it })
        }
    }
}

@Composable
fun AuditScreen(vm: AppViewModel) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val audit by vm.audit.collectAsState()
    ScreenList {
        if (audit.isEmpty()) item { SectionCard { Hint(S.empty) } }
        items(audit.size) { i ->
            val a = audit[i]
            SectionCard {
                Text(Fa.auditAction(a.action), style = MaterialTheme.typography.labelLarge)
                Hint("${Fmt.dateTime(a.at, building.calendar)}، ${a.summary}")
            }
        }
    }
}
