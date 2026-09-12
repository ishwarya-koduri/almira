package tech.bhrigu.almira.shared.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.api.ApiException
import tech.bhrigu.almira.shared.api.Dashboard
import tech.bhrigu.almira.shared.api.Member

/**
 * Which totals are on screen. `Me` is what I own, `Household` is everything I
 * am allowed to see, and `Member` is one person's holdings through my eyes.
 */
sealed interface Scope {
    data object Me : Scope
    data object Household : Scope
    data class Person(val id: String, val name: String) : Scope

    /** The wire values the API expects: me | household | member. */
    val wire: String
        get() = when (this) {
            Me -> "me"
            Household -> "household"
            is Person -> "member"
        }

    val memberId: String? get() = (this as? Person)?.id
}

data class DashboardState(
    val loading: Boolean = true,
    val error: String? = null,
    val scope: Scope = Scope.Household,
    val members: List<Member> = emptyList(),
    val dashboard: Dashboard? = null,
)

/**
 * The dashboard, with no Compose in it.
 *
 * One rule matters more than everything else here, and it is the reason this
 * class is almost empty: **the app does no arithmetic and no formatting.**
 * Every figure on screen is a string the server already computed —
 * `netWorthFormatted`, each breakdown's `valueFormatted`. Two clients that both
 * add up holdings will eventually disagree, and a family registry that shows
 * one number on a phone and a different one in a browser has destroyed the only
 * thing it sells. So the totals arrive finished.
 *
 * That is also what makes the privacy model true rather than claimed: the
 * server filters by what this viewer may see before it adds anything up, so a
 * holding someone else marked private is not hidden in the client — it was
 * never in the number.
 */
class DashboardController(
    private val api: AlmiraApi,
    private val householdId: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var inFlight: Job? = null

    init {
        scope.launch {
            // The switcher needs names; a failure here costs the member lenses
            // but must not cost the totals.
            runCatching { api.members(householdId) }
                .onSuccess { members -> _state.update { it.copy(members = members) } }
        }
        load()
    }

    fun onScopeChanged(next: Scope) {
        if (next == _state.value.scope) return
        _state.update { it.copy(scope = next) }
        load()
    }

    fun load() {
        // A fast tap through the switcher must not let an older answer land
        // last and label itself with the newer scope.
        inFlight?.cancel()
        val current = _state.value.scope
        _state.update { it.copy(loading = true, error = null) }
        inFlight = scope.launch {
            try {
                val dashboard = api.dashboard(householdId, current.wire, current.memberId)
                _state.update {
                    if (it.scope != current) it
                    else it.copy(loading = false, dashboard = dashboard, error = null)
                }
            } catch (failure: ApiException) {
                _state.update {
                    if (it.scope != current) it else it.copy(loading = false, error = failure.message)
                }
            }
        }
    }
}
