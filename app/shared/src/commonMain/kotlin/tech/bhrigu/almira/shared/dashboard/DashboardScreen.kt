package tech.bhrigu.almira.shared.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.bhrigu.almira.shared.api.Breakdown
import tech.bhrigu.almira.shared.api.Dashboard
import tech.bhrigu.almira.shared.theme.AlmiraMotion
import tech.bhrigu.almira.shared.theme.AlmiraTheme
import tech.bhrigu.almira.shared.theme.CategoryColors
import tech.bhrigu.almira.shared.ui.PrimaryButton
import tech.bhrigu.almira.shared.ui.SecondaryButton

/**
 * The first screen after signing in: what this household is worth, to you.
 *
 * Every figure here is a string the server sent. Nothing is added up, rounded
 * or grouped on the device — ₹33,35,000 is Indian grouping the server did once,
 * and two members of the same household seeing different totals is the product
 * working, not a bug to reconcile.
 */
@Composable
fun DashboardScreen(
    controller: DashboardController,
    householdName: String,
    onAdd: () -> Unit,
    onSealed: () -> Unit,
    onSignOut: () -> Unit,
    footnote: String,
) {
    val state by controller.state.collectAsState()
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
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
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(colors.accent, RoundedCornerShape(AlmiraTheme.radii.sm)),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", style = type.caption, color = colors.accentInk, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(space.x2))
            Text(householdName, style = type.h4, color = colors.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onSignOut) {
                Text("Sign out", style = type.small, color = colors.accent)
            }
        }

        ScopeSwitcher(state, controller)

        when {
            state.error != null -> Column(verticalArrangement = Arrangement.spacedBy(space.x3)) {
                Text(state.error!!, style = type.small, color = colors.caution)
                SecondaryButton("Try again", enabled = true, onClick = controller::load)
            }

            state.dashboard == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(space.x16),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = colors.accent) }

            else -> {
                val dashboard = state.dashboard!!
                NetWorthCard(dashboard)
                if (dashboard.byCategory.isNotEmpty()) {
                    BreakdownCard("Where it sits", dashboard.byCategory, byCategory = true)
                }
                if (dashboard.byMember.isNotEmpty()) {
                    BreakdownCard("Whose it is", dashboard.byMember, byCategory = false)
                }
            }
        }

        PrimaryButton(label = "Add a holding", enabled = true, busy = false, onClick = onAdd)
        SecondaryButton(label = "Sealed fields", enabled = true, onClick = onSealed)

        Text(footnote, style = type.caption, color = colors.inkFaint)
        Spacer(Modifier.height(space.x8))
    }
}

/**
 * Me, the household, and each member.
 *
 * It scrolls sideways rather than wrapping or shrinking: a household of six is
 * ordinary, and a switcher that clips is a person who cannot be looked at.
 */
@Composable
private fun ScopeSwitcher(state: DashboardState, controller: DashboardController) {
    val colors = AlmiraTheme.colors
    val space = AlmiraTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(space.x2),
    ) {
        ScopePill("Me", state.scope == Scope.Me) { controller.onScopeChanged(Scope.Me) }
        ScopePill(
            state.dashboard?.scopeLabel?.takeIf { state.scope == Scope.Household } ?: "Everyone",
            state.scope == Scope.Household,
        ) { controller.onScopeChanged(Scope.Household) }
        state.members.filterNot { it.isMe }.forEach { member ->
            val target = Scope.Person(member.id, member.displayName)
            ScopePill(member.displayName, state.scope == target) { controller.onScopeChanged(target) }
        }
    }
}

@Composable
private fun ScopePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AlmiraTheme.colors
    val space = AlmiraTheme.spacing
    val background by animateColorAsState(
        if (selected) colors.accent else colors.surface,
        label = "scope-pill",
    )

    Text(
        label,
        style = AlmiraTheme.typography.small,
        color = if (selected) colors.accentInk else colors.inkMuted,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(background, RoundedCornerShape(AlmiraTheme.radii.full))
            .border(
                1.dp,
                if (selected) Color.Transparent else colors.hairline,
                RoundedCornerShape(AlmiraTheme.radii.full),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = space.x4, vertical = space.x2),
    )
}

