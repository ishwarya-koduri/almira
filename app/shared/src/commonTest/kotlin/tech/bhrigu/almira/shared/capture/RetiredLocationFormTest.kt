package tech.bhrigu.almira.shared.capture

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.bhrigu.almira.shared.api.CommonFieldDef
import tech.bhrigu.almira.shared.api.InvestmentType
import tech.bhrigu.almira.shared.api.TypeSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The plain-text "Where it's kept" column is retired (V33, docs/20 §1). A schema
 * from an older server may still name it; the form must not ask, and the
 * request must not carry it.
 */
class RetiredLocationFormTest {

    private val gold = InvestmentType(
        id = "t1", code = "gold_physical", label = "Physical Gold",
        categoryCode = "gold", categoryLabel = "Gold", color = "#C9A227",
        schema = TypeSchema(
            common = mapOf(
                "invested_amount" to CommonFieldDef("Amount paid", "essential", 20, required = true),
                "storage_location" to CommonFieldDef("Where it's kept", "essential", 40, help = "Home locker"),
            ),
        ),
    )

    @Test
    fun anOldSchemaThatStillNamesTheColumnDoesNotGetAField() {
        assertEquals(listOf("invested_amount"), gold.schema.toFormFields().map { it.key })
    }

    @Test
    fun theRequestHasNoLocationEvenIfAValueWasTypedUnderTheOldKey() {
        val fields = gold.schema.toFormFields()
        val body = buildCreateBody(
            type = gold, fields = fields,
            values = mapOf("invested_amount" to FieldValue("5000"), "storage_location" to FieldValue("Home locker")),
            title = "Coins", ownerMemberId = null, institutionId = null,
            visibility = "private", visibleToMemberIds = emptyList(), notes = null,
        )
        val json = Json { explicitNulls = false }.encodeToString(body)
        assertFalse("storageLocation" in json, json)
        assertFalse("Home locker" in json, json)
    }
}
