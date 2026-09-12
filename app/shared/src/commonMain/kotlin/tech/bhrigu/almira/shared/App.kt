package tech.bhrigu.almira.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.api.ApiException
import tech.bhrigu.almira.shared.api.InMemoryTokenStore
import tech.bhrigu.almira.shared.theme.AlmiraTheme
import tech.bhrigu.almira.shared.theme.CategoryColors

/**
 * The skeleton's one screen.
 *
 * It exists to prove three things at once: that the shared module compiles and
 * renders on a real device, that the theme from docs/02 is genuinely wired in
 * rather than a file nobody reads, and that a stock Material `Button` already
 * comes out in Almira's accent without being restyled at the call site.
 *
 * It is deliberately not the sign-in screen yet — that is the next stage. What
 * it does now is reach the API, which is the only way to know the client works
 * from inside an emulator rather than from a terminal on the host.
 */
@Composable
fun App(apiBaseUrl: String, platformName: String) {
    // Built once and remembered: an HttpClient per recomposition would leak a
    // connection pool every frame.
    val api = remember(apiBaseUrl) { AlmiraApi(apiBaseUrl, InMemoryTokenStore()) }
    var connection by remember { mutableStateOf<ConnectionState>(ConnectionState.Checking) }

    LaunchedEffect(api) {
        connection = try {
            val health = api.health()
            ConnectionState.Reached(
                environment = health.environment,
                role = health.dbRole,
                rlsEnforced = health.rlsEnforced,
            )
        } catch (failure: ApiException) {
            ConnectionState.Failed(failure.message)
        }
    }

    AlmiraTheme {
        val colors = AlmiraTheme.colors
        val type = AlmiraTheme.typography
        val space = AlmiraTheme.spacing
        val radii = AlmiraTheme.radii

        Box(
            modifier = Modifier.fillMaxSize().background(colors.canvas),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(space.x5)
                    .background(colors.surface, RoundedCornerShape(radii.lg))
                    .border(1.dp, colors.hairline, RoundedCornerShape(radii.lg))
                    .padding(space.x6),
                verticalArrangement = Arrangement.spacedBy(space.x3),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(space.x2),
                ) {
                    Box(
                        modifier = Modifier
                            .size(space.x8)
                            .background(colors.accent, RoundedCornerShape(radii.sm)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("A", style = type.h4, color = colors.accentInk)
                    }
                    Text("Almira", style = type.h3, color = colors.ink)
                }

                Text(
                    "Everything your family owns and owes, in one calm, private place.",
                    style = type.body,
                    color = colors.inkMuted,
                )

                Spacer(Modifier.height(space.x1))

                // The gold appears exactly once, on the number — which is the
                // whole rule about it (docs/02 §2).
                Text("TRUE NET WORTH", style = type.overline, color = colors.inkFaint)
                Text("₹14,93,750", style = type.amount, color = colors.gold)
                Text("Fourteen Lakh Ninety-Three Thousand", style = type.small, color = colors.inkMuted)

                Spacer(Modifier.height(space.x2))

                // Category dots: colour never carries meaning on its own, so
                // each is paired with its label.
                Row(horizontalArrangement = Arrangement.spacedBy(space.x3)) {
                    listOf(
                        "Gold" to CategoryColors.gold,
                        "Deposits" to CategoryColors.deposits,
                        "Property" to CategoryColors.realEstate,
                    ).forEach { (label, dot) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(space.x1),
                        ) {
                            Box(Modifier.size(space.x2).background(dot, CircleShape))
                            Text(label, style = type.caption, color = colors.inkMuted)
                        }
                    }
                }

                Spacer(Modifier.height(space.x2))

                // Unstyled on purpose: it picks up the accent from the Material
                // scheme the theme derives, which is the half of a design system
                // that is easy to leave out.
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign in", style = type.body)
                }

                // The connection check. It earns its place on a skeleton: it is
                // the difference between "the app compiles" and "the app can
                // talk to its server from inside an emulator".
                val (line, tone) = when (val state = connection) {
                    ConnectionState.Checking ->
                        "Checking the connection…" to colors.inkFaint

                    is ConnectionState.Reached ->
                        "Connected · ${state.environment} · as ${state.role}" to
                            if (state.rlsEnforced) colors.positive else colors.caution

                    is ConnectionState.Failed ->
                        state.message to colors.caution
                }

                Text(
                    line,
                    style = type.caption,
                    color = tone,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "$platformName · $apiBaseUrl",
                    style = type.caption,
                    color = colors.inkFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** What the skeleton knows about its server. */
private sealed interface ConnectionState {
    data object Checking : ConnectionState

    data class Reached(
        val environment: String,
        val role: String,
        /**
         * False would mean the server is serving as the schema owner, with every
         * privacy policy bypassed. It is shown in the caution colour rather than
         * hidden, because that is a thing a developer should see immediately.
         */
        val rlsEnforced: Boolean,
    ) : ConnectionState

    data class Failed(val message: String) : ConnectionState
}
