package tech.bhrigu.almira.shared.capture

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tech.bhrigu.almira.shared.api.InvestmentType
import tech.bhrigu.almira.shared.api.Member
import tech.bhrigu.almira.shared.theme.AlmiraMotion
import tech.bhrigu.almira.shared.theme.AlmiraTheme
import tech.bhrigu.almira.shared.theme.CategoryColors
import tech.bhrigu.almira.shared.ui.FieldShell
import tech.bhrigu.almira.shared.ui.PrimaryButton
import tech.bhrigu.almira.shared.ui.SecondaryButton
import tech.bhrigu.almira.shared.ui.almiraFieldColors

/**
 * Capture — two steps, and the second one is written by the server.
 *
 * Pick what you're adding, then describe it. Every field on the second step
 * comes from the chosen type's schema, so this file contains no knowledge of
 * fixed deposits, gold or insurance: adding an asset type is a row in a table.
 */
@Composable
fun CaptureScreen(
    controller: CaptureController,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val colors = AlmiraTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .imePadding(),
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val forward = targetState == CaptureStep.Filling
                val width = { full: Int -> if (forward) full else -full }
                (
                    slideInHorizontally(tween(AlmiraMotion.BASE), width) +
                        fadeIn(tween(AlmiraMotion.BASE))
                    ) togetherWith (
                    slideOutHorizontally(tween(AlmiraMotion.BASE)) { -width(it) } +
                        fadeOut(tween(AlmiraMotion.FAST))
                    )
            },
            label = "capture-step",
        ) { step ->
            when (step) {
                CaptureStep.Picking -> TypePicker(state, controller, onClose)
                CaptureStep.Filling -> CaptureForm(state, controller, onSaved)
            }
        }
    }
}

// --- step one ---------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypePicker(
    state: CaptureState,
    controller: CaptureController,
    onClose: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    val results = state.matches()

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The insets go on the scrolling container, so content scrolls
            // away *under* nothing: the clip is the padded box, and a field
            // leaving the top disappears below the status bar instead of
            // printing itself over the clock.
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.x5),
        verticalArrangement = Arrangement.spacedBy(space.x4),
    ) {
        Spacer(Modifier.height(space.x2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("What are you adding?", style = type.h3, color = colors.ink)
            TextButton(onClick = onClose) {
                Text("Close", style = type.small, color = colors.accent)
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = controller::onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search — FD, gold, PPF…", style = type.body) },
            shape = RoundedCornerShape(AlmiraTheme.radii.full),
            colors = almiraFieldColors(),
            textStyle = type.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(space.x16),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = colors.accent) }

            state.loadError != null -> Column(
                verticalArrangement = Arrangement.spacedBy(space.x3),
            ) {
                Text(state.loadError, style = type.small, color = colors.caution)
                SecondaryButton("Try again", enabled = true, onClick = controller::load)
            }

            state.query.isNotBlank() && results.isEmpty() -> Text(
                "Nothing matches that. “Anything Else” takes whatever this is.",
                style = type.small,
                color = colors.inkMuted,
            )

            state.query.isNotBlank() -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(space.x2),
                verticalArrangement = Arrangement.spacedBy(space.x2),
            ) {
                results.forEach { TypeChip(it) { controller.choose(it) } }
            }

            else -> state.categories.forEach { category ->
                Column(verticalArrangement = Arrangement.spacedBy(space.x2)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(space.x2),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(CategoryColors.forCode(category.categoryCode), CircleShape),
                        )
                        Text(
                            category.categoryLabel.uppercase(),
                            style = type.overline,
                            color = colors.inkFaint,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(space.x2),
                        verticalArrangement = Arrangement.spacedBy(space.x2),
                    ) {
                        category.types.forEach { TypeChip(it) { controller.choose(it) } }
                    }
                }
            }
        }

        Spacer(Modifier.height(space.x8))
    }
}

@Composable
private fun TypeChip(investmentType: InvestmentType, onClick: () -> Unit) {
    val colors = AlmiraTheme.colors
    val space = AlmiraTheme.spacing
    val tint = CategoryColors.forCode(investmentType.categoryCode)

    Row(
        modifier = Modifier
            .background(colors.surface, RoundedCornerShape(AlmiraTheme.radii.full))
            .border(1.dp, colors.hairline, RoundedCornerShape(AlmiraTheme.radii.full))
            .clickable(onClick = onClick)
            .padding(horizontal = space.x4, vertical = space.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.x2),
    ) {
        Box(Modifier.size(8.dp).background(tint, CircleShape))
        Text(investmentType.label, style = AlmiraTheme.typography.small, color = colors.ink)
    }
}

