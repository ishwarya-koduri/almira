package tech.almira.provider

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.almira.audit.AuditService
import tech.almira.capture.QuickAddService
import tech.almira.common.ApiException
import tech.almira.document.DocumentService
import tech.almira.document.DocumentUpload
import tech.almira.household.HouseholdService
import tech.almira.investment.CreateInvestment
import tech.almira.investment.InvestmentService
import tech.almira.security.RequestUserContext
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
    /**
     * Null when the reply went out; otherwise the same code a connect call would
     * have failed with (`provider_timeout`, `provider_unavailable`,
     * `provider_rejected`, `provider_account_unavailable`). The capture itself
     * still succeeded — see [ConnectService.captureFromWhatsApp].
     */
    val replyFailure: String? = null,
    /**
     * True when this message id was already received: nothing was parsed and no
     * reply was sent again, and [reply] is empty. Answered 200 so the provider
     * stops redelivering (known-issues 21).
     */
    val duplicate: Boolean = false,
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
 *
 * **No provider call inside a transaction** (known-issues 21). A person is
 * waiting for each of these, so the calls stay in the request — but never while
 * a database transaction, and so an app-pool connection, is held open around
 * them. Each method reads what it needs in one short transaction, calls the
 * provider with none open, and writes what came back in another. Every call is
 * also bounded as a whole by `almira.providers.connect-budget`
 * ([ProviderCalls.interactive]), not only per attempt.
 */
