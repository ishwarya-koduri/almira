package tech.bhrigu.almira.provider

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.capture.QuickAddService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.document.DocumentService
import tech.bhrigu.almira.document.DocumentUpload
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.investment.CreateInvestment
import tech.bhrigu.almira.investment.InvestmentService
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ImportedFromProvider(
    val provider: String,
    val imported: Int,
    val skipped: Int,
    val titles: List<String>,
    val note: String,
)

data class WhatsAppCapture(
    val understood: Boolean,
    val reply: String,
    val fields: List<Map<String, Any?>> = emptyList(),
)

/**
 * The wiring between the adapters and the rest of the app (docs/13).
 *
 * Everything here runs against the sandbox implementations today. That is the
 * point of building it now: the part that can be got wrong — consent states,
 * duplicate handling, where an imported record's privacy comes from, what
 * happens to a document once it arrives — is exercised and tested, and the only
 * unproven thing left is the transport.
 *
 * Two rules apply to everything imported from outside:
 *   · it arrives at the household's **default visibility**, never wider;
 *   · it is marked with where it came from, so nobody later mistakes a fixture,
 *     or a bank's guess, for something a person typed.
 */
@Service
class ConnectService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val investments: InvestmentService,
    private val catalog: tech.bhrigu.almira.catalog.CatalogService,
    private val documents: DocumentService,
    private val quickAdd: QuickAddService,
    private val vault: DocumentVaultProvider,
    private val aggregator: AccountAggregatorClient,
    private val whatsApp: WhatsAppGateway,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    private val mapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun status(householdId: UUID): List<ProviderStatus> {
        households.get(householdId)
        val connections = jdbc.query(
            "select provider, status, last_synced_at from provider_connections where household_id = :hid",
            mapOf("hid" to householdId),
        ) { rs, _ ->
            rs.getString("provider") to Pair(
                rs.getString("status"),
                rs.getTimestamp("last_synced_at")?.toInstant(),
            )
        }.toMap()

        return listOf(
            ProviderStatus(
                provider = "digilocker", label = "DigiLocker", mode = vault.mode,
                connected = connections["digilocker"]?.first == "active",
                sandboxNote = "Returns three sample documents — a PAN card, an LIC policy and a " +
                    "driving licence — as real PDFs. They are nobody's records.",
                toGoLive = listOf(
                    "A client id and secret from the DigiLocker partner portal",
                    "A redirect URI on a public HTTPS host, registered with them",
                    "Organisation KYC completed with NeGD",
                    "Set almira.providers.digilocker.mode=live",
                ),
                lastSyncedAt = connections["digilocker"]?.second,
            ),
            ProviderStatus(
                provider = "account_aggregator", label = "Account Aggregator", mode = aggregator.mode,
                connected = connections["account_aggregator"]?.first == "active",
                sandboxNote = "Returns a savings account, a fixed deposit and a fund folio, " +
                    "shaped like real FI data. Consent is still asked for.",
                toGoLive = listOf(
                    "FIU registration with an Account Aggregator (Sahamati onboarding)",
                    "A signed client certificate for the AA's gateway",
                    "A published purpose code and consent template",
                    "Set almira.providers.aa.mode=live",
                ),
                lastSyncedAt = connections["account_aggregator"]?.second,
            ),
            ProviderStatus(
                provider = "whatsapp", label = "WhatsApp capture", mode = whatsApp.mode,
                connected = connections["whatsapp"]?.first == "active",
                sandboxNote = "Accepts a webhook payload shaped like Meta's and does NOT check " +
                    "its signature. Never expose this to the internet in sandbox mode.",
                toGoLive = listOf(
                    "A Meta business account with a verified WhatsApp number",
                    "A permanent access token and the app secret for signature checks",
                    "Message templates approved by Meta",
                    "Set almira.providers.whatsapp.mode=live",
                ),
                lastSyncedAt = connections["whatsapp"]?.second,
            ),
        )
    }

    // --- DigiLocker ------------------------------------------------------------

    @Transactional
    fun startDocumentVault(householdId: UUID): Map<String, String> {
        val userId = userContext.require()
        households.get(householdId)
        val state = UUID.randomUUID().toString()
        upsertConnection(householdId, "digilocker", vault.mode, "pending", state, userId)
        return mapOf("authorizationUrl" to vault.authorizationUrl(householdId, state), "state" to state)
    }

    @Transactional
    fun completeDocumentVault(householdId: UUID, code: String): List<VaultDocument> {
        val userId = userContext.require()
        households.get(householdId)
        val session = vault.exchange(householdId, code)
        upsertConnection(householdId, "digilocker", vault.mode, "active", session.token, userId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "provider.connect",
            entityType = "provider", entityId = null, diff = mapOf("provider" to "digilocker"),
        )
        return vault.list(session)
    }

    @Transactional
    fun importDocuments(householdId: UUID, uris: List<String>): ImportedFromProvider {
        val userId = userContext.require()
        households.get(householdId)
        val session = activeSession(householdId, "digilocker")
        val available = vault.list(session).associateBy { it.uri }

        val titles = mutableListOf<String>()
        var skipped = 0
        uris.forEach { uri ->
            val meta = available[uri]
            if (meta == null) {
                skipped++
                return@forEach
            }
            val bytes = vault.fetch(session, uri)
            documents.upload(
                householdId,
                DocumentUpload(
                    fileName = "${meta.name}.pdf",
                    mimeType = meta.mimeType,
                    bytes = bytes,
                    docType = docTypeFor(meta.docType),
                    expiresOn = null,
                    visibility = null,
                    notes = "From ${meta.issuer} via DigiLocker (${vault.mode.name.lowercase()}).",
                    linkTo = emptyList(),
                ),
            )
            titles += meta.name
        }

        touchSync(householdId, "digilocker")
        audit.record(
            householdId = householdId, actorUserId = userId, action = "provider.import",
            entityType = "provider", entityId = null,
            diff = mapOf("provider" to "digilocker", "count" to titles.size),
        )
        return ImportedFromProvider(
            provider = "digilocker", imported = titles.size, skipped = skipped, titles = titles,
            note = "Stored encrypted in the vault, like anything else uploaded here. " +
                "Nothing was linked to a holding — do that where it belongs.",
        )
    }

    // --- Account Aggregator ----------------------------------------------------

    @Transactional
    fun requestConsent(householdId: UUID): ConsentHandle {
        val userId = userContext.require()
        households.get(householdId)
        val consent = aggregator.requestConsent(
            householdId,
            ConsentRequest(
                purpose = "Personal finance management",
                fiTypes = listOf("DEPOSIT", "TERM_DEPOSIT", "MUTUAL_FUNDS", "EQUITIES"),
                fromDate = LocalDate.now().minusYears(1),
                toDate = LocalDate.now(),
            ),
        )
        upsertConnection(householdId, "account_aggregator", aggregator.mode, "pending", consent.handle, userId)
        return consent
    }

    @Transactional
    fun consentStatus(householdId: UUID): ConsentHandle {
        households.get(householdId)
        val handle = externalRef(householdId, "account_aggregator")
        val status = aggregator.consentStatus(handle)
        if (status.status == "ACTIVE") {
            jdbc.update(
                """
                update provider_connections set status = 'active'
                where household_id = :hid and provider = 'account_aggregator'
                """.trimIndent(),
                mapOf("hid" to householdId),
            )
        }
        return status
    }

    /**
     * What the network reports becomes ordinary records — at the household's own
     * default visibility, never wider, and marked with where they came from. A
     * second run recognises what it already imported by the masked account
     * number, so re-consenting does not double the balance sheet.
     */
    @Transactional
    fun importHoldings(householdId: UUID): ImportedFromProvider {
        val userId = userContext.require()
        households.get(householdId)
        val handle = externalRef(householdId, "account_aggregator")
        val discovered = runCatching { aggregator.fetch(handle) }.getOrElse {
            throw ApiException.badRequest(
                "consent_not_active", "That consent isn't active yet — approve it first.",
            )
        }

        val existing = jdbc.query(
            """
            select attributes ->> 'source_ref' as ref from investments
            where household_id = :hid and deleted_at is null
              -- jsonb_exists, not the ? operator: a bare ? in a named-parameter
              -- statement is read as a placeholder and the query never runs.
              and jsonb_exists(attributes, 'source_ref')
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ -> rs.getString("ref") }.toSet()

        val titles = mutableListOf<String>()
        var skipped = 0

        discovered.forEach { holding ->
            val reference = "${holding.institution}:${holding.maskedAccount}"
            if (reference in existing) {
                skipped++
                return@forEach
            }
            val typeCode = typeFor(holding.fiType)
            val type = catalog.taxonomy(householdId)
                .flatMap { it.types }
                .firstOrNull { it.code == typeCode }
                ?.id
                ?: run { skipped++; return@forEach }

            // The provider's own fields become record-level custom fields rather
            // than being forced into the type's schema, which would reject them —
            // correctly. This is the "record anything" path doing exactly what it
            // is for: an IFSC and a folio number are real information, and they
            // arrive as first-class fields somebody can see and edit.
            val detail = holding.detail.mapKeys { (key, _) -> snakeCase(key) }
            val custom = (detail.keys + "source_ref").map { key ->
                tech.bhrigu.almira.investment.CustomFieldInput(
                    key = key,
                    label = labelFor(key),
                    dataType = "text",
                )
            }

            investments.create(
                householdId,
                CreateInvestment(
                    typeId = type,
                    title = holding.displayName,
                    investedAmount = holding.currentValue,
                    currency = holding.currency,
                    customFields = custom,
                    attributes = buildMap {
                        put("source_ref", reference)
                        detail.forEach { (key, value) -> put(key, value) }
                    },
                    notes = "Imported from ${holding.institution} via the Account Aggregator " +
                        "network on ${holding.asOf}.",
                    // Everything imported starts at the household's default and is
                    // widened deliberately or not at all.
                    visibility = null,
                    allowMissingRequired = true,
                    initialValuation = holding.currentValue?.let {
                        tech.bhrigu.almira.investment.ValuationInput(value = it, asOfDate = holding.asOf)
                    },
                ),
            )
            titles += holding.displayName
        }

        touchSync(householdId, "account_aggregator")
        audit.record(
            householdId = householdId, actorUserId = userId, action = "provider.import",
            entityType = "provider", entityId = null,
            diff = mapOf("provider" to "account_aggregator", "count" to titles.size),
        )
        return ImportedFromProvider(
            provider = "account_aggregator", imported = titles.size, skipped = skipped,
            titles = titles,
            note = "Added at your household's default visibility, with a valuation dated by the " +
                "provider. Anything already here was left alone.",
        )
    }

    /**
     * A provider's vocabulary is not ours. DigiLocker says "pan" and
     * "driving_licence"; the vault knows about certificates, policies and KYC —
     * so the mapping happens here, at the boundary, rather than by widening our
     * own list every time somebody else invents a word.
     */
    private fun docTypeFor(providerType: String) = when (providerType) {
        "insurance" -> "policy"
        "pan", "aadhaar", "driving_licence", "passport", "voter_id" -> "kyc"
        "property", "deed", "sale_deed" -> "deed"
        "statement" -> "statement"
        else -> "certificate"
    }

    /** camelCase from a provider becomes the snake_case field names we use. */
    private fun snakeCase(key: String) = key
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase()
        .replace(Regex("[^a-z0-9_]"), "_")

    private fun labelFor(key: String) = when (key) {
        "source_ref" -> "Where this came from"
        "ifsc" -> "IFSC"
        "nav" -> "NAV at import"
        "units" -> "Units"
        "rate" -> "Interest rate"
        "maturity_date" -> "Matures on"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun typeFor(fiType: String) = when (fiType) {
        "DEPOSIT" -> "savings_buffer"
        "TERM_DEPOSIT" -> "fd"
        "RECURRING_DEPOSIT" -> "rd"
        "MUTUAL_FUNDS" -> "mf_lumpsum"
        "EQUITIES" -> "stock_listed"
        "NPS" -> "nps"
        "INSURANCE_POLICIES" -> "insurance_term"
        else -> "universal"
    }

    // --- WhatsApp --------------------------------------------------------------

    /**
     * An inbound message becomes a *proposal*, exactly like typing into quick
     * add — never a saved record. Somebody texting their bank balance to a
     * number should not be able to write to a registry without looking at what
     * was understood.
     */
    fun captureFromWhatsApp(
        householdId: UUID,
        signature: String?,
        body: ByteArray,
    ): WhatsAppCapture {
        if (!whatsApp.verify(signature, body)) {
            throw ApiException.forbidden("That message didn't come from where it claims to.")
        }
        val message = whatsApp.parse(body)
            ?: return WhatsAppCapture(false, "We couldn't read that message.")

        val parsed = quickAdd.parse(householdId, message.text)
        // A title is what is left when nothing was recognised, so a parse that
        // produced only a title understood nothing — replying "Got it: Name
        // hello?" would be worse than admitting it.
        val recognised = parsed.fields.filter { it.key != "title" }
        val reply = if (recognised.isEmpty()) {
            "We couldn't make anything of that. Try something like “1L gold at ICICI”."
        } else {
            "Got it: " + parsed.fields.joinToString(", ") { "${it.label} ${it.display}" } +
                ". Open Almira to confirm and save."
        }
        whatsApp.reply(message.from, reply)

        return WhatsAppCapture(
            understood = recognised.isNotEmpty(),
            reply = reply,
            fields = parsed.fields.map {
                mapOf("key" to it.key, "label" to it.label, "display" to it.display, "value" to it.value)
            },
        )
    }

    // --- plumbing --------------------------------------------------------------

    private fun upsertConnection(
        householdId: UUID,
        provider: String,
        mode: ProviderMode,
        status: String,
        externalRef: String?,
        userId: UUID,
    ) {
        jdbc.update(
            """
            insert into provider_connections (household_id, provider, mode, status, external_ref,
                                              created_by)
            values (:hid, :provider, :mode, :status, :ref, :by)
            on conflict (household_id, provider) do update set
              mode = excluded.mode, status = excluded.status, external_ref = excluded.external_ref
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("provider", provider)
                .addValue("mode", mode.name.lowercase()).addValue("status", status)
                .addValue("ref", externalRef).addValue("by", userId),
        )
    }

    private fun externalRef(householdId: UUID, provider: String): String = jdbc.query(
        """
        select external_ref from provider_connections
        where household_id = :hid and provider = :provider
        """.trimIndent(),
        mapOf("hid" to householdId, "provider" to provider),
    ) { rs, _ -> rs.getString("external_ref") }.firstOrNull()
        ?: throw ApiException.badRequest(
            "not_connected", "Connect that first — there's nothing to fetch yet.",
        )

    private fun activeSession(householdId: UUID, provider: String) = ProviderSession(
        token = externalRef(householdId, provider),
        expiresAt = Instant.now().plusSeconds(3600),
        scope = "files.issueddocs",
    )

    private fun touchSync(householdId: UUID, provider: String) {
        jdbc.update(
            """
            update provider_connections set last_synced_at = now()
            where household_id = :hid and provider = :provider
            """.trimIndent(),
            mapOf("hid" to householdId, "provider" to provider),
        )
    }
}
