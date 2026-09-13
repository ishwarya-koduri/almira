package tech.bhrigu.almira.e2e

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * The plaintext "where it is kept" fields are retired (V33, docs/20 §1).
 *
 * The owner's words: "no plain-text fallback" was not true while the server
 * could still create one. These tests are the ways it could: a client sending
 * the old fields, a duplicate or a template carrying the column across, a type
 * or custom field asking for it again, the handbook and the transmission guide
 * printing it, and the columns themselves.
 */
@DisplayName("Plaintext locations are retired")
class PlaintextLocationRetiredApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var memberId: String

    // No fixture title or label repeats a word from this, so a match anywhere can
    // only be the sentence itself.
    private val sentence = "Tamarind cupboard, behind the rice tins"

    @BeforeEach
    fun setUp() {
        owner = signIn()
        val household = createHousehold(owner, "Retired", "private", "Ishwarya")
        householdId = household.path("id").asText()
        memberId = household.path("myMemberId").asText()
    }

    private fun assertRefused(response: ResponseEntity<String>, field: String) {
        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.errorCode()).isEqualTo("plaintext_location_retired")
        assertThat(response.json().path("error").path("details").path("field").asText()).isEqualTo(field)
        assertThat(response.body).describedAs("the refusal never echoes the text").doesNotContain("Tamarind")
    }

    private fun gold(extra: Map<String, Any?> = emptyMap()) = post(
        "/api/v1/households/$householdId/investments", owner,
        mapOf(
            "id" to uuid(),
            "typeId" to typeId(owner, householdId, "gold_physical"),
            "title" to "Coins", "investedAmount" to 50000, "quantity" to 10,
            "attributes" to mapOf("purity" to "24k"),
        ) + extra,
    )

    private fun holding(): JsonNode {
        val response = gold()
        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.CREATED)
        return response.json().path("investment")
    }

    @Test
    fun `a holding sent with a location is refused, and nothing is saved`() {
        val id = uuid()
        val response = post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "id" to id, "typeId" to typeId(owner, householdId, "gold_physical"),
                "title" to "Coins", "investedAmount" to 50000, "quantity" to 10,
                "attributes" to mapOf("purity" to "24k"), "storageLocation" to sentence,
            ),
        )
        assertRefused(response, "storageLocation")
        assertThat(get("/api/v1/households/$householdId/investments/$id", owner).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an empty location carries nothing, so it is accepted and nothing comes back`() {
        val response = gold(mapOf("storageLocation" to ""))
        assertThat(response.statusCode).describedAs(response.body).isEqualTo(HttpStatus.CREATED)
        assertThat(response.json().path("investment").has("storageLocation")).isFalse()
    }

    @Test
    fun `an edit that sends a location is refused and changes nothing`() {
        val record = holding()
        val id = record.path("id").asText()
        val response = patch(
            "/api/v1/households/$householdId/investments/$id", owner,
            mapOf("version" to record.path("version").asInt(), "title" to "Renamed", "storageLocation" to sentence),
        )
        assertRefused(response, "storageLocation")
        val after = get("/api/v1/households/$householdId/investments/$id", owner).json()
        assertThat(after.path("title").asText()).isEqualTo("Coins")
        assertThat(after.path("version").asInt()).isEqualTo(record.path("version").asInt())

        // The old "clear the note" PATCH still works, and does nothing.
        val cleared = patch(
            "/api/v1/households/$householdId/investments/$id", owner,
            mapOf("version" to record.path("version").asInt(), "storageLocation" to ""),
        )
        assertThat(cleared.statusCode).describedAs(cleared.body).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a custom field cannot bring the column back under its own name`() {
        val onCapture = gold(
            mapOf("customFields" to listOf(mapOf("key" to "storage_location", "label" to "Kept", "dataType" to "text"))),
        )
        assertRefused(onCapture, "key")

        val onType = post(
            "/api/v1/households/$householdId/types", owner,
            mapOf(
                "label" to "Heirlooms",
                "fields" to listOf(mapOf("key" to "storage_location", "label" to "Kept", "dataType" to "text")),
            ),
        )
        assertRefused(onType, "key")
    }

    @Test
    fun `templates neither take a location nor carry one`() {
        val typeId = typeId(owner, householdId, "gold_physical")
        assertRefused(
            post(
                "/api/v1/households/$householdId/templates", owner,
                mapOf("name" to "Coins again", "typeId" to typeId, "storageLocation" to sentence),
            ),
            "storageLocation",
        )
        assertRefused(
            post(
                "/api/v1/households/$householdId/templates", owner,
                mapOf("name" to "Coins again", "typeId" to typeId, "attributes" to mapOf("storage_location" to sentence)),
            ),
            "attributes.storage_location",
        )

        val record = holding()
        val template = post(
            "/api/v1/households/$householdId/templates", owner,
            mapOf("name" to "From the coins", "fromInvestmentId" to record.path("id").asText()),
        )
        assertThat(template.statusCode).describedAs(template.body).isEqualTo(HttpStatus.CREATED)
        assertThat(template.json().has("storageLocation")).isFalse()
        val templateId = template.json().path("id").asText()

        assertRefused(
            patch(
                "/api/v1/households/$householdId/templates/$templateId", owner,
                mapOf("version" to template.json().path("version").asInt(), "storageLocation" to sentence),
            ),
            "storageLocation",
        )

        val applied = post("/api/v1/households/$householdId/templates/$templateId/apply", owner, emptyMap<String, Any>())
        assertThat(applied.statusCode).describedAs(applied.body).isEqualTo(HttpStatus.CREATED)
        assertThat(applied.json().path("investment").has("storageLocation")).isFalse()
    }

    @Test
    fun `a will sent with a location is refused, on create and on edit`() {
        assertRefused(
            post(
                "/api/v1/households/$householdId/estate/documents", owner,
                mapOf("memberId" to memberId, "kind" to "will", "title" to "Will", "location" to sentence),
            ),
            "location",
        )
        val created = post(
            "/api/v1/households/$householdId/estate/documents", owner,
            mapOf("memberId" to memberId, "kind" to "will", "title" to "Will", "location" to ""),
        )
        assertThat(created.statusCode).describedAs(created.body).isEqualTo(HttpStatus.CREATED)
        assertThat(created.json().has("location")).isFalse()
        val id = created.json().path("id").asText()
        assertRefused(
            patch(
                "/api/v1/households/$householdId/estate/documents/$id", owner,
                mapOf("version" to created.json().path("version").asInt(), "location" to sentence),
            ),
            "location",
        )
    }

    @Test
    fun `a duplicate, the handbook and the transmission guide have no location to carry or print`() {
        val record = holding()
        val id = record.path("id").asText()
        val copy = post("/api/v1/households/$householdId/investments/$id/duplicate", owner, emptyMap<String, Any>())
        assertThat(copy.statusCode).describedAs(copy.body).isEqualTo(HttpStatus.CREATED)
        assertThat(copy.json().path("investment").has("storageLocation")).isFalse()

        post(
            "/api/v1/households/$householdId/estate/documents", owner,
            mapOf("memberId" to memberId, "kind" to "will", "title" to "Will"),
        )
        val handbook = get("/api/v1/households/$householdId/continuity/handbook", owner).json()
        assertThat(handbook.path("entries").map { it.has("whereItIsKept") }).isNotEmpty.containsOnly(false)
        assertThat(handbook.path("instruments").map { it.has("location") }).isNotEmpty.containsOnly(false)

        val guide = get("/api/v1/households/$householdId/continuity/transmission/$id", owner)
        assertThat(guide.statusCode).describedAs(guide.body).isEqualTo(HttpStatus.OK)
        assertThat(guide.json().has("whereItIsKept")).isFalse()
    }

    @Test
    fun `no type asks for it, and the database has nowhere to put it`() {
        val taxonomy = get("/api/v1/households/$householdId/taxonomy", owner).json()
        val schemas = taxonomy.flatMap { category -> category.path("types").map { it.path("schema") } }
        assertThat(schemas).isNotEmpty
        assertThat(schemas.filter { it.path("common").has("storage_location") }).isEmpty()
        assertThat(schemas.flatMap { s -> s.path("fields").map { it.path("key").asText() } })
            .doesNotContain("storage_location")

        assertThat(
            db.queryForList(
                """
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = 'public'
                  and ((table_name in ('investments','investment_templates') and column_name = 'storage_location')
                    or (table_name = 'estate_documents' and column_name = 'location'))
                """.trimIndent(),
                String::class.java,
            ),
        ).describedAs("the three plaintext columns").isEmpty()

        // A type that asks again, or attributes that carry it, are refused by the
        // database too, whatever path writes them.
        assertThatThrownBy {
            db.update(
                """
                update investment_types
                   set field_schema = jsonb_set(field_schema, '{common,storage_location}', '{"label":"Kept"}')
                 where code = 'gold_physical'
                """.trimIndent(),
            )
        }.hasMessageContaining("type_does_not_ask_for_plaintext_location")
        assertThatThrownBy {
            db.update(
                "update investments set attributes = attributes || '{\"storage_location\":\"\"}' where id = ?::uuid",
                holding().path("id").asText(),
            )
        }.hasMessageContaining("no_plaintext_location_in_attributes")
    }

    @Test
    fun `the plaintext where fields on crypto and a business stake are gone as well`() {
        // V34: "Where the keys are" and "Where the agreement is" asked the same
        // question in plain text, into attributes the server copied and searched.
        val taxonomy = get("/api/v1/households/$householdId/taxonomy", owner).json()
        val types = taxonomy.flatMap { category -> category.path("types").toList() }
        val keysByCode = types.associate { type ->
            type.path("code").asText() to type.path("schema").path("fields").map { it.path("key").asText() }
        }
        assertThat(keysByCode["crypto"]).describedAs("crypto still captures").contains("asset_symbol")
            .doesNotContain("wallet_hint")
        assertThat(keysByCode["business_equity"]).contains("business_name").doesNotContain("agreement_location")
        assertThat(keysByCode.values.flatten()).doesNotContain("wallet_hint", "agreement_location")

        val cryptoId = post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "id" to uuid(), "typeId" to typeId(owner, householdId, "crypto"),
                "title" to "Bitcoin", "investedAmount" to 10000, "quantity" to 1,
                "attributes" to mapOf("asset_symbol" to "BTC", "wallet_hint" to sentence),
            ),
        )
        assertRefused(cryptoId, "attributes.wallet_hint")

        // An older client that sends the field empty still saves the holding.
        val saved = post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "id" to uuid(), "typeId" to typeId(owner, householdId, "business_equity"),
                "title" to "Stake", "investedAmount" to 10000,
                "attributes" to mapOf("business_name" to "Pickles", "agreement_location" to ""),
            ),
        )
        assertThat(saved.statusCode).describedAs(saved.body).isEqualTo(HttpStatus.CREATED)
        val stake = saved.json().path("investment")
        assertThat(stake.path("attributes").has("agreement_location")).isFalse()
        assertRefused(
            patch(
                "/api/v1/households/$householdId/investments/${stake.path("id").asText()}", owner,
                mapOf(
                    "version" to stake.path("version").asInt(),
                    "attributes" to mapOf("business_name" to "Pickles", "agreement_location" to sentence),
                ),
            ),
            "attributes.agreement_location",
        )

        assertRefused(
            post(
                "/api/v1/households/$householdId/templates", owner,
                mapOf(
                    "name" to "Keys again", "typeId" to typeId(owner, householdId, "crypto"),
                    "attributes" to mapOf("wallet_hint" to sentence),
                ),
            ),
            "attributes.wallet_hint",
        )
        assertRefused(
            post(
                "/api/v1/households/$householdId/types", owner,
                mapOf(
                    "label" to "Wallets",
                    "fields" to listOf(mapOf("key" to "wallet_hint", "label" to "Keys", "dataType" to "text")),
                ),
            ),
            "key",
        )

        assertThatThrownBy {
            db.update(
                """
                update investment_types
                   set field_schema = jsonb_set(field_schema, '{fields}',
                         (field_schema -> 'fields') || '[{"key":"wallet_hint","label":"Keys","dataType":"text"}]')
                 where code = 'crypto'
                """.trimIndent(),
            )
        }.hasMessageContaining("type_does_not_ask_for_plaintext_where")
        assertThatThrownBy {
            db.update(
                "update investments set attributes = attributes || '{\"agreement_location\":\"\"}' where id = ?::uuid",
                stake.path("id").asText(),
            )
        }.hasMessageContaining("no_plaintext_where_in_attributes")
    }

    @Test
    fun `a type that used to ask for it still captures without it`() {
        val captured = capture(owner, householdId, "cash_on_hand", "Envelope", BigDecimal("2000"))
        assertThat(captured.path("investment").has("storageLocation")).isFalse()
    }
}
