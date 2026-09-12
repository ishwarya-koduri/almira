package tech.bhrigu.almira.shared.capture

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tech.bhrigu.almira.shared.api.CreateInvestmentBody
import tech.bhrigu.almira.shared.api.FieldOption
import tech.bhrigu.almira.shared.api.InvestmentType
import tech.bhrigu.almira.shared.api.OwnerInput
import tech.bhrigu.almira.shared.api.TypeSchema

/**
 * The capture form, derived from a type's `field_schema`.
 *
 * Nothing about any asset type is written here. The list of fields, their
 * labels, which are required, which are essential and which wait behind "More
 * details" all come from the schema the server sends — the same definition the
 * server validates against and the web client renders. That is the whole point:
 * a new asset type is a row in a table, not a release of two clients, and the
 * form can never ask for something the API will reject.
 *
 * Pure Kotlin on purpose — no Compose, no platform. The screen renders whatever
 * this produces, and the same derivation runs unchanged on iOS.
 */

/** The seven things a field can be, straight from the contract's `dataType`. */
enum class FieldKind {
    Text, Number, Money, Date, Percent, Bool, Select;

    companion object {
        fun from(wire: String): FieldKind = when (wire) {
            "money" -> Money
            "number" -> Number
            "percent" -> Percent
            "date" -> Date
            "bool" -> Bool
            "select" -> Select
            else -> Text
        }
    }
}

data class FormField(
    /**
     * `invested_amount` for a first-class column, `attr:interest_rate` for a
     * type attribute. The prefix is what decides where the value goes in the
     * request, and it is also how a server field error finds its control.
     */
    val key: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean,
    val essential: Boolean,
    val unit: String? = null,
    val help: String? = null,
    val placeholder: String? = null,
    val options: List<FieldOption> = emptyList(),
) {
    val isAttribute: Boolean get() = key.startsWith(ATTR)
    /** The bare key the server knows this by. */
    val wireKey: String get() = key.removePrefix(ATTR)

    companion object {
        const val ATTR = "attr:"
    }
}

/**
 * The columns a type may re-label, and the kind each one is.
 *
 * These are fixed by the database, not by the schema — the schema only supplies
 * the wording ("Principal", "Amount paid") and whether the field is essential.
 */
private val COLUMN_KINDS: Map<String, FieldKind> = mapOf(
    "invested_amount" to FieldKind.Money,
    "quantity" to FieldKind.Number,
    "start_date" to FieldKind.Date,
    "maturity_date" to FieldKind.Date,
    "storage_location" to FieldKind.Text,
)

/**
 * Schema in, fields out, ordered by `sort`.
 *
 * Ordering is the one place this differs from the web client, which renders
 * every re-labelled column first and every attribute after, so `sort` only
 * orders within each group. Interleaving them is what the numbers plainly ask
 * for — a Fixed Deposit's interest rate is sort 30, between Principal at 20 and
 * "Opened on" at 40, and reading Principal → rate → dates is the order someone
 * holds an FD receipt in their head. Which fields exist, what they are called
 * and what they accept still come from the schema alone, so the two clients
 * cannot disagree about anything that reaches the server.
 */
fun TypeSchema.toFormFields(): List<FormField> {
    val columns = common.mapNotNull { (key, def) ->
        val kind = COLUMN_KINDS[key] ?: return@mapNotNull null
        def.sort to FormField(
            key = key,
            label = def.label,
            kind = kind,
            required = def.required,
            essential = def.group == "essential",
            unit = def.unit,
            // For a plain text column the schema's help reads as an example
            // ("Home locker, bank locker, with a relative…"), so it belongs in
            // the placeholder. Saying it twice wastes the line an error needs.
            help = if (kind == FieldKind.Text) null else def.help,
            placeholder = if (kind == FieldKind.Text) def.help else null,
        )
    }

    val attributes = fields.map { def ->
        def.sort to FormField(
            key = FormField.ATTR + def.key,
            label = def.label,
            kind = FieldKind.from(def.dataType),
            required = def.required,
            essential = def.group == "essential",
            unit = def.unit,
            help = def.help,
            placeholder = def.placeholder,
            options = def.options.orEmpty(),
        )
    }

    return (columns + attributes).sortedBy { it.first }.map { it.second }
}

/**
 * What a field currently holds. One string for everything except a checkbox,
 * because that is what a text control actually gives you and because coercion
 * belongs on the server, once, where both clients meet it identically.
 */
data class FieldValue(val text: String = "", val checked: Boolean = false)

/**
 * Assembles the request.
 *
 * Numbers travel as strings. The server stores money as a plain string in jsonb
 * precisely because jsonb's number type is a double and money must never
 * round-trip through a float; sending the string it is going to store keeps the
 * digits the user typed intact all the way down. Verified against the running
 * API rather than assumed.
 *
 * A blank field is left out entirely rather than sent as an empty string, so
 * "not filled in" stays distinct from "deliberately empty".
 */
fun buildCreateBody(
    type: InvestmentType,
    fields: List<FormField>,
    values: Map<String, FieldValue>,
    title: String,
    ownerMemberId: String?,
    institutionId: String?,
    visibility: String,
    visibleToMemberIds: List<String>,
    notes: String?,
): CreateInvestmentBody {
    val attributes = mutableMapOf<String, JsonPrimitive>()
    val columns = mutableMapOf<String, String>()

    fields.forEach { field ->
        val value = values[field.key] ?: return@forEach
        if (field.kind == FieldKind.Bool) {
            // Only a ticked box is sent, which is what the web does: an
            // untouched checkbox is "not recorded", not "no".
            if (value.checked && field.isAttribute) attributes[field.wireKey] = JsonPrimitive(true)
            return@forEach
        }
        val text = value.text.trim()
        if (text.isEmpty()) return@forEach
        if (field.isAttribute) attributes[field.wireKey] = JsonPrimitive(text)
        else columns[field.key] = text
    }

    return CreateInvestmentBody(
        typeId = type.id,
        title = title.trim(),
        investedAmount = columns["invested_amount"],
        quantity = columns["quantity"],
        startDate = columns["start_date"],
        maturityDate = columns["maturity_date"],
        storageLocation = columns["storage_location"],
        institutionId = institutionId,
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
        visibility = visibility,
        visibleToMemberIds = if (visibility == "scoped") visibleToMemberIds else emptyList(),
        owners = ownerMemberId?.let { listOf(OwnerInput(it, 100)) } ?: emptyList(),
        attributes = JsonObject(attributes),
    )
}