@Composable
private fun NetWorthCard(dashboard: Dashboard) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(AlmiraTheme.radii.lg))
            .border(1.dp, colors.hairline, RoundedCornerShape(AlmiraTheme.radii.lg))
            .padding(space.x5),
        verticalArrangement = Arrangement.spacedBy(space.x2),
    ) {
        Text("TRUE NET WORTH", style = type.overline, color = colors.inkFaint)
        // Gold appears here and nowhere else (docs/02 §8).
        Text(dashboard.netWorthFormatted, style = type.amount, color = colors.gold)
        Text(dashboard.netWorthInWords, style = type.large, color = colors.inkMuted)

        Spacer(Modifier.height(space.x2))

        Line("Assets", dashboard.totalAssetsFormatted, colors.ink)
        Line("Owed", "– ${dashboard.totalLiabilitiesFormatted}", colors.caution)

        HorizontalDivider(
            modifier = Modifier.padding(vertical = space.x2),
            color = colors.hairline,
        )

        Line("Holdings", dashboard.holdingCount.toString(), colors.ink)
        Line("Loans", dashboard.liabilityCount.toString(), colors.ink)
        // A holding with no figure at all is a gap worth naming. Folding it
        // into the total as a zero would quietly understate the family.
        if (dashboard.valueConfidence.unknown > 0) {
            Line("No value yet", dashboard.valueConfidence.unknown.toString(), colors.inkMuted)
        }

        Spacer(Modifier.height(space.x1))
        Text(dashboard.disclaimer, style = type.caption, color = colors.inkFaint)
    }
}

@Composable
private fun Line(label: String, value: String, valueColor: Color) {
    val type = AlmiraTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = type.small, color = AlmiraTheme.colors.inkMuted)
        Text(
            value,
            style = type.small.copy(fontFeatureSettings = "tnum"),
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BreakdownCard(title: String, rows: List<Breakdown>, byCategory: Boolean) {
    val colors = AlmiraTheme.colors
    val type = AlmiraTheme.typography
    val space = AlmiraTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(AlmiraTheme.radii.lg))
            .border(1.dp, colors.hairline, RoundedCornerShape(AlmiraTheme.radii.lg))
            .padding(space.x5),
        verticalArrangement = Arrangement.spacedBy(space.x4),
    ) {
        Text(title, style = type.h4, color = colors.ink)

        rows.forEach { row ->
            // The category's own colour when there is one — the same twelve the
            // web uses — and the accent for people, who have no category.
            val tint = if (byCategory) CategoryColors.forCode(row.key) else colors.accent

            Column(verticalArrangement = Arrangement.spacedBy(space.x1)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(space.x2),
                ) {
                    Box(Modifier.size(8.dp).background(tint, CircleShape))
                    Text(
                        row.label,
                        style = type.small,
                        color = colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            row.valueFormatted,
                            style = type.small.copy(fontFeatureSettings = "tnum"),
                            color = colors.ink,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${trimPercent(row.percentage)}%",
                            style = type.caption,
                            color = colors.inkFaint,
                        )
                    }
                }
                Bar(fraction = (row.percentage / 100.0).coerceIn(0.0, 1.0).toFloat(), tint = tint)
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float, tint: Color) {
    val colors = AlmiraTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(colors.surfaceSunken, RoundedCornerShape(AlmiraTheme.radii.full)),
    ) {
        // A share too small to see is still a share: give it a sliver rather
        // than nothing, so "1.6%" and "0%" do not draw identically.
        Box(
            modifier = Modifier
                .fillMaxWidth(if (fraction > 0f) fraction.coerceAtLeast(0.02f) else 0f)
                .height(6.dp)
                .background(tint, RoundedCornerShape(AlmiraTheme.radii.full)),
        )
    }
}

/** 67.5 stays 67.5; 8.0 becomes 8 — the way the web writes it. */
private fun trimPercent(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}
