package tech.bhrigu.almira.shared.signin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.api.ApiException
import tech.bhrigu.almira.shared.api.Me
import tech.bhrigu.almira.shared.api.OtpChallenge

/** Which of the two steps is on screen. Nothing else is ever on it. */
enum class SignInStep { Phone, Code }

data class SignInState(
    val step: SignInStep = SignInStep.Phone,
    val phone: String = "",
    val code: String = "",
    val busy: Boolean = false,
    /** Written by the server, shown as written. */
    val error: String? = null,
    val challenge: OtpChallenge? = null,
    /** Seconds until another code may be asked for; 0 means now. */
    val resendIn: Int = 0,
    val signedIn: Me? = null,
) {
    /** Ten digits, which is every Indian mobile number and no accidents. */
    val phoneIsPlausible: Boolean get() = phone.length == 10 && phone.first() in '6'..'9'
    val codeIsComplete: Boolean get() = code.length == CODE_LENGTH

    companion object {
        const val CODE_LENGTH = 6
        const val PHONE_LENGTH = 10
    }
}

/**
 * The sign-in flow, with no Compose in it.
 *
 * Deliberately a plain class rather than an androidx ViewModel: this is the
 * shared module, and the iOS target has no such thing. It takes the scope it
 * runs in, so the screen owns its lifetime and a rotation does not leave a
 * request talking to a dead composition.
 */
class SignInController(
    private val api: AlmiraApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private var countdown: Job? = null

    fun onPhoneChanged(input: String) {
        val digits = input.filter(Char::isDigit).take(SignInState.PHONE_LENGTH)
        _state.update { it.copy(phone = digits, error = null) }
    }

    fun onCodeChanged(input: String) {
        val digits = input.filter(Char::isDigit).take(SignInState.CODE_LENGTH)
        _state.update { it.copy(code = digits, error = null) }
        // Six digits is the whole input, so submitting on the sixth keystroke
        // saves a tap without ever guessing at an incomplete code.
        if (digits.length == SignInState.CODE_LENGTH) verify()
    }

    /**
     * A session that was already on this device, brought back without asking
     * for a phone number.
     *
     * `me()` is the honest test: it is a real authenticated call, so it proves
     * the stored token is not merely present but accepted — including the case
     * where the refresh had to rotate to answer it. A failure here is not an
     * error to show, it is a session that has ended, so the caller falls back
     * to the phone step.
     */
    suspend fun resume(): Boolean = try {
        val me = api.me()
        _state.update { it.copy(signedIn = me, busy = false, error = null) }
        true
    } catch (_: ApiException) {
        false
    }

    fun sendCode() {
        val phone = state.value.phone
        if (!state.value.phoneIsPlausible || state.value.busy) return
        run("Couldn't send the code.") {
            val challenge = api.requestOtp(phone)
            _state.update {
                it.copy(step = SignInStep.Code, challenge = challenge, code = "", error = null)
            }
            startCountdown(challenge.resendAfterSeconds)
        }
    }

    fun resend() {
        if (state.value.resendIn > 0 || state.value.busy) return
        run("Couldn't send a new code.") {
            val challenge = api.requestOtp(state.value.phone)
            _state.update { it.copy(challenge = challenge, code = "", error = null) }
            startCountdown(challenge.resendAfterSeconds)
        }
    }

    fun verify() {
        val current = state.value
        if (!current.codeIsComplete || current.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val login = api.verifyOtp(current.phone, current.code, current.challenge?.requestId)
                countdown?.cancel()
                _state.update { it.copy(signedIn = login.user) }
            } catch (failure: ApiException) {
                // Clear the code as well as showing why. Leaving six wrong
                // digits in place means six backspaces before the next attempt,
                // and the next attempt is the only thing this person wants.
                // The caution borders stay until the first new keystroke, which
                // is what makes the failure legible without being in the way.
                _state.update {
                    it.copy(code = "", error = failure.message.ifBlank { "That didn't work." })
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Back to the phone step — a wrong number should not need a restart. */
    fun editPhone() {
        countdown?.cancel()
        _state.update { it.copy(step = SignInStep.Phone, code = "", error = null, challenge = null, resendIn = 0) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun startCountdown(seconds: Int) {
        countdown?.cancel()
        countdown = scope.launch {
            var remaining = seconds
            while (isActive && remaining > 0) {
                _state.update { it.copy(resendIn = remaining) }
                delay(1000)
                remaining -= 1
            }
            _state.update { it.copy(resendIn = 0) }
        }
    }

    /**
     * One place where a request becomes a busy flag and, if it fails, a
     * sentence. The server's own message is used verbatim — it is already
     * written for a person, and rewriting it here would mean keeping two sets
     * of words in step.
     */
    private fun run(fallback: String, block: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (failure: ApiException) {
                _state.update { it.copy(error = failure.message.ifBlank { fallback }) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
