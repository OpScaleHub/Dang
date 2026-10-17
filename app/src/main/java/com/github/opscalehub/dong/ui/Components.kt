package com.github.opscalehub.dong.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.opscalehub.dong.domain.model.CalendarSystem
import com.github.opscalehub.dong.i18n.Fmt

/** Standard scrollable screen body with 16dp gutters. */
@Composable
fun ScreenList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)

@Composable
fun KeyValue(key: String, value: String, strong: Boolean = false, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, color = valueColor, textAlign = TextAlign.End)
    }
}

@Composable
fun ClickRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(vertical = 2.dp))

@Composable
fun EmptyText(text: String = S.empty) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    AssistChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = color))
}

@Composable
fun BalanceText(minor: Long, digits: Int = 0) {
    val (label, color) = when {
        minor > 0 -> S.owes to MaterialTheme.colorScheme.error
        minor < 0 -> S.credit to MaterialTheme.colorScheme.primary
        else -> S.settled to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text("${Fmt.money(kotlin.math.abs(minor), digits)} $label", color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}

// ------------------------------------------------------------------ form fields

@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null,
              keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, supporting: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth(),
        singleLine = singleLine, isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}

/** Holds the text of a numeric field and its parsed value. */
class NumberFieldState(initial: String, private val parser: (String) -> Long?, val preview: ((Long) -> String)? = null) {
    var text by mutableStateOf(initial)
    val value: Long? get() = parser(text)
    val valid: Boolean get() = value != null
}

@Composable
fun rememberMoney(initial: Long? = null, digits: Int = 0) =
    remember { NumberFieldState(initial?.let { Fmt.money(it, digits) } ?: "", { Fmt.parseMoney(it, digits) }, { Fmt.money(it, digits) }) }

@Composable
fun rememberCubic(initial: Long? = null) = remember { NumberFieldState(initial?.let { Fmt.cubicMeters(it) } ?: "", parser = { Fmt.parseCubicMeters(it) }) }

@Composable
fun rememberCount(initial: Long? = null) = remember { NumberFieldState(initial?.let { Fmt.digits(it.toString()) } ?: "", parser = { Fmt.parseLong(it) }) }

@Composable
fun NumberInput(label: String, state: NumberFieldState, modifier: Modifier = Modifier, allowEmpty: Boolean = false, supporting: String? = null, showError: Boolean = true) {
    val err = if (!showError || (allowEmpty && state.text.isBlank())) null else if (state.text.isNotBlank() && !state.valid) S.invalidNumber else null
    // Money fields echo the parsed amount with grouping so a missing or extra zero is easy to spot.
    val echo = state.preview?.let { p -> state.value?.let(p) }
    TextInput(label, state.text, { state.text = it }, modifier, err, KeyboardType.Decimal, supporting = listOfNotNull(echo, supporting).joinToString(" — ").ifBlank { null })
}

/** Date field in the building calendar; accepts Persian or Latin digits. */
class DateFieldState(initial: Long?, val calendar: CalendarSystem) {
    var text by mutableStateOf(initial?.let { Fmt.date(it, calendar) } ?: "")
    val value: Long? get() = Fmt.parseDate(text, calendar)
    fun set(epochDay: Long) {
        text = Fmt.date(epochDay, calendar)
    }
}

@Composable
fun rememberDate(initial: Long?, calendar: CalendarSystem = CalendarSystem.JALALI) = remember { DateFieldState(initial, calendar) }

@Composable
fun DateInput(label: String, state: DateFieldState, today: Long, modifier: Modifier = Modifier, allowEmpty: Boolean = false) {
    val bad = state.text.isNotBlank() && state.value == null
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.text, onValueChange = { state.text = it }, label = { Text(label) }, singleLine = true,
            isError = bad || (!allowEmpty && state.text.isBlank()), modifier = Modifier.weight(1f),
            supportingText = if (bad) ({ Text(S.invalidDate) }) else state.value?.let { v -> { Text(Fmt.longDate(v, state.calendar)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        TextButton(onClick = { state.set(today) }) { Text(S.today) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T?, onSelect: (T) -> Unit, text: (T) -> String, modifier: Modifier = Modifier, error: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.let(text) ?: "", onValueChange = {}, readOnly = true, label = { Text(label) },
            placeholder = { Text(S.select) }, isError = error,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = {
                    onSelect(o)
                    open = false
                })
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) Hint(supporting)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipSelect(options: List<T>, selected: Set<T>, onToggle: (T) -> Unit, text: (T) -> String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o in selected, onClick = { onToggle(o) }, label = { Text(text(o)) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SingleChoice(options: List<T>, selected: T, onSelect: (T) -> Unit, text: (T) -> String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(text(o)) }) }
    }
}

/** Full-height dialog for forms; [onConfirm] enabled only when [canConfirm]. */
@Composable
fun FormDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit, canConfirm: Boolean = true, confirmText: String = S.save,
               content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = canConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit, confirmText: String = S.confirm) =
    FormDialog(title, onDismiss, onConfirm, confirmText = confirmText) { Text(body) }

/** Asks for a mandatory reason (reversals, voids, corrections). */
@Composable
fun ReasonDialog(title: String, hint: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    FormDialog(title, onDismiss, { onConfirm(reason.trim()) }, canConfirm = reason.isNotBlank(), confirmText = S.confirm) {
        if (hint != null) Hint(hint)
        TextInput(S.reasonRequiredHint, reason, { reason = it }, singleLine = false)
    }
}

@Composable
fun Gap(h: Int = 4) = Spacer(Modifier.height(h.dp))

@Composable
fun BoxCenter(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
