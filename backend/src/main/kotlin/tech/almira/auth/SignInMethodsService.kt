package tech.almira.auth

import org.springframework.stereotype.Service
import tech.almira.common.ApiException
import tech.almira.common.EmailAddress
import tech.almira.common.PhoneNumber
import java.util.UUID

/** The "How you sign in" card, as data. */
data class SignInMethods(
    val phone: String?,
    val phoneSignIn: Boolean,
    val email: String?,
    val emailSignIn: Boolean,
    val authenticator: Boolean,
    val passkeys: List<PasskeyRow>,
    val passkeysAvailable: Boolean,
    val recoveryCodesLeft: Int,
) {
    /** Each way this person can prove it's them on this server. Recovery codes are a spare key, not a way. */
    val count: Int
        get() = listOf(phoneSignIn, emailSignIn, authenticator).count { it } + passkeys.size

    val meetsMinimum: Boolean get() = count >= MINIMUM

    companion object {
        /** One way in is one lost phone away from no way in (T-05). */
        const val MINIMUM = 2
    }
}

/**
 * What someone has for signing in, and the rule that keeps it at two.
 *
 * A number that is lost, changed or reassigned should cost a person one way in,
 * not the account. So the card asks for at least [SignInMethods.MINIMUM], and a
 * second factor cannot be taken away when that would leave fewer than two —
 * the way to replace an authenticator or a passkey is to add the new one first.
 * An account that has fewer than two already can still remove things: the rule
 * stops a step down, not a person stuck below it.
 *
 * A phone number or an email address counts only when this server lets people
 * sign in with it (SignInChannels): an address nobody can sign in with is not a
 * way in.
 */
@Service
class SignInMethodsService(
    private val users: AuthRepository,
    private val channels: SignInChannels,
    private val factors: SecondFactorService,
    private val passkeys: PasskeyService,
) {

    fun of(userId: UUID): SignInMethods {
        val user = users.findById(userId) ?: throw ApiException.unauthorized()
        val second = factors.factors(userId)
        val email = user.email?.let { EmailAddress.canonicalOrNull(it) }
        return SignInMethods(
            phone = user.phone?.let(PhoneNumber::mask),
            phoneSignIn = user.phone != null && channels.isEnabled(OtpChannel.PHONE),
            email = user.email?.let(EmailAddress::mask),
            emailSignIn = email != null && channels.isEnabled(OtpChannel.EMAIL) && channels.isAllowed(email),
            authenticator = second.authenticator,
            passkeys = if (second.passkeys > 0) passkeys.list(userId) else emptyList(),
            passkeysAvailable = passkeys.available,
            recoveryCodesLeft = second.recoveryCodesLeft,
        )
    }

    /** Refuses removing one way in when it would take the account below two. */
    fun requireKeepsMinimum(userId: UUID) {
        val methods = of(userId)
        if (methods.meetsMinimum && methods.count - 1 < SignInMethods.MINIMUM) {
            throw ApiException.conflict(
                "sign_in_methods_minimum",
                "That would leave only one way to sign in. Add another first" +
                    (if (methods.passkeysAvailable) ": a passkey or an authenticator app" else ": an authenticator app") +
                    ", then remove this one.",
            )
        }
    }
}
