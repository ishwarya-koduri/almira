package tech.bhrigu.almira.investment

import org.springframework.stereotype.Component
import tech.bhrigu.almira.catalog.CustomFieldRow
import tech.bhrigu.almira.catalog.DataType
import tech.bhrigu.almira.catalog.FieldDef
import tech.bhrigu.almira.catalog.FieldOption
import tech.bhrigu.almira.catalog.TypeSchema
import tech.bhrigu.almira.common.ApiException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Validates and normalises the free-form `attributes` map against the type's
 * schema plus any custom fields on the record.
 *
 * jsonb will accept anything, which is exactly why this has to exist. Without
 * it a weight arrives as "6.3g" from one client and 6.3 from another, and every
 * consumer downstream — totals, reports, the family handbook — has to guess.
 * Values are coerced to their declared type here, once, so the rest of the
 * system can rely on them.
 *
 * Unknown keys are rejected rather than silently kept. A typo that lands in
 * jsonb is invisible: the field looks saved and simply never appears again.
 */
@Component
class AttributeValidator {

    fun validate(
        schema: TypeSchema,
        customFields: List<CustomFieldRow>,
        submitted: Map<String, Any?>,
    ): Map<String, Any?> {
        val definitions = buildMap<String, FieldDef> {
            schema.fields.forEach { put(it.key, it) }
            // A record-level custom field shadows a type field of the same name,
            // which is what lets someone override a template on one holding.
            customFields.forEach { put(it.key, it.toFieldDef()) }
        }

        val unknown = submitted.keys - definitions.keys
        if (unknown.isNotEmpty()) {
            throw ApiException.badRequest(
                "attribute_unknown",
                "We don't have a place for: ${unknown.sorted().joinToString()}. " +
                    "Add it as a custom field first.",
                mapOf("unknownKeys" to unknown.sorted()),
            )
        }

        val errors = mutableMapOf<String, String>()
        val cleaned = mutableMapOf<String, Any?>()

        definitions.values.forEach { def ->
            val raw = submitted[def.key]
            if (raw == null || (raw is String && raw.isBlank())) {
                if (def.required) errors[def.key] = "${def.label} is needed"
                return@forEach
            }
            try {
                cleaned[def.key] = coerce(def, raw)
            } catch (e: FieldError) {
                errors[def.key] = e.message!!
            }
        }

        if (errors.isNotEmpty()) {
            throw ApiException.badRequest(
                "attributes_invalid",
                "Some details need a second look.",
                mapOf("fields" to errors),
            )
        }
        return cleaned
    }

    private fun coerce(def: FieldDef, raw: Any): Any = when (DataType.from(def.dataType)) {
        DataType.TEXT -> raw.toString().trim().also {
            if (it.length > MAX_TEXT) throw FieldError("${def.label} is too long")
        }

        DataType.NUMBER, DataType.MONEY, DataType.PERCENT -> {
            val n = decimal(raw) ?: throw FieldError("${def.label} should be a number")
            if (n.signum() < 0) throw FieldError("${def.label} can't be negative")
            if (DataType.from(def.dataType) == DataType.PERCENT && n > BigDecimal(100)) {
                throw FieldError("${def.label} can't be more than 100%")
            }
            // Kept as a plain string in jsonb: jsonb's number type is a double,
            // and money must never round-trip through a float.
            n.stripTrailingZeros().toPlainString()
        }

        DataType.DATE -> try {
            LocalDate.parse(raw.toString().trim()).toString()
        } catch (_: DateTimeParseException) {
            throw FieldError("${def.label} should be a date like 2026-08-03")
        }

        DataType.BOOL -> when (raw) {
            is Boolean -> raw
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> throw FieldError("${def.label} should be yes or no")
        }

        DataType.SELECT -> {
            val value = raw.toString().trim()
            val allowed = def.options.orEmpty()
            if (allowed.none { it.value == value }) {
                throw FieldError(
                    "Choose one of: ${allowed.joinToString { it.label }}",
                )
            }
            value
        }

        null -> throw FieldError("${def.label} has an unsupported type")
    }

    private fun decimal(raw: Any): BigDecimal? = when (raw) {
        is BigDecimal -> raw
        is Number -> BigDecimal(raw.toString())
        // Tolerates what people paste: "1,00,000", "₹1,00,000", "6.3 g".
        is String -> raw.replace(Regex("[,₹\\s]"), "")
            .takeIf { it.isNotEmpty() }
            ?.toBigDecimalOrNull()
        else -> null
    }

    private fun CustomFieldRow.toFieldDef() = FieldDef(
        key = key, label = label, dataType = dataType, group = "more",
        sort = sort, required = required, unit = unit, options = options,
    )

    private class FieldError(message: String) : RuntimeException(message)

    private companion object {
        const val MAX_TEXT = 2000
    }
}

/** Exposed for tests and for the custom-field creation path. */
fun customFieldOptions(options: List<FieldOption>?): List<FieldOption>? = options
