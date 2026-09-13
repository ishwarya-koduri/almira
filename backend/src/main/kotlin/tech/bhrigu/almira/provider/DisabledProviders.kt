package tech.bhrigu.almira.provider

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The provider is not offered on this server — and that is a state, not a fault.
 *
 * Before these existed, a mode that removed the sandbox bean left nothing in its
 * place, and `ConnectService` — which needs one of each — failed the whole
 * application at startup on a `NoSuchBeanDefinitionException` (known-issues 11).
 * So "switch Account Aggregator off" was not something a deployment could do.
 *
 * A disabled adapter reports [ProviderMode.DISABLED] and is never asked to do
 * anything: `ConnectService` checks the mode first and answers 409
 * `provider_disabled`. The methods below throw [ProviderDisabled] anyway, so a
 * caller that forgets the check fails with a named refusal rather than acting
 * on a provider that is not there.
 *
 * Only the three connect providers have one. The notification channels (`sms`,
 * `email`, `push`) are injected as a list, so a disabled channel is simply not in
 * it: `RecordingNotifier` skips it and writes no row for it. Email sign-in with
 * email disabled does not start at all (`SignInChannels`): offering a sign-in
 * channel through an absent provider contradicts the setting that made it absent.
 */
class ProviderDisabled(val provider: String) :
    IllegalStateException("provider '$provider' is disabled on this server")

@Component
@ConditionalOnProperty(name = ["almira.providers.digilocker.mode"], havingValue = "disabled")
class DisabledDocumentVault : DocumentVaultProvider {
    override val mode = ProviderMode.DISABLED
    override fun authorizationUrl(householdId: UUID, state: String): String = throw ProviderDisabled(NAME)
    override fun exchange(householdId: UUID, code: String): ProviderSession = throw ProviderDisabled(NAME)
    override fun list(session: ProviderSession): List<VaultDocument> = throw ProviderDisabled(NAME)
    override fun fetch(session: ProviderSession, uri: String): ByteArray = throw ProviderDisabled(NAME)

    private companion object { const val NAME = "digilocker" }
}

/**
 * Account Aggregator is cut from v1 (owner's decision, 2026-09): production
 * access needs the FIU to be regulated by RBI, SEBI, IRDAI or PFRDA, which a
 * family asset registry is not. So this is the default — `matchIfMissing` — and
 * the sandbox has to be asked for. The interface, the sandbox and its tests stay,
 * for a future regulated partner (docs/providers/account-aggregator.md).
 */
@Component
@ConditionalOnProperty(name = ["almira.providers.aa.mode"], havingValue = "disabled", matchIfMissing = true)
class DisabledAccountAggregator : AccountAggregatorClient {
    override val mode = ProviderMode.DISABLED
    override fun requestConsent(householdId: UUID, request: ConsentRequest): ConsentHandle =
        throw ProviderDisabled(NAME)
    override fun consentStatus(handle: String): ConsentHandle = throw ProviderDisabled(NAME)
    override fun fetch(handle: String): List<DiscoveredHolding> = throw ProviderDisabled(NAME)

    private companion object { const val NAME = "aa" }
}

@Component
@ConditionalOnProperty(name = ["almira.providers.whatsapp.mode"], havingValue = "disabled")
class DisabledWhatsAppGateway : WhatsAppGateway {
    override val mode = ProviderMode.DISABLED

    /** Refuses every payload: nothing is listening, so nothing is genuine. */
    override fun verify(signature: String?, body: ByteArray) = false
    override fun parse(body: ByteArray): InboundMessage? = throw ProviderDisabled(NAME)
    override fun reply(to: String, text: String) = throw ProviderDisabled(NAME)

    private companion object { const val NAME = "whatsapp" }
}
