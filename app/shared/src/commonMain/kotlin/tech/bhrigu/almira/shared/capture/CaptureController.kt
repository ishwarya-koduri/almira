package tech.bhrigu.almira.shared.capture

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.api.ApiException
import tech.bhrigu.almira.shared.api.Institution
import tech.bhrigu.almira.shared.api.InvestmentType
import tech.bhrigu.almira.shared.api.Member
import tech.bhrigu.almira.shared.api.TaxonomyCategory

/** Pick what you're adding, then describe it. Nothing else is ever on screen. */
enum class CaptureStep { Picking, Filling }

data class SavedRecord(
    val title: String,
    val valueFormatted: String?,
    /**
     * False when the record saved as Private for someone else — it exists, and
     * the person who typed it cannot read it back. Worth saying out loud.
     */
    val visibleToYou: Boolean,
)

data class CaptureState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val categories: List<TaxonomyCategory> = emptyList(),
    val members: List<Member> = emptyList(),
    val institutions: List<Institution> = emptyList(),

    val step: CaptureStep = CaptureStep.Picking,
    val query: String = "",

    val type: InvestmentType? = null,
    val fields: List<FormField> = emptyList(),
    val values: Map<String, FieldValue> = emptyMap(),
    val title: String = "",
    val ownerMemberId: String? = null,
    val institutionId: String? = null,
    val visibility: String = "household",
    val visibleToMemberIds: List<String> = emptyList(),
    val notes: String = "",
    val showMore: Boolean = false,

    val busy: Boolean = false,
    val titleError: String? = null,
    val formError: String? = null,
    /** Keyed the way [FormField.key] is, so a message lands on its own control. */
    val fieldErrors: Map<String, String> = emptyMap(),
    val saved: SavedRecord? = null,
) {
    val essentials: List<FormField> get() = fields.filter { it.essential }
    val more: List<FormField> get() = fields.filterNot { it.essential }

    /** Types matching the search box, flattened across categories. */
    fun matches(): List<InvestmentType> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return categories.flatMap { it.types }.filter {
            it.label.lowercase().contains(needle) ||
                it.categoryLabel.lowercase().contains(needle) ||
                it.code.lowercase().contains(needle)
        }
    }

    fun value(key: String): FieldValue = values[key] ?: FieldValue()
}

/**
 * Capture, with no Compose in it.
 *
 * The form this drives is generated from the chosen type's schema (see
 * [toFormFields]), so this class knows nothing about fixed deposits or gold. It
 * holds values as text, sends them, and shows whatever the server says came
 * back wrong — deliberately *not* re-implementing the server's validation,
 * because a second copy of those rules is exactly how a form starts accepting
 * something the API rejects.
 *
 * The one thing checked here is a missing name, because that question can be
 * answered without a round trip and losing a filled form to a server error
 * about a blank title would be unkind.
 */
