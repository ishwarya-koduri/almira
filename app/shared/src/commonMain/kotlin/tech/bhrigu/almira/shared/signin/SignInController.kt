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
import tech.bhrigu.almira.shared.api.OtpDeliveryStatus

/**
 * Which of the two steps is on screen. Nothing else is ever on it.
 *
 * `Phone` is the first step whichever channel it asks for — a number or an
 * address. The name predates email sign-in and is kept so nothing that
 * switches on it has to change.
 */
enum class SignInStep { Phone, Code }

/** The ways in a server can offer. */
enum class SignInChannel(val wire: String) {
    Phone("phone"),
    Email("email");

    companion object {
        /**
         * The server's list, in the server's order, ignoring anything this
         * build does not know. Empty or unknown means phone, which is what
         * every server did before it could say.
         */
        fun fromServer(names: List<String>): List<SignInChannel> =
            names.mapNotNull { name -> entries.firstOrNull { it.wire == name } }.distinct()
                .ifEmpty { listOf(Phone) }
    }
}

data class SignInState(
    val step: SignInStep = SignInStep.Phone,
    /** The channel the first step is asking for. */
    val channel: SignInChannel = SignInChannel.Phone,
    /** Every channel the server offers; more than one shows a switch. */
    val channels: List<SignInChannel> = listOf(SignInChannel.Phone),
    val phone: String = "",
    val email: String = "",
    val code: String = "",
    val busy: Boolean = false,
    /** Written by the server, shown as written. */
    val error: String? = null,
    val challenge: OtpChallenge? = null,
    /** Seconds until another code may be asked for; 0 means now. */
    val resendIn: Int = 0,
    /** An emailed code whose send has not settled yet. */
    val emailSending: Boolean = false,
    /** What the server said about an emailed code that ran late or could not be sent. */
    val delivery: EmailDelivery? = null,
    val signedIn: Me? = null,
) {
    /** Ten digits, which is every Indian mobile number and no accidents. */
    val phoneIsPlausible: Boolean get() = phone.length == 10 && phone.first() in '6'..'9'

    /**
     * Loose on purpose, as the server is: one `@`, something either side, a
     * dot after it. The server normalises and has the final word.
     */
    val emailIsPlausible: Boolean get() = EMAIL.matches(email.trim())

    val addressIsPlausible: Boolean get() = when (channel) {
        SignInChannel.Phone -> phoneIsPlausible
        SignInChannel.Email -> emailIsPlausible
    }

    /** What the code step says the code was sent to. */
    val sentTo: String get() = when (channel) {
        SignInChannel.Phone -> "+91 $phone"
        SignInChannel.Email -> email.trim()
    }

    /** Only the other channel, and only when the server offers it. */
    val otherChannel: SignInChannel? get() = channels.firstOrNull { it != channel }

    val codeIsComplete: Boolean get() = code.length == CODE_LENGTH

    companion object {
        const val CODE_LENGTH = 6
        const val PHONE_LENGTH = 10
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s.]+$")
    }
}

/**
 * What the code step says about an emailed code.
 *
 * Email sign-in answers before the email is sent, so that an address off the
 * alpha allowlist cannot be told from one on it; the code step then asks the
 * server how the send went. A failure is said — "We couldn't send the code" —
 * because silence looks exactly like a code that never arrived.
 */
sealed interface EmailDelivery {
    data object Pending : EmailDelivery
    data object Sent : EmailDelivery
    data class Delayed(val message: String) : EmailDelivery
    data class Failed(val message: String) : EmailDelivery
    /** An unreadable status (an older server, no connection): stop asking, unconfirmed. */
    data object Unknown : EmailDelivery

