package tech.bhrigu.almira.catalog

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * The typed view of `investment_types.field_schema`.
 *
 * One definition serves two consumers that must never disagree: the client
 * renders the capture form from it, and the server validates against it. When
 * a form and its validator are written separately they drift, and the user
 * meets the drift as "Save" failing on a field the form said was fine.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TypeSchema(
    /**
     * Re-labels a first-class column for this type. A Fixed Deposit's
     * "Principal" and a gold purchase's "Amount paid" are both
     * investments.invested_amount; only the wording differs.
     */
    val common: Map<String, CommonFieldDef> = emptyMap(),
    /** Type-specific values, stored in investments.attributes. */
    val fields: List<FieldDef> = emptyList(),
) {
    fun field(key: String): FieldDef? = fields.firstOrNull { it.key == key }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CommonFieldDef(
    val label: String,
    /** "essential" renders on the form; "more" hides behind "More details". */
    val group: String = "more",
    val sort: Int = 100,
    val required: Boolean = false,
    val unit: String? = null,
    val help: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class FieldOption(val value: String, val label: String)

enum class DataType(val wire: String) {
    TEXT("text"), NUMBER("number"), MONEY("money"), DATE("date"),
    PERCENT("percent"), BOOL("bool"), SELECT("select");

    companion object {
        fun from(wire: String): DataType? = entries.firstOrNull { it.wire == wire }
    }
}