// --- step two ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureForm(
    state: CaptureState,
    controller: CaptureController,
    onSaved: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    val chosen = state.type ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = space.x5),
        verticalArrangement = Arrangement.spacedBy(space.x3),
    ) {
        Spacer(Modifier.height(space.x2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.x2),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(CategoryColors.forCode(chosen.categoryCode), CircleShape),
            )
            Text(chosen.label, style = type.h3, color = colors.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = controller::backToPicking) {
                Text("Change", style = type.small, color = colors.accent)
            }
        }

        if (state.saved != null) {
            SavedBanner(state, controller, onSaved)
            Spacer(Modifier.height(space.x8))
            return@Column
        }

        FieldShell(
            label = "What should we call it?",
            required = true,
            help = "Something you'll recognise in a list a year from now.",
            error = state.titleError,
        ) {
            OutlinedTextField(
                value = state.title,
                onValueChange = controller::onTitleChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = state.titleError != null,
                placeholder = { Text(titleHint(chosen.code, chosen.label), style = type.body) },
                shape = RoundedCornerShape(AlmiraTheme.radii.md),
                colors = almiraFieldColors(state.titleError != null),
                textStyle = type.body,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
        }

        state.essentials.forEach { field ->
            SchemaField(
                field = field,
                value = state.value(field.key),
                error = state.fieldErrors[field.key],
                onText = { controller.onFieldChanged(field.key, it) },
                onToggle = { controller.onFieldToggled(field.key, it) },
            )
        }

        InstitutionPicker(state, controller)

        Picker(
            label = "Whose is it?",
            selectedLabel = state.members.firstOrNull { it.id == state.ownerMemberId }?.let(::memberLabel),
            placeholder = "Choose",
            options = state.members.map { it.id to memberLabel(it) },
            onPick = { id -> id?.let(controller::onOwnerChanged) },
        )

        Picker(
            label = "Who can see this?",
            help = "Private means only the owner. Not even a household admin.",
            selectedLabel = visibilityLabel(state.visibility),
            placeholder = "Choose",
            options = VISIBILITIES.map { it.first to it.second },
            onPick = { id -> id?.let(controller::onVisibilityChanged) },
        )

        if (state.visibility == "scoped") {
            SharedWithPicker(state, controller)
        }

        TextButton(onClick = controller::toggleMore) {
            Text(
                if (state.showMore) "Fewer details" else "More details",
                style = type.small,
                color = colors.accent,
            )
        }

        if (state.showMore) {
            if (state.more.isEmpty()) {
                Text("Nothing else for this type.", style = type.caption, color = colors.inkFaint)
            }
            state.more.forEach { field ->
                SchemaField(
                    field = field,
                    value = state.value(field.key),
                    error = state.fieldErrors[field.key],
                    onText = { controller.onFieldChanged(field.key, it) },
                    onToggle = { controller.onFieldToggled(field.key, it) },
                )
            }
            FieldShell(label = "Notes", help = "Anything the fields above don't cover.") {
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = controller::onNotesChanged,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    shape = RoundedCornerShape(AlmiraTheme.radii.md),
                    colors = almiraFieldColors(),
                    textStyle = type.body,
                )
            }
        }

        // An error with nowhere to land still has to be seen, so it sits
        // immediately above the button that produced it.
        Text(
            text = state.formError.orEmpty(),
            style = type.caption,
            color = colors.caution,
            modifier = Modifier.height(20.dp),
        )

        PrimaryButton(
            label = "Save",
            enabled = state.title.isNotBlank(),
            busy = state.busy,
            onClick = { controller.save() },
        )

        Spacer(Modifier.height(space.x12))
    }
}

@Composable
private fun SavedBanner(
    state: CaptureState,
    controller: CaptureController,
    onSaved: () -> Unit,
) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing
    val saved = state.saved ?: return

    Column(verticalArrangement = Arrangement.spacedBy(space.x4)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.accentSoft, RoundedCornerShape(AlmiraTheme.radii.md))
                .padding(space.x4),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(space.x1)) {
                Text("Saved", style = type.overline, color = colors.accent)
                Text(
                    saved.valueFormatted?.let { "${saved.title} — $it" } ?: saved.title,
                    style = type.body,
                    color = colors.ink,
                )
                if (!saved.visibleToYou) {
                    // It saved, and the person who typed it cannot read it
                    // back. Saying so is far better than appearing to lose it.
                    Text(
                        "It's private to its owner, so it won't appear in your list.",
                        style = type.caption,
                        color = colors.inkMuted,
                    )
                }
            }
        }
        PrimaryButton(
            label = "Add another",
            enabled = true,
            busy = false,
            onClick = controller::addAnother,
        )
        SecondaryButton(label = "Done", enabled = true, onClick = onSaved)
    }
}