@Service
class ConnectService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val investments: InvestmentService,
    private val catalog: tech.almira.catalog.CatalogService,
    private val documents: DocumentService,
    private val quickAdd: QuickAddService,
    private val vault: DocumentVaultProvider,
    private val aggregator: AccountAggregatorClient,
    private val whatsApp: WhatsAppGateway,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    private val mapper: ObjectMapper,
    private val calls: ProviderCalls,
    private val props: tech.almira.config.AlmiraProperties,
    private val cipher: tech.almira.crypto.EnvelopeCipher,
    private val deliveries: WhatsAppDeliveries,
    transactionManager: org.springframework.transaction.PlatformTransactionManager,
) {

    private val transactions = org.springframework.transaction.support.TransactionTemplate(transactionManager)

    /** Runs [block] in its own short transaction, with the request's identity, and returns what it returned. */
    private fun <T : Any> inTransaction(block: () -> T): T = checkNotNull(transactions.execute { block() })

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
                connected = vault.mode != ProviderMode.DISABLED &&
                    connections["digilocker"]?.first == "active",
                sandboxNote = sandboxOnly(
                    vault.mode,
                    "Returns three sample documents (a PAN card, an LIC policy and a " +
                        "driving licence) as real PDFs. They are nobody's records.",
                ),
                // Kept in step with docs/providers/digilocker.md, which has the reasons.
                toGoLive = listOf(
                    "GST registration, so the organisation can be GSTN-verified on API Setu",
                    "A client id and secret from the DigiLocker partner portal",
                    "A server located in India",
                    "A redirect URI on a public HTTPS host, registered with them",
                    "Organisation KYC completed with NeGD",
                    "A live adapter, written and tested first: DigiLocker has no separate sandbox",
                    "Set almira.providers.digilocker.mode=live",
                ),
                lastSyncedAt = connections["digilocker"]?.second,
            ),
            ProviderStatus(
                provider = "account_aggregator", label = "Account Aggregator", mode = aggregator.mode,
                connected = aggregator.mode != ProviderMode.DISABLED &&
                    connections["account_aggregator"]?.first == "active",
                sandboxNote = sandboxOnly(
                    aggregator.mode,
                    "Returns a savings account, a fixed deposit and a fund folio, " +
                        "shaped like real FI data. Consent is still asked for.",
                ),
                // Kept in step with docs/providers/account-aggregator.md.
                toGoLive = listOf(
                    "An entity regulated by RBI, SEBI, IRDAI or PFRDA. Without one, production " +
                        "access is not available, which is why it is cut from v1",
                    "A Company PAN and GSTIN, even for the sandbox",
                    "FIU registration with an Account Aggregator (Sahamati onboarding)",
                    "A signed client certificate for the AA's gateway",
                    "A published purpose code and consent template",
                    "Set almira.providers.aa.mode=live",
                ),
                lastSyncedAt = connections["account_aggregator"]?.second,
            ),
            ProviderStatus(
                provider = "whatsapp", label = "WhatsApp capture", mode = whatsApp.mode,
                connected = whatsApp.mode != ProviderMode.DISABLED &&
                    connections["whatsapp"]?.first == "active",
                sandboxNote = sandboxOnly(
                    whatsApp.mode,
                    "Accepts a webhook payload shaped like Meta's and does NOT check " +
                        "its signature. Never expose this to the internet in sandbox mode.",
                ),
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

    /**
     * Begins a connection, and remembers the OAuth `state` it hands out so that
     * [completeDocumentVault] can insist on it (known-issues 10).
     *
     * Only a hash is kept, with who started and until when. The state is not a
     * secret the way a token is, but a column any member can read is no place
     * for the one value that proves a sign-in began here.
     */
    @Transactional
    fun startDocumentVault(householdId: UUID): Map<String, String> {
        val userId = userContext.require()
        households.get(householdId)
        requireEnabled(DIGILOCKER, vault.mode)
        val state = newState()
        jdbc.update(
            """
            insert into provider_connections (household_id, provider, mode, status, detail, created_by)
            values (:hid, 'digilocker', :mode, 'pending', cast(:detail as jsonb), :by)
            on conflict (household_id, provider) do update set
              mode = excluded.mode, status = 'pending',
              detail = provider_connections.detail || excluded.detail
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("mode", vault.mode.name.lowercase())
                .addValue("by", userId)
                .addValue(
                    "detail",
                    mapper.writeValueAsString(
                        mapOf(
                            STATE_HASH to sha256(state),
                            STATE_EXPIRES to Instant.now().plus(STATE_LIFETIME).toString(),
                            STATE_STARTED_BY to userId.toString(),
                        ),
                    ),
                ),
        )
        return mapOf("authorizationUrl" to vault.authorizationUrl(householdId, state), "state" to state)
    }

    /**
     * Redeems the authorisation code, keeps the connection, then lists.
     *
     * Deliberately not one transaction. The code is spent the moment [exchange]
     * succeeds, so the connection it bought is committed before the list call:
     * a list that then times out or finds DigiLocker unavailable used to roll
     * the connection back with it, leaving a person with no connection and a
     * code that could never be redeemed again — no way to finish at all.
     *
     * A failed list after a good exchange answers with the list's own
     * `provider_*` code and `details.connected = true`; the documents are then
     * one [listDocuments] away, with no new code.
     */
    fun completeDocumentVault(householdId: UUID, code: String, state: String?): List<VaultDocument> {
        val userId = userContext.require()
        transactions.execute { households.get(householdId) }
        requireEnabled(DIGILOCKER, vault.mode)
        // Before the code is spent: a code that arrives with somebody else's
        // state, or none, is somebody else's DigiLocker (login CSRF), and
        // redeeming it would import their documents into this household. And
        // before it is redeemed, not at the connection's insert: the code is
        // spent at DigiLocker the moment exchange succeeds, and a member who
        // may not connect a provider used to spend it and only then be refused.
        inTransaction {
            households.requireAdministrator(householdId)
            requireState(householdId, userId, state)
        }
        // Not idempotent: an authorisation code redeems once, so a retry after a
        // timeout would come back "rejected" and blame the person for our wait.
        val session = provider(DIGILOCKER, "exchange", idempotent = false) { vault.exchange(householdId, code) }
        transactions.execute {
            storeSession(householdId, session)
            audit.record(
                householdId = householdId, actorUserId = userId, action = "provider.connect",
                entityType = "provider", entityId = null, diff = mapOf("provider" to "digilocker"),
            )
        }
        return try {
            provider(DIGILOCKER, "list") { vault.list(session) }
        } catch (e: ApiException) {
            throw ApiException(
                e.status, e.code,
                "You're connected to DigiLocker, but it couldn't list your documents just now. " +
                    "Please try again in a minute. You won't need to sign in to DigiLocker again.",
                e.details + mapOf("connected" to true),
                e,
            )
        }
    }

    /** What an active DigiLocker connection offers, without redeeming anything. */
    fun listDocuments(householdId: UUID): List<VaultDocument> {
        val session = inTransaction {
            households.get(householdId)
            requireEnabled(DIGILOCKER, vault.mode)
            activeSession(householdId, requireActive = true)
        }
        return provider(DIGILOCKER, "list") { vault.list(session) }
    }

    /**
     * Lists and fetches with no transaction open, then stores everything that
     * arrived in one: a fetch that fails part-way stores nothing, as before.
     */
    fun importDocuments(householdId: UUID, uris: List<String>): ImportedFromProvider {
        val userId = userContext.require()
        val session = inTransaction {
            households.get(householdId)
            requireEnabled(DIGILOCKER, vault.mode)
            val active = activeSession(householdId)
            // Before anything is fetched from DigiLocker, stored or encrypted — the
            // document insert refuses a viewer too, but only after all of that.
            households.requireWriter(householdId)
            active
        }
        val available = provider(DIGILOCKER, "list") { vault.list(session) }.associateBy { it.uri }

        var skipped = 0
        val fetched = uris.mapNotNull { uri ->
            val meta = available[uri]
            if (meta == null) {
                skipped++
                return@mapNotNull null
            }
            meta to provider(DIGILOCKER, "fetch") { vault.fetch(session, uri) }
        }

        return inTransaction { storeDocuments(householdId, userId, fetched, skipped) }
    }

    private fun storeDocuments(
        householdId: UUID,
        userId: UUID,
        fetched: List<Pair<VaultDocument, ByteArray>>,
        skipped: Int,
    ): ImportedFromProvider {
        val titles = mutableListOf<String>()
        fetched.forEach { (meta, bytes) ->
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
                "Nothing was linked to a holding. Do that where it belongs.",
        )
    }

    // --- Account Aggregator ----------------------------------------------------

    fun requestConsent(householdId: UUID): ConsentHandle {
        val userId = userContext.require()
        inTransaction {
            households.get(householdId)
            requireEnabled(AA, aggregator.mode)
            // Before a consent is created at the aggregator, which the rollback of a
            // refused connection insert cannot take back.
            households.requireAdministrator(householdId)
        }
        val request = ConsentRequest(
            purpose = "Personal finance management",
            fiTypes = listOf("DEPOSIT", "TERM_DEPOSIT", "MUTUAL_FUNDS", "EQUITIES"),
            fromDate = LocalDate.now().minusYears(1),
            toDate = LocalDate.now(),
        )
        // Not idempotent: a retried consent request after a timeout can leave
        // the person with two consents to approve and no idea which is real.
        val consent = provider(AA, "consent", idempotent = false) {
            aggregator.requestConsent(householdId, request)
        }
        inTransaction {
            upsertConnection(householdId, "account_aggregator", aggregator.mode, "pending", consent.handle, userId)
        }
        return consent
    }

    fun consentStatus(householdId: UUID): ConsentHandle {
        val handle = inTransaction {
            households.get(householdId)
            requireEnabled(AA, aggregator.mode)
            externalRef(householdId, "account_aggregator")
        }
        val status = provider(AA, "consent-status") { aggregator.consentStatus(handle) }
        if (status.status == "ACTIVE") {
            inTransaction {
                jdbc.update(
                    """
                    update provider_connections set status = 'active'
                    where household_id = :hid and provider = 'account_aggregator'
                    """.trimIndent(),
                    mapOf("hid" to householdId),
                )
            }
        }
        return status
    }

    /**
     * What the network reports becomes ordinary records — at the household's own
     * default visibility, never wider, and marked with where they came from. A
     * second run recognises what it already imported by the masked account
     * number, so re-consenting does not double the balance sheet.
     */
    fun importHoldings(householdId: UUID): ImportedFromProvider {
        val userId = userContext.require()
        val handle = inTransaction {
            households.get(householdId)
            requireEnabled(AA, aggregator.mode)
            val ref = externalRef(householdId, "account_aggregator")
            // Before the aggregator is asked for anyone's data: the investment
            // insert refuses a viewer too, but only after the fetch.
            households.requireWriter(householdId)
            ref
        }
        // A provider failure is its own answer. Only the adapter's own refusal
        // (the consent is not active) means "approve it first" — reading a
        // timeout as that would send someone to re-approve a consent that is
        // fine, while the real problem is on the other end.
        val discovered = try {
            provider(AA, "fetch") { aggregator.fetch(handle) }
        } catch (e: ApiException) {
            throw e
        } catch (e: RuntimeException) {
            throw ApiException.badRequest(
                "consent_not_active", "That consent isn't active yet. Approve it first.",
            )
        }

        return inTransaction { storeHoldings(householdId, userId, discovered) }
    }

    private fun storeHoldings(householdId: UUID, userId: UUID, discovered: List<DiscoveredHolding>): ImportedFromProvider {
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
                tech.almira.investment.CustomFieldInput(
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
                        tech.almira.investment.ValuationInput(value = it, asOfDate = holding.asOf)
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
     *
     * A reply that fails to send does not fail the capture: the webhook answers
     * 200 with [WhatsAppCapture.replyFailure] set. Failing the webhook would make
     * the provider redeliver it, and the person would get the reply twice once
     * the outage passed — or, on a rejection, be retried into the same refusal.
     */
    fun captureFromWhatsApp(
        householdId: UUID,
        signature: String?,
        body: ByteArray,
    ): WhatsAppCapture {
        // Before the signature: a disabled gateway refuses every signature, and
        // "didn't come from where it claims to" would be the wrong thing to say.
        requireEnabled(WHATSAPP, whatsApp.mode)
        if (!whatsApp.verify(signature, body)) {
            throw ApiException.forbidden("That message didn't come from where it claims to.")
        }
        val message = whatsApp.parse(body)
            ?: return WhatsAppCapture(false, "We couldn't read that message.")
        if (!deliveries.firstDelivery(householdId, message.messageId)) {
            return WhatsAppCapture(understood = false, reply = "", duplicate = true)
        }

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
        val replyFailure = try {
            provider(WHATSAPP, "reply") { whatsApp.reply(message.from, reply) }
            null
        } catch (e: ApiException) {
            e.code
        }

        return WhatsAppCapture(
            understood = recognised.isNotEmpty(),
            reply = reply,
            replyFailure = replyFailure,
            fields = parsed.fields.map {
                mapOf("key" to it.key, "label" to it.label, "display" to it.display, "value" to it.value)
            },
        )
    }

    // --- DigiLocker session at rest ----------------------------------------------

    /**
     * The `state` must be the one [startDocumentVault] handed to this same
     * person, and not stale. Every way of failing gives the same answer, so the
     * refusal says nothing about whose flow was in progress.
     */
    private fun requireState(householdId: UUID, userId: UUID, state: String?) {
        val pending = jdbc.query(
            """
            select detail ->> '$STATE_HASH' as hash, detail ->> '$STATE_EXPIRES' as expires,
                   detail ->> '$STATE_STARTED_BY' as started_by
            from provider_connections where household_id = :hid and provider = 'digilocker'
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ -> Triple(rs.getString("hash"), rs.getString("expires"), rs.getString("started_by")) }
            .firstOrNull()

        val (hash, expires, startedBy) = pending ?: Triple(null, null, null)
        val matches = hash != null && !state.isNullOrBlank() && state.length <= 200 &&
            java.security.MessageDigest.isEqual(
                sha256(state).toByteArray(Charsets.US_ASCII),
                hash.toByteArray(Charsets.US_ASCII),
            ) &&
            startedBy == userId.toString() &&
            runCatching { Instant.parse(expires) }.getOrNull()?.isAfter(Instant.now()) == true
        if (!matches) {
            throw ApiException.badRequest(
                "connect_state_mismatch",
                "That DigiLocker sign-in didn't start here, or took too long, so nothing was connected. " +
                    "Please start again from Almira.",
            )
        }
    }

    /**
     * V24's design, finally followed: the token encrypted with the household's
     * data key in `access_token_enc`, its real expiry in `expires_at`, and
     * `external_ref` — "never a credential" — left empty. The state is spent.
     */
    private fun storeSession(householdId: UUID, session: ProviderSession) {
        jdbc.update(
            """
            update provider_connections set
              mode = :mode, status = 'active', external_ref = null,
              access_token_enc = :token, expires_at = :expires, scope = :scope,
              detail = detail - '$STATE_HASH' - '$STATE_EXPIRES' - '$STATE_STARTED_BY'
            where household_id = :hid and provider = 'digilocker'
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("mode", vault.mode.name.lowercase())
                .addValue("token", cipher.encrypt(householdId, TOKEN_FIELD, session.token))
                .addValue("expires", java.sql.Timestamp.from(session.expiresAt))
                .addValue("scope", session.scope),
        )
    }

    /**
     * The stored session, decrypted, with the expiry it really has. An expired
     * one is refused here rather than sent, so the answer is "connect again"
     * and not the provider's rejection.
     */
    private fun activeSession(householdId: UUID, requireActive: Boolean = false): ProviderSession {
        val stored = jdbc.query(
            """
            select access_token_enc, expires_at, scope from provider_connections
            where household_id = :hid and provider = 'digilocker'
              and (:anyStatus or status = 'active')
            """.trimIndent(),
            mapOf("hid" to householdId, "anyStatus" to !requireActive),
        ) { rs, _ ->
            val token = rs.getBytes("access_token_enc") ?: return@query null
            ProviderSession(
                token = cipher.decrypt(householdId, TOKEN_FIELD, token),
                expiresAt = rs.getTimestamp("expires_at")?.toInstant() ?: Instant.EPOCH,
                scope = rs.getString("scope") ?: "files.issueddocs",
            )
        }.firstOrNull()
            ?: throw ApiException.badRequest(
                "not_connected", "Connect that first. There's nothing to fetch yet.",
            )
        if (!stored.expiresAt.isAfter(Instant.now())) {
            throw ApiException.badRequest(
                "connection_expired",
                "Your DigiLocker connection has run out. Connect again. Your documents are still there.",
            )
        }
        return stored
    }

    private fun newState(): String {
        val bytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // --- plumbing --------------------------------------------------------------

    /**
     * A disabled provider is refused before anything is written or called — no
     * pending connection row, no audit entry, no provider call — with 409
     * `provider_disabled`. Checked after the household, so a caller who is not a
     * member still gets the same 404 as for any other household.
     */
    private fun requireEnabled(name: String, mode: ProviderMode) {
        if (mode == ProviderMode.DISABLED) throw ProviderErrors.disabled(name)
    }

    /** What the sandbox does is worth saying only when the sandbox is what is running. */
    private fun sandboxOnly(mode: ProviderMode, note: String): String? =
        note.takeIf { mode == ProviderMode.SANDBOX }

    /** Through the shared timeout and retry policy, with each failure turned into its own answer. */
    private fun <T> provider(name: String, operation: String, idempotent: Boolean = true, block: () -> T): T =
        try {
            calls.interactive(name, operation, props.providers.connectBudget, idempotent, block)
        } catch (failure: ProviderCallFailed) {
            throw ProviderErrors.forConnect(failure)
        }

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

    private fun externalRef(householdId: UUID, provider: String, requireActive: Boolean = false): String = jdbc.query(
        """
        select external_ref from provider_connections
        where household_id = :hid and provider = :provider
          and (:anyStatus or status = 'active')
        """.trimIndent(),
        mapOf("hid" to householdId, "provider" to provider, "anyStatus" to !requireActive),
    ) { rs, _ -> rs.getString("external_ref") }.firstOrNull()
        ?: throw ApiException.badRequest(
            "not_connected", "Connect that first. There's nothing to fetch yet.",
        )

    private companion object {
        const val DIGILOCKER = "digilocker"
        const val AA = "aa"
        const val WHATSAPP = "whatsapp"

        /** The AAD position of a DigiLocker token (docs/05 §4). */
        const val TOKEN_FIELD = "provider_connections.access_token_enc"
        const val STATE_HASH = "oauth_state_sha256"
        const val STATE_EXPIRES = "oauth_state_expires_at"
        const val STATE_STARTED_BY = "oauth_started_by"
        val STATE_LIFETIME: java.time.Duration = java.time.Duration.ofMinutes(15)
    }

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