class CaptureController(
    private val api: AlmiraApi,
    private val householdId: String,
    private val scope: CoroutineScope,
    private val defaultVisibility: String = "household",
) {
    private val _state = MutableStateFlow(CaptureState(visibility = defaultVisibility))
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        scope.launch {
            try {
                // Three independent reads; no reason to pay for them in series.
                val (taxonomy, members, institutions) = awaitAll(
                    async { api.taxonomy(householdId) },
                    async { api.members(householdId) },
                    async { api.institutions(householdId) },
                ).let {
                    @Suppress("UNCHECKED_CAST")
                    Triple(
                        it[0] as List<TaxonomyCategory>,
                        it[1] as List<Member>,
                        it[2] as List<Institution>,
                    )
                }
                _state.update {
                    it.copy(
                        loading = false,
                        categories = taxonomy,
                        members = members,
                        institutions = institutions,
                        ownerMemberId = it.ownerMemberId
                            ?: members.firstOrNull { m -> m.isMe }?.id
                            ?: members.firstOrNull()?.id,
                    )
                }
            } catch (failure: ApiException) {
                _state.update { it.copy(loading = false, loadError = failure.message) }
            }
        }
    }

    // --- picking ------------------------------------------------------------

    fun onQueryChanged(query: String) = _state.update { it.copy(query = query) }

    fun choose(type: InvestmentType) = _state.update {
        it.copy(
            step = CaptureStep.Filling,
            type = type,
            fields = type.schema.toFormFields(),
            values = emptyMap(),
            title = "",
            institutionId = null,
            notes = "",
            showMore = false,
            titleError = null,
            formError = null,
            fieldErrors = emptyMap(),
        )
    }

    fun backToPicking() = _state.update { it.copy(step = CaptureStep.Picking, query = "") }

    // --- filling ------------------------------------------------------------

    fun onTitleChanged(title: String) =
        _state.update { it.copy(title = title, titleError = null) }

    fun onFieldChanged(key: String, text: String) = _state.update {
        it.copy(
            values = it.values + (key to it.value(key).copy(text = text)),
            // A message that survives the edit it was about becomes noise.
            fieldErrors = it.fieldErrors - key,
        )
    }

    fun onFieldToggled(key: String, checked: Boolean) = _state.update {
        it.copy(
            values = it.values + (key to it.value(key).copy(checked = checked)),
            fieldErrors = it.fieldErrors - key,
        )
    }

    fun onOwnerChanged(memberId: String) = _state.update { it.copy(ownerMemberId = memberId) }

    fun onInstitutionChanged(id: String?) = _state.update { it.copy(institutionId = id) }

    fun onNotesChanged(notes: String) = _state.update { it.copy(notes = notes) }

    fun onVisibilityChanged(visibility: String) = _state.update {
        it.copy(
            visibility = visibility,
            visibleToMemberIds = if (visibility == "scoped") it.visibleToMemberIds else emptyList(),
            formError = null,
        )
    }

    fun onSharedWithToggled(memberId: String) = _state.update {
        val next = if (memberId in it.visibleToMemberIds) it.visibleToMemberIds - memberId
        else it.visibleToMemberIds + memberId
        it.copy(visibleToMemberIds = next, formError = null)
    }

    fun toggleMore() = _state.update { it.copy(showMore = !it.showMore) }

    fun dismissSaved() = _state.update { it.copy(saved = null) }

    /** Back to an empty form of the same type, for the next one in the pile. */
    fun addAnother() = _state.update {
        it.copy(
            values = emptyMap(),
            title = "",
            notes = "",
            showMore = false,
            titleError = null,
            formError = null,
            fieldErrors = emptyMap(),
            saved = null,
        )
    }

    fun save(onSaved: (SavedRecord) -> Unit = {}) {
        val current = _state.value
        val type = current.type ?: return
        if (current.busy) return

        if (current.title.isBlank()) {
            _state.update { it.copy(titleError = "Give it a name") }
            return
        }
        if (current.visibility == "scoped" && current.visibleToMemberIds.isEmpty()) {
            _state.update { it.copy(formError = "Choose at least one person to share it with.") }
            return
        }

        _state.update { it.copy(busy = true, formError = null, fieldErrors = emptyMap()) }
        scope.launch {
            try {
                val response = api.createInvestment(
                    householdId,
                    buildCreateBody(
                        type = type,
                        fields = current.fields,
                        values = current.values,
                        title = current.title,
                        ownerMemberId = current.ownerMemberId,
                        institutionId = current.institutionId,
                        visibility = current.visibility,
                        visibleToMemberIds = current.visibleToMemberIds,
                        notes = current.notes,
                    ),
                )
                val saved = SavedRecord(
                    title = response.investment?.title ?: current.title.trim(),
                    valueFormatted = response.investment?.valueFormatted,
                    visibleToYou = response.visibleToYou,
                )
                _state.update { it.copy(busy = false, saved = saved) }
                onSaved(saved)
            } catch (failure: ApiException) {
                // The server names the fields it rejected. Put each message on
                // its own control; anything unattributed goes above the button,
                // because an error with nowhere to land must still be visible.
                val byField = failure.fieldErrors
                val known = current.fields.map { it.key }.toSet()
                val placed = mutableMapOf<String, String>()
                val orphans = mutableListOf<String>()
                byField.forEach { (key, message) ->
                    val attr = FormField.ATTR + key
                    when {
                        attr in known -> placed[attr] = message
                        key in known -> placed[key] = message
                        else -> orphans += message
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        fieldErrors = placed,
                        formError = when {
                            orphans.isNotEmpty() -> orphans.joinToString(" ")
                            placed.isNotEmpty() -> null
                            else -> failure.message
                        },
                        showMore = it.showMore ||
                            placed.keys.any { key -> it.fields.any { f -> f.key == key && !f.essential } },
                    )
                }
            }
        }
    }
}