/**
 * The institution list is long — every bank, fund house, broker, insurer and
 * jeweller the server knows — and scrolling it on a phone to find one name is
 * the kind of friction that stops someone recording the FD at all. So this one
 * field is typed into rather than scrolled through: three letters is enough.
 *
 * It is still the same list from the same endpoint, and it still sends an id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstitutionPicker(state: CaptureState, controller: CaptureController) {
    val colors = AlmiraTheme.colors
    val chosen = state.institutions.firstOrNull { it.id == state.institutionId }
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // While the menu is shut the field shows the choice; while it is open it
    // shows what is being typed, so the two never fight over the same line.
    val shown = if (open) query else chosen?.name.orEmpty()
    val matches = remember(query, state.institutions) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) state.institutions
        else state.institutions.filter { it.name.lowercase().contains(needle) }
    }.take(30)

    FieldShell(
        label = "Where is it held?",
        help = "Which bank, fund house or broker — so you know what funds what.",
    ) {
        ExposedDropdownMenuBox(
            expanded = open,
            onExpandedChange = { open = it; if (it) query = "" },
        ) {
            OutlinedTextField(
                value = shown,
                onValueChange = { query = it; open = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                singleLine = true,
                placeholder = {
                    Text("Not linked to an institution", style = AlmiraTheme.typography.body)
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                shape = RoundedCornerShape(AlmiraTheme.radii.md),
                colors = almiraFieldColors(),
                textStyle = AlmiraTheme.typography.body,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (chosen != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Not linked to an institution",
                                style = AlmiraTheme.typography.body,
                                color = colors.inkMuted,
                            )
                        },
                        onClick = { controller.onInstitutionChanged(null); query = ""; open = false },
                    )
                }
                if (matches.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "No institution by that name.",
                                style = AlmiraTheme.typography.small,
                                color = colors.inkFaint,
                            )
                        },
                        onClick = { open = false },
                    )
                }
                matches.forEach { institution ->
                    DropdownMenuItem(
                        text = { Text(institution.name, style = AlmiraTheme.typography.body) },
                        onClick = {
                            controller.onInstitutionChanged(institution.id)
                            query = ""
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    selectedLabel: String?,
    placeholder: String,
    options: List<Pair<String?, String>>,
    onPick: (String?) -> Unit,
    help: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    FieldShell(label = label, help = help) {
        ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
            OutlinedTextField(
                value = selectedLabel ?: "",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                placeholder = { Text(placeholder, style = AlmiraTheme.typography.body) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                shape = RoundedCornerShape(AlmiraTheme.radii.md),
                colors = almiraFieldColors(),
                textStyle = AlmiraTheme.typography.body,
            )
            ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text, style = AlmiraTheme.typography.body) },
                        onClick = { onPick(value); open = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedWithPicker(state: CaptureState, controller: CaptureController) {
    val colors = AlmiraTheme.colors
    val space = AlmiraTheme.spacing

    FieldShell(label = "Shared with", help = "Everyone you choose can see it.") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(space.x2),
            verticalArrangement = Arrangement.spacedBy(space.x2),
        ) {
            state.members.filterNot { it.isMe }.forEach { member ->
                val on = member.id in state.visibleToMemberIds
                Text(
                    member.displayName,
                    style = AlmiraTheme.typography.small,
                    color = if (on) colors.accentInk else colors.ink,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier
                        .background(
                            if (on) colors.accent else colors.surface,
                            RoundedCornerShape(AlmiraTheme.radii.full),
                        )
                        .border(
                            1.dp,
                            if (on) Color.Transparent else colors.hairline,
                            RoundedCornerShape(AlmiraTheme.radii.full),
                        )
                        .clickable { controller.onSharedWithToggled(member.id) }
                        .padding(horizontal = space.x4, vertical = space.x2),
                )
            }
        }
    }
}

// --- words ------------------------------------------------------------------

private val VISIBILITIES = listOf(
    "private" to "Private — only the owner",
    "household" to "Shared with the household",
    "scoped" to "Shared with specific people",
)

private fun visibilityLabel(value: String) =
    VISIBILITIES.firstOrNull { it.first == value }?.second ?: value

private fun memberLabel(member: Member) =
    if (member.isMe) "${member.displayName} (me)" else member.displayName

/**
 * An example rather than an instruction. The placeholder shows the shape of a
 * good name for *this* type; anything not listed falls back to the type's own
 * label, which is never wrong, only less helpful.
 */
private fun titleHint(code: String, label: String) = when (code) {
    "fd" -> "SBI FD — 5 years"
    "gold_physical" -> "Wedding coins"
    "mf_sip" -> "Parag Parikh Flexi Cap"
    "stock_listed" -> "Infosys"
    "insurance_term" -> "LIC term cover"
    "property" -> "Flat, Kakinada"
    "universal" -> "A stake in Meera's bakery"
    else -> label
}
