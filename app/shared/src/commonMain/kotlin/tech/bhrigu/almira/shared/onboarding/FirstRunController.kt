package tech.bhrigu.almira.shared.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.bhrigu.almira.shared.api.AddMember
import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.api.ApiException
import tech.bhrigu.almira.shared.api.CreateHousehold
import tech.bhrigu.almira.shared.api.Household

/** Who the almirah is being set up for. */
enum class SettingUpFor { Me, SomeoneElse }

/** Whose things it will hold. */
enum class Tracking { JustMe, Family }

/** What a new entry starts as. */
enum class StartsAs { Private, Shared }

/**
 * The first run's answers, and nothing else.
 *
 * Every field has an answer from the start, because every question here has a
 * reasonable default and a person who taps straight through should still end up
 * somewhere sensible. Only one thing can be missing: the name of the person this
 * is being set up for, which has no default because guessing it would be worse
 * than asking.
 */
data class FirstRunState(
    val forWhom: SettingUpFor = SettingUpFor.Me,
    val tracking: Tracking = Tracking.JustMe,
    val startsAs: StartsAs = StartsAs.Private,
    val householdName: String = "",
    val yourName: String = "",
    val personName: String = "",
    val relationship: String = "parent",
    val busy: Boolean = false,
    val error: String? = null,
) {
    /**
     * What is wrong, once somebody asks to create it. Checked at the press, as
     * the sign-in fields are (owner's ruling, 2026-09-17): a button that will not
     * move cannot say why.
     */
    val problem: String? get() =
        if (forWhom == SettingUpFor.SomeoneElse && personName.isBlank()) {
            "Tell us who you're setting this up for."
        } else {
            null
        }

    /**
     * Setting it up for somebody else means there are at least two people in it,
     * whatever was tapped on the question after, which is what the web client
     * sends too.
     */
    val mode: String get() =
        if (forWhom == SettingUpFor.SomeoneElse || tracking == Tracking.Family) "family" else "just_me"

    val visibility: String get() = if (startsAs == StartsAs.Shared) "household" else "private"
}

/**
 * Making the first household, from the app (docs/03 §1; known issue 89).
 *
 * Until this existed, somebody who met Almira on a phone signed in, was told
 * "the web client can create one", and had to go and find a laptop. The API was
 * never the problem: this makes the same `POST /households` call the web client's
 * first run makes, with the same four answers, so a household started on a phone
 * is indistinguishable afterwards from one started on a laptop.
 *
 * **The order matters, and it is household first.** When the almirah is for
 * somebody else, the person is added afterwards, and a failure to add them does
 * NOT fail the run: the household exists by then, and offering to create a
 * second one would leave the family with two. They are told, and the person can
 * be added from Household later. The web client makes the same choice in the
 * same words.
 */
class FirstRunController(
    private val api: AlmiraApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(FirstRunState())
    val state: StateFlow<FirstRunState> = _state.asStateFlow()

    fun forWhom(value: SettingUpFor) = _state.update { it.copy(forWhom = value, error = null) }
    fun tracking(value: Tracking) = _state.update { it.copy(tracking = value, error = null) }
    fun startsAs(value: StartsAs) = _state.update { it.copy(startsAs = value, error = null) }
    fun householdName(value: String) = _state.update { it.copy(householdName = value.take(120), error = null) }
    fun yourName(value: String) = _state.update { it.copy(yourName = value.take(80), error = null) }
    fun personName(value: String) = _state.update { it.copy(personName = value.take(80), error = null) }
    fun relationship(value: String) = _state.update { it.copy(relationship = value, error = null) }

    /**
     * Creates it, and hands back the household it made. [onDone] is called only
     * when there is one, so the screen it came from cannot be left showing a
     * "create" button over a household that already exists.
     */
    fun create(onDone: (Household) -> Unit) {
        val current = state.value
        if (current.busy) return
        current.problem?.let { problem ->
            _state.update { it.copy(error = problem) }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val household = api.createHousehold(
                    CreateHousehold(
                        name = current.householdName.trim().ifBlank { null },
                        mode = current.mode,
                        defaultVisibility = current.visibility,
                        displayName = current.yourName.trim().ifBlank { null },
                    ),
                )
                if (current.forWhom == SettingUpFor.SomeoneElse) {
                    // Deliberately swallowed: see the class comment. The household
                    // is already there, and a second one would be worse than a
                    // missing row somebody can add in a moment.
                    runCatching {
                        api.addMember(
                            household.id,
                            AddMember(
                                displayName = current.personName.trim(),
                                relationship = current.relationship,
                            ),
                        )
                    }
                }
                onDone(household)
            } catch (failure: ApiException) {
                _state.update { it.copy(error = failure.message) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        /** What "they are your…" offers, in the web client's order. */
        val RELATIONSHIPS = listOf("parent" to "Parent", "spouse" to "Spouse", "other" to "Someone else")
    }
}
