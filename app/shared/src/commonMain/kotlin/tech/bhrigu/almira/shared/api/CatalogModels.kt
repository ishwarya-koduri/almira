package tech.bhrigu.almira.shared.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The taxonomy, and the field schema that generates the capture form.
 *
 * This is the contract's most important idea for a second client. The form is
 * not written here — it is *derived* from `schema`, the same definition the
 * server validates against and the same one the web client renders. So the app
 * can never ask for a field the API will reject, and adding an asset type stays
 * a data change rather than a release of two clients.
 *
 * Shapes come from `docs/api/openapi-v1.json`: TaxonomyResponse, TypeResponse,
 * TypeSchema, CommonFieldDef, FieldDef, FieldOption.
 */

@Serializable
data class TaxonomyCategory(
    val categoryCode: String,
    val categoryLabel: String,
    val color: String,
    val icon: String? = null,
    val types: List<InvestmentType> = emptyList(),
)

@Serializable
data class InvestmentType(
    val id: String,
    val code: String,
    val label: String,
    val categoryCode: String,
    val categoryLabel: String,
    val color: String,
    val icon: String? = null,
    val isCustom: Boolean = false,
    val schemaVersion: Int = 1,
    val schema: TypeSchema = TypeSchema(),
)

@Serializable
data class TypeSchema(
    /**
     * Re-labels a first-class column for this type. A Fixed Deposit's
     * "Principal" and a gold purchase's "Amount paid" are the same column;
     * only the wording differs. Keys are the column names in snake_case.
     */
    val common: Map<String, CommonFieldDef> = emptyMap(),
    /** Type-specific values, which land in `attributes`. */
    val fields: List<FieldDef> = emptyList(),
)

@Serializable
data class CommonFieldDef(
    val label: String,
    /** "essential" renders on the form; anything else waits behind More details. */
    val group: String = "more",
    val sort: Int = 100,
    val required: Boolean = false,
    val unit: String? = null,
    val help: String? = null,
)

@Serializable
data class FieldDef(
    val key: String,
    val label: String,
    val dataType: String,
    val group: String = "more",
    val sort: Int = 100,
    val required: Boolean = false,
    val unit: String? = null,
    val options: List<FieldOption>? = null,
    val help: String? = null,
    val placeholder: String? = null,
)

@Serializable
data class FieldOption(val value: String, val label: String)

// --- the people and places a holding can be attached to ---------------------

@Serializable
data class Member(
    val id: String,
    val displayName: String,
    val relationship: String? = null,
    val isMinor: Boolean = false,
    val isManaged: Boolean = false,
    val isMe: Boolean = false,
    val role: String? = null,
)

@Serializable
data class Institution(
    val id: String,
    val name: String,
    val kind: String? = null,
    val isCustom: Boolean = false,
)

// --- capture ----------------------------------------------------------------

/**
 * Everything but `attributes` is a first-class column; `attributes` is the
 * type's own fields, already coerced to the shape the server stores.
 *
 * `explicitNulls = false` on the client's Json means an absent value is simply
 * left out of the request rather than sent as null, which is what the server's
 * "absent means unchanged / unset" defaults expect.
 */
@Serializable
data class CreateInvestmentBody(
    val typeId: String,
    val title: String,
    val investedAmount: String? = null,
    val quantity: String? = null,
    val startDate: String? = null,
    val maturityDate: String? = null,
    val storageLocation: String? = null,
    val institutionId: String? = null,
    val notes: String? = null,
    val visibility: String? = null,
    val visibleToMemberIds: List<String> = emptyList(),
    val owners: List<OwnerInput> = emptyList(),
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class OwnerInput(val memberId: String, val sharePct: Int = 100)

/**
 * A record saved as Private for someone else is a legitimate outcome, and the
 * server says so rather than pretending: `visibleToYou` false means it saved
 * and the person who typed it cannot read it back. Saying that plainly is much
 * better than appearing to have lost it.
 */
@Serializable
data class CreateInvestmentResponse(
    val id: String,
    val visibleToYou: Boolean,
    val investment: InvestmentSummary? = null,
)

@Serializable
data class InvestmentSummary(
    val id: String,
    val title: String,
    val typeLabel: String,
    val categoryLabel: String,
    val color: String,
    val currency: String,
    val valueFormatted: String? = null,
    val attributes: Map<String, JsonElement> = emptyMap(),
)