    companion object {
        const val NOT_SENT = "We couldn't send the code."
        const val DELAYED = "Your code is taking longer than usual to send. If it arrives, it will work. " +
            "If it doesn't, you can ask for a new one now."

        fun of(status: OtpDeliveryStatus?): EmailDelivery = when (status?.status) {
            "sending" -> Pending
            "sent" -> Sent
            "delayed" -> Delayed(status.message?.takeIf { it.isNotBlank() } ?: DELAYED)
            // The server's sentence already starts with the headline; one this
            // build cannot read still says the code did not go.
            "failed" -> Failed(
                status.message?.takeIf { it.startsWith(NOT_SENT) } ?: NOT_SENT,
            )
            else -> Unknown
        }

        /**
         * What the code step shows once it stops asking. Only a status that said
         * "sent" may leave it saying a code went; asking that never settled, or a
         * status that could not be read, is unconfirmed and shown as a late email
         * with resend open, never as silence.
         */
        fun whenAskingStops(last: EmailDelivery?): EmailDelivery = when (last) {
            Sent, is Delayed, is Failed -> last
            Pending, Unknown, null -> Delayed(DELAYED)
        }
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
    /**
     * Optional on purpose. Everything here works without it — it only saves
     * six keystrokes — so a platform that cannot listen for the message, or a
     * test that does not want to, passes nothing.
     */
    private val autofill: OtpAutofill? = null,
) {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private var countdown: Job? = null
    private var listening: Job? = null
    private var watching: Job? = null

    fun onPhoneChanged(input: String) {
        val digits = input.filter(Char::isDigit).take(SignInState.PHONE_LENGTH)
        _state.update { it.copy(phone = digits, error = null) }
    }

    /** Whitespace inside an address is never right; the ends are trimmed on send. */
    fun onEmailChanged(input: String) {
        _state.update { it.copy(email = input.filterNot { c -> c == ' ' || c == '\n' }.take(254), error = null) }
    }

    /**
     * Asks the server which channels it offers and starts on the first. Called
     * once when the sign-in screen appears; until it answers, the phone step
     * shows, as it always did.
     */
    suspend fun loadChannels() {
        val channels = SignInChannel.fromServer(api.signInChannels())
        _state.update {
            // Someone already typing keeps their place, if that channel is still offered.
            val keep = it.channel in channels && (it.phone.isNotEmpty() || it.email.isNotEmpty())
            it.copy(channels = channels, channel = if (keep) it.channel else channels.first())
        }
    }

    /** The switch under the first step. Ignored for a channel the server does not offer. */
    fun useChannel(channel: SignInChannel) {
        if (channel !in state.value.channels || state.value.busy) return
        _state.update { it.copy(channel = channel, error = null) }
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
        if (!state.value.addressIsPlausible || state.value.busy) return
        run("Couldn't send the code.") {
            val challenge = requestCode(state.value)
            _state.update {
                it.copy(step = SignInStep.Code, challenge = challenge, code = "", error = null)
            }
            startCountdown(challenge.resendAfterSeconds)
            listenForCode()
            watchDelivery(challenge.requestId)
        }
    }

    /**
     * For an emailed code: ask the server how the send went, once a second,
     * until it has settled. Delayed or failed opens resend at once — the server
     * lifted the cooldown — and says so.
     */
    private fun watchDelivery(requestId: String) {
        watching?.cancel()
        if (state.value.channel != SignInChannel.Email) {
            _state.update { it.copy(emailSending = false, delivery = null) }
            return
        }
        _state.update { it.copy(emailSending = true, delivery = null) }
        watching = scope.launch {
            var last: EmailDelivery? = null
            for (attempt in 0 until WATCH_ATTEMPTS) {
                delay(1000)
                last = EmailDelivery.of(api.emailDelivery(requestId))
                if (last != EmailDelivery.Pending) break
            }
            when (val outcome = EmailDelivery.whenAskingStops(last)) {
                is EmailDelivery.Delayed, is EmailDelivery.Failed -> {
                    countdown?.cancel()
                    _state.update { it.copy(emailSending = false, delivery = outcome, resendIn = 0) }
                }
                else -> _state.update { it.copy(emailSending = false) }
            }
        }
    }

    /**
     * Wait for the message, and fill the field with it.
     *
     * Routed through [onCodeChanged] rather than written into the state
     * directly, so an autofilled code goes down exactly the same path as a
     * typed one — same validation, same submit-on-the-sixth-digit. A second
     * code path here is how the two quietly grow apart.
     */
    private fun listenForCode() {
        // The SMS Retriever reads text messages. An emailed code arrives in a
        // mail app, where the keyboard's own suggestion is what helps.
        if (state.value.channel != SignInChannel.Phone) return
        val autofill = autofill ?: return
        listening?.cancel()
        listening = scope.launch {
            val code = autofill.awaitCode(SignInState.CODE_LENGTH) ?: return@launch
            // Landing on the phone step again means they went back; filling a
            // field they are no longer looking at would be a jump scare.
            if (state.value.step == SignInStep.Code && state.value.code.isEmpty()) {
                onCodeChanged(code)
            }
        }
    }

    /** The eleven characters the SMS has to end with, where that applies. */
    fun smsSignature(): String? = autofill?.smsSignature()

    fun resend() {
        if (state.value.resendIn > 0 || state.value.busy) return
        run("Couldn't send a new code.") {
            val challenge = requestCode(state.value)
            _state.update { it.copy(challenge = challenge, code = "", error = null) }
            startCountdown(challenge.resendAfterSeconds)
            // A new code means a new message, and the old listener is watching
            // for one that will never come.
            listenForCode()
            watchDelivery(challenge.requestId)
        }
    }

    fun verify() {
        val current = state.value
        if (!current.codeIsComplete || current.busy) return
        scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val requestId = current.challenge?.requestId
                val login = when (current.channel) {
                    SignInChannel.Phone -> api.verifyOtp(current.phone, current.code, requestId)
                    SignInChannel.Email -> api.verifyEmailOtp(current.email.trim(), current.code, requestId)
                }
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

    /** Back to the first step — a wrong number or address should not need a restart. */
    fun editPhone() {
        listening?.cancel()
        countdown?.cancel()
        watching?.cancel()
        _state.update {
            it.copy(
                step = SignInStep.Phone, code = "", error = null, challenge = null, resendIn = 0,
                emailSending = false, delivery = null,
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun requestCode(state: SignInState): OtpChallenge = when (state.channel) {
        SignInChannel.Phone -> api.requestOtp(state.phone)
        SignInChannel.Email -> api.requestEmailOtp(state.email.trim())
    }

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

    private companion object {
        /** Past the longest a send can take (15 s) and its settling tick. */
        const val WATCH_ATTEMPTS = 30
    }
}
