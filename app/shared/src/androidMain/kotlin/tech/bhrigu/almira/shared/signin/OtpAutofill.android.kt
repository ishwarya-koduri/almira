package tech.bhrigu.almira.shared.signin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.suspendCancellableCoroutine
import tech.bhrigu.almira.shared.security.PlatformHost
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.coroutines.resume

actual fun createOtpAutofill(host: PlatformHost): OtpAutofill =
    SmsRetrieverAutofill(host.activity.applicationContext)

/**
 * SMS Retriever: one message, handed over, with no permission asked for.
 *
 * This is the whole reason not to use the other approach. Reading the code out
 * of the inbox needs `READ_SMS`, which is access to every message a person has
 * ever received — for a registry of family wealth to ask for that would be
 * grotesque, and Play would want justifying. SMS Retriever gives the app
 * exactly one message: the one that ends with a hash of this build's signing
 * certificate, within five minutes of the app asking. Nothing else is readable,
 * and there is no prompt, because there is nothing to consent to.
 *
 * The cost is that the sender has to cooperate: the SMS body must carry
 * [smsSignature] on the end, or Play Services never hands it over and the
 * message simply arrives like any other. That is a server-side template
 * concern, which is why the signature is something this class will tell you
 * rather than something buried in a build file.
 */
internal class SmsRetrieverAutofill(private val context: Context) : OtpAutofill {

    override suspend fun awaitCode(length: Int): String? = suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras: Bundle = intent.extras ?: return
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return

                val code = when (status.statusCode) {
                    CommonStatusCodes.SUCCESS ->
                        extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE)?.let { digitsOf(it, length) }
                    // TIMEOUT is the five-minute window closing with nothing
                    // matched. Ordinary, and not worth telling anyone about.
                    else -> null
                }
                if (continuation.isActive) continuation.resume(code)
            }
        }

        // Registered with the sender's permission so only Play Services can
        // deliver this, and exported because Play Services is another app —
        // on API 33 and up that has to be said out loud.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )

        continuation.invokeOnCancellation {
            runCatching { context.unregisterReceiver(receiver) }
        }

        SmsRetriever.getClient(context).startSmsRetriever()
            .addOnFailureListener {
                // No Play Services, or it declined. The person types the code,
                // which is exactly what they were going to do anyway.
                runCatching { context.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(null)
            }
    }

    /**
     * The first run of exactly [length] digits.
     *
     * Deliberately not "the first digits anywhere": a message like "Your Almira
     * code is 483920. Valid 5 minutes." has a 5 in it, and a looser rule would
     * happily fill the field with something that is not the code.
     */
    private fun digitsOf(message: String, length: Int): String? =
        Regex("(?<!\\d)\\d{$length}(?!\\d)").find(message)?.value

    /**
     * The eleven characters computed the way Play Services computes them:
     * base64 of the first nine bytes of SHA-256 over "package certificate",
     * where the certificate is itself base64 of the signing cert's bytes.
     *
     * Read off the installed package rather than off a keystore, so it is right
     * for whatever actually signed this build — including a Play-resigned one,
     * where a hash derived locally would be wrong and nobody would notice until
     * autofill quietly stopped working in production.
     */
    override fun smsSignature(): String? = runCatching {
        val packageName = context.packageName
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures
        }
        val signature = signatures?.firstOrNull() ?: return null

        // `toCharsString` — the certificate as hex — and not base64 of the same
        // bytes. Play Services hashes the hex form, so the base64 form produces
        // a perfectly plausible eleven characters that simply never match: the
        // message arrives, and autofill silently does not happen. Cost an hour
        // and one delivered SMS to find.
        val certificate = signature.toCharsString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName $certificate".toByteArray(StandardCharsets.UTF_8))
        Base64.encodeToString(digest.copyOf(NINE_BYTES), Base64.NO_PADDING or Base64.NO_WRAP)
            .substring(0, SIGNATURE_LENGTH)
    }.getOrNull()

    private companion object {
        /** Nine bytes is what base64s to exactly twelve characters, of which eleven are kept. */
        const val NINE_BYTES = 9
        const val SIGNATURE_LENGTH = 11
    }
}
