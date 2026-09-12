package tech.bhrigu.almira.shared.zk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.api.ApiException
import tech.bhrigu.almira.shared.api.InvestmentRow
import tech.bhrigu.almira.shared.api.SealedField

data class OpenedField(val fieldKey: String, val outcome: OpenOutcome)

data class ZkState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val caveats: List<String> = emptyList(),
    val unlocked: Boolean = false,
    val passphrase: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val holdings: List<InvestmentRow> = emptyList(),
    val chosen: InvestmentRow? = null,
    val fields: List<OpenedField> = emptyList(),
    val newFieldKey: String = "",
    val newValue: String = "",
)

/**
 * Zero-knowledge mode, with no Compose in it.
 *
 * Holds nothing secret itself: the content key lives in [ZkVault] and only
 * while the app is in front (B9). This class holds the passphrase in state only
 * for as long as the field is on screen, and clears it the moment it has been
 * used — a passphrase sitting in a state object after it has done its job is a
 * passphrase in a heap dump.
 */
class ZkController(
    private val api: AlmiraApi,
    private val householdId: String,
    private val vault: ZkVault,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ZkState(unlocked = vault.isUnlocked))
    val state: StateFlow<ZkState> = _state.asStateFlow()

    init { load() }

    fun load() {
        scope.launch {
            try {
                val status = api.e2eStatus(householdId)
                val holdings = runCatching { api.investments(householdId) }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        loading = false,
                        enabled = status.enabled,
                        caveats = status.caveats,
                        unlocked = vault.isUnlocked,
                        holdings = holdings,
                    )
                }
                if (vault.isUnlocked) _state.value.chosen?.let(::choose)
            } catch (failure: ApiException) {
                _state.update { it.copy(loading = false, message = failure.message) }
            }
        }
    }

    fun onPassphraseChanged(text: String) =
        _state.update { it.copy(passphrase = text, message = null) }

    fun unlock() {
        val passphrase = _state.value.passphrase
        if (passphrase.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            val outcome = try {
                vault.unlock(householdId, passphrase)
            } catch (failure: ApiException) {
                _state.update { it.copy(busy = false, message = failure.message) }
                return@launch
            }
            _state.update {
                it.copy(
                    busy = false,
                    unlocked = outcome is UnlockOutcome.Unlocked,
                    // Gone from state as soon as it has been used, whether or
                    // not it worked.
                    passphrase = "",
                    message = when (outcome) {
                        UnlockOutcome.Unlocked -> null
                        UnlockOutcome.WrongPassphrase ->
                            "That passphrase doesn't open this. Nothing has been changed."
                        UnlockOutcome.NotSetUp ->
                            "No passphrase has been set up for this household yet."
                        UnlockOutcome.NewerVersion ->
                            "This was sealed by a newer version of Almira."
                        UnlockOutcome.UnknownScheme ->
                            "This household uses an encryption scheme this app doesn't know."
                        UnlockOutcome.Unreadable ->
                            "The stored key doesn't look like a key."
                    },
                )
            }
            // Only on success. Re-reading the chosen holding clears `message` on
            // its way past, so doing it after a failure erased the very
            // sentence that explains the failure — the screen stayed locked,
            // which is correct, and said nothing about why, which is not. A
            // silent refusal on a passphrase that cannot be recovered is close
            // to the worst thing this screen could do.
            if (outcome is UnlockOutcome.Unlocked) _state.value.chosen?.let(::choose)
        }
    }

    fun lock() {
        vault.forget()
        _state.update { it.copy(unlocked = false, fields = emptyList(), passphrase = "") }
    }

    fun choose(holding: InvestmentRow) {
        _state.update { it.copy(chosen = holding, fields = emptyList(), message = null) }
        scope.launch {
            try {
                val values: List<SealedField> =
                    api.sealedValues(householdId, RECORD_TYPE, holding.id)
                _state.update { current ->
                    current.copy(
                        fields = values.map { OpenedField(it.fieldKey, vault.open(householdId, it)) },
                    )
                }
            } catch (failure: ApiException) {
                _state.update { it.copy(message = failure.message) }
            }
        }
    }

    fun onFieldKeyChanged(text: String) = _state.update { it.copy(newFieldKey = text) }
    fun onValueChanged(text: String) = _state.update { it.copy(newValue = text) }

    fun seal() {
        val current = _state.value
        val holding = current.chosen ?: return
        if (current.newFieldKey.isBlank() || current.busy) return
        _state.update { it.copy(busy = true, message = null) }
        scope.launch {
            try {
                vault.seal(householdId, RECORD_TYPE, holding.id, current.newFieldKey.trim(), current.newValue)
                _state.update { it.copy(busy = false, newFieldKey = "", newValue = "") }
                choose(holding)
            } catch (failure: ApiException) {
                _state.update { it.copy(busy = false, message = failure.message) }
            } catch (failure: IllegalArgumentException) {
                _state.update { it.copy(busy = false, message = failure.message) }
            } catch (failure: IllegalStateException) {
                _state.update { it.copy(busy = false, message = failure.message) }
            }
        }
    }

    private companion object {
        const val RECORD_TYPE = "investment"
    }
}
