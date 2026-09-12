package tech.bhrigu.almira.shared.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import tech.bhrigu.almira.shared.api.currentTimeMillis
import tech.bhrigu.almira.shared.theme.AlmiraTheme
import tech.bhrigu.almira.shared.ui.FieldShell
import tech.bhrigu.almira.shared.ui.almiraFieldColors

/**
 * One control per `dataType`, and nothing that knows what the field means.
 *
 * The switch below is the whole type-awareness of the app's capture form: the
 * schema says "percent", so a decimal keypad and a "% p.a." suffix appear. It
 * never says "interest rate", because no client should know that a Fixed
 * Deposit has one.
 */
@Composable
fun SchemaField(
    field: FormField,
    value: FieldValue,
    error: String?,
    onText: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    when (field.kind) {
        FieldKind.Bool -> BoolField(field, value, error, onToggle)
        FieldKind.Select -> SelectField(field, value, error, onText)
        FieldKind.Date -> DateField(field, value, error, onText)
        else -> TextLikeField(field, value, error, onText)
    }
}

@Composable
private fun TextLikeField(
    field: FormField,
    value: FieldValue,
    error: String?,
    onText: (String) -> Unit,
) {
    val keyboard = when (field.kind) {
        // Decimal rather than Number: a weight is 6.3g and a rate is 7.15%,
        // and a keypad without a decimal point makes those impossible to type.
        FieldKind.Money, FieldKind.Number, FieldKind.Percent -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }
    FieldShell(field.label, field.required, field.help, error) {
        OutlinedTextField(
            value = value.text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            placeholder = field.placeholder?.let { { Text(it, style = AlmiraTheme.typography.body) } },
            // ₹ as a prefix rather than inside the label: it belongs next to
            // the digits, where a missing zero is caught by eye.
            prefix = if (field.kind == FieldKind.Money) {
                { Text("₹", style = AlmiraTheme.typography.body) }
            } else null,
            suffix = field.unit?.let { { Text(it, style = AlmiraTheme.typography.caption) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
            shape = RoundedCornerShape(AlmiraTheme.radii.md),
            colors = almiraFieldColors(error != null),
            // An amount gets a little more weight and tabular figures so a
            // missing zero is visible, but not the dashboard's 33sp `amount`
            // style — that is a headline, and in a form row it makes one field
            // twice the height of its neighbours.
            textStyle = if (field.kind == FieldKind.Money) {
                AlmiraTheme.typography.large.copy(fontFeatureSettings = "tnum")
            } else {
                AlmiraTheme.typography.body
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectField(
    field: FormField,
    value: FieldValue,
    error: String?,
    onText: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = field.options.firstOrNull { it.value == value.text }

    FieldShell(field.label, field.required, field.help, error) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = chosen?.label ?: "",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(
                    androidx.compose.material3.MenuAnchorType.PrimaryNotEditable,
                ),
                isError = error != null,
                placeholder = { Text("Choose", style = AlmiraTheme.typography.body) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                shape = RoundedCornerShape(AlmiraTheme.radii.md),
                colors = almiraFieldColors(error != null),
                textStyle = AlmiraTheme.typography.body,
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                field.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, style = AlmiraTheme.typography.body) },
                        onClick = { onText(option.value); open = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    field: FormField,
    value: FieldValue,
    error: String?,
    onText: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    FieldShell(field.label, field.required, field.help, error) {
        Box {
            OutlinedTextField(
                value = value.text.takeIf { it.isNotEmpty() }?.let(::readableDate) ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = false,
                modifier = Modifier.fillMaxWidth().clickable { open = true },
                isError = error != null,
                placeholder = { Text("Pick a date", style = AlmiraTheme.typography.body) },
                shape = RoundedCornerShape(AlmiraTheme.radii.md),
                // Disabled, not read-only: that is what makes the whole row
                // tappable instead of opening a keyboard on a field nobody can
                // type into. So its disabled colours have to look like an
                // ordinary field rather than like something switched off.
                colors = OutlinedTextFieldDefaults.colors(
                    disabledTextColor = AlmiraTheme.colors.ink,
                    disabledContainerColor = AlmiraTheme.colors.surface,
                    disabledBorderColor = if (error != null) {
                        AlmiraTheme.colors.caution
                    } else {
                        AlmiraTheme.colors.hairline
                    },
                    disabledPlaceholderColor = AlmiraTheme.colors.inkFaint,
                ),
                textStyle = AlmiraTheme.typography.body,
            )
        }
    }

    if (open) {
        // An empty field opens on today, already selected. Opening with nothing
        // chosen means "Choose" quietly does nothing, which reads as a broken
        // button rather than as a question still unanswered.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.text.takeIf { it.isNotEmpty() }?.let(::isoToMillis)
                ?: todayMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onText(millisToIso(it)) }
                    open = false
                }) { Text("Choose") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun BoolField(
    field: FormField,
    value: FieldValue,
    error: String?,
    onToggle: (Boolean) -> Unit,
) {
    val colors = AlmiraTheme.colors
    FieldShell(label = field.label, required = field.required, help = field.help, error = error) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (value.checked) "Yes" else "Not recorded",
                style = AlmiraTheme.typography.body,
                color = if (value.checked) colors.ink else colors.inkFaint,
            )
            Switch(
                checked = value.checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.accentInk,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.surface,
                    uncheckedTrackColor = colors.hairline,
                ),
            )
        }
    }
}

// --- dates ------------------------------------------------------------------

/**
 * ISO in, ISO out, with a readable face.
 *
 * Written by hand rather than pulled in with kotlinx-datetime: this is the only
 * date arithmetic in the app, the algorithm is Howard Hinnant's well-known
 * days-from-civil pair, and adding a dependency that has to be checked against
 * this Kotlin version for four functions is a poor trade.
 */
private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private const val MILLIS_PER_DAY = 86_400_000L

/** UTC midnight today, which is the frame the picker itself reports in. */
internal fun todayMillis(): Long =
    floorDiv(currentTimeMillis(), MILLIS_PER_DAY) * MILLIS_PER_DAY

internal fun readableDate(iso: String): String {
    val parts = iso.split("-")
    if (parts.size != 3) return iso
    val month = parts[1].toIntOrNull() ?: return iso
    val day = parts[2].toIntOrNull() ?: return iso
    return "$day ${MONTHS.getOrElse(month - 1) { parts[1] }} ${parts[0]}"
}

internal fun isoToMillis(iso: String): Long? {
    val parts = iso.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return daysFromCivil(y, m, d) * MILLIS_PER_DAY
}

internal fun millisToIso(millis: Long): String {
    // The picker reports UTC midnight, so a plain floor is right here and no
    // timezone shifting is wanted: the date someone tapped is the date meant.
    val days = floorDiv(millis, MILLIS_PER_DAY)
    val (y, m, d) = civilFromDays(days)
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0) q - 1 else q
}

private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
    val year = if (m <= 2) y - 1 else y
    val era = (if (year >= 0) year else year - 399) / 400
    val yoe = year - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365L + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}

private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468L
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
}
