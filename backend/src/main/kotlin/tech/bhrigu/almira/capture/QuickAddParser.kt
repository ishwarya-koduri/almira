package tech.bhrigu.almira.capture

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * Turns "1L gold 6.3g at ICICI Aug 3" into editable chips.
 *
 * Pure: text and a vocabulary in, a parse out. No database, no clock beyond the
 * `today` it is given — so every case is reproducible from a string, and the
 * ambiguous ones can be pinned rather than argued about.
 *
 * The governing rule is that **nothing is ever saved from a parse**. The result
 * is a proposal: each field carries the text it came from so the UI can show it
 * as a chip the person can edit or drop, and whatever could not be read is
 * handed back rather than guessed at (docs/10 Epic 2.4.1). Getting this wrong in
 * the other direction — silently inventing a date or an amount — is how a
 * registry quietly fills with fiction.
 */
object QuickAddParser {

    data class TypeVocabulary(
        val typeId: UUID,
        val code: String,
        val label: String,
        /** Lowercase words that should select this type. */
        val keywords: Set<String>,
    )

    data class InstitutionVocabulary(val id: UUID, val name: String)

    data class Vocabulary(
        val types: List<TypeVocabulary>,
        val institutions: List<InstitutionVocabulary>,
    )

    data class Field(
        val key: String,
        val label: String,
        /** The value to submit. */
        val value: String,
        /** How it should read on the chip. */
        val display: String,
        /** The words it was read from, so the chip can point at them. */
        val sourceText: String,
        val confidence: String,
    )

    data class Parsed(
        val input: String,
        val fields: List<Field>,
        /** Words nothing was made of. Shown, not swallowed. */
        val unparsed: String,
        val note: String?,
    )

    fun parse(input: String, vocabulary: Vocabulary, today: LocalDate = LocalDate.now()): Parsed {
        val text = input.trim()
        if (text.isEmpty()) {
            return Parsed(input, emptyList(), "", "Type something like “1L gold 6.3g at ICICI”.")
        }

        val fields = mutableListOf<Field>()
        val consumed = mutableListOf<IntRange>()

        // Order is most-specific-first, and both steps below it exist because of
        // a case that got this wrong:
        //
        //   quantity before amount — "6.3g" would otherwise read as the number
        //   6.3 and leave a stray "g";
        //
        //   date before amount — in "3 August 2024" the year is a four-digit
        //   number, and an amount parser that ran first claimed it as ₹2,024 and
        //   left the date unreadable.
        parseQuantity(text)?.let { (field, range) -> fields += field; consumed += range }
        parseDate(text, today, consumed)?.let { (field, range) -> fields += field; consumed += range }
        parseAmount(text, consumed)?.let { (field, range) -> fields += field; consumed += range }

        // Types and institutions are drawn from the same words — "Angel
        // Investment" the custom type and "Angel One" the broker both start with
        // "angel" — so neither can simply go first. Both are read, the longer and
        // therefore more specific match keeps the words it matched, and the other
        // is read again from what is left.
        var type = parseType(text, vocabulary, consumed)
        var institution = parseInstitution(text, vocabulary, consumed)
        if (type != null && institution != null && overlaps(institution.second, type.second)) {
            if (type.first.sourceText.length >= institution.first.sourceText.length) {
                institution = parseInstitution(text, vocabulary, consumed + type.second)
            } else {
                type = parseType(text, vocabulary, consumed + listOf(institution.second))
            }
        }
        type?.let { (field, ranges) -> fields += field; consumed += ranges }
        institution?.let { (field, range) -> fields += field; consumed += range }

        val leftover = remainder(text, consumed)
        // Whatever is left is the most likely name for the thing, but it is a
        // guess, so it is offered at low confidence rather than asserted.
        if (leftover.isNotBlank() && fields.none { it.key == "title" }) {
            fields += Field(
                key = "title", label = "Name", value = leftover, display = leftover,
                sourceText = leftover, confidence = "low",
            )
        }

        return Parsed(
            input = input,
            fields = fields.sortedBy { FIELD_ORDER.indexOf(it.key).takeIf { i -> i >= 0 } ?: 99 },
            unparsed = if (fields.any { it.key == "title" }) "" else leftover,
            note = when {
                fields.isEmpty() -> "We couldn't make anything of that — try “1L gold at ICICI”."
                fields.none { it.key == "typeId" } -> "Pick a type and we'll fill in the rest."
                else -> null
            },
        )
    }

    // --- amounts --------------------------------------------------------------

    /**
     * Indian shorthand, which is how people actually write amounts here: 1L is a
     * lakh, 2.5Cr is two and a half crore, 50k is fifty thousand. A parser that
     * only understood "100000" would be refusing the way its users think.
     */
    private val AMOUNT = Regex(
        """(?<![\w.])(?:₹\s*)?(\d+(?:[,\d]*)?(?:\.\d+)?)\s*(l|lac|lakh|lakhs|cr|crore|crores|k|thousand)?(?![\w])""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseAmount(text: String, consumed: List<IntRange>): Pair<Field, IntRange>? {
        for (match in AMOUNT.findAll(text)) {
            if (overlaps(match.range, consumed)) continue
            val digits = match.groupValues[1].replace(",", "")
            val number = digits.toBigDecimalOrNull() ?: continue
            val suffix = match.groupValues[2].lowercase()

            // A bare small number with no suffix is far more likely to be a
            // quantity or a day than an amount, so it is left for something else.
            if (suffix.isEmpty() && number < BigDecimal(1000)) continue

            val multiplier = when {
                suffix.startsWith("cr") -> BigDecimal(10_000_000)
                suffix.startsWith("l") -> BigDecimal(100_000)
                suffix == "k" || suffix == "thousand" -> BigDecimal(1_000)
                else -> BigDecimal.ONE
            }
            val amount = number.multiply(multiplier).stripTrailingZeros()
            return Field(
                key = "investedAmount",
                label = "Amount",
                value = amount.toPlainString(),
                display = "₹" + tech.bhrigu.almira.common.IndianNumbers.group(amount),
                sourceText = match.value.trim(),
                confidence = if (suffix.isEmpty()) "medium" else "high",
            ) to match.range
        }
        return null
    }

    // --- quantity -------------------------------------------------------------

    private val QUANTITY = Regex(
        """(?<![\w.])(\d+(?:\.\d+)?)\s*(g|gm|gms|gram|grams|kg|units?|shares?|sq\s?ft)(?![\w])""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseQuantity(text: String): Pair<Field, IntRange>? {
        val match = QUANTITY.find(text) ?: return null
        val number = match.groupValues[1].toBigDecimalOrNull() ?: return null
        val rawUnit = match.groupValues[2].lowercase().replace(" ", "")
        val unit = when {
            rawUnit.startsWith("kg") -> "kg"
            rawUnit.startsWith("g") -> "g"
            rawUnit.startsWith("share") -> "shares"
            rawUnit.startsWith("unit") -> "units"
            else -> rawUnit
        }
        return Field(
            key = "quantity",
            label = "Quantity",
            value = number.stripTrailingZeros().toPlainString(),
            display = "${number.stripTrailingZeros().toPlainString()} $unit",
            sourceText = match.value.trim(),
            confidence = "high",
        ) to match.range
    }

    // --- dates ----------------------------------------------------------------

    private val MONTHS = (1..12).associate { month ->
        java.time.Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() to month
    }

    private fun parseDate(
        text: String,
        today: LocalDate,
        consumed: List<IntRange>,
    ): Pair<Field, IntRange>? {
        Regex("""\b(today|yesterday)\b""", RegexOption.IGNORE_CASE).find(text)
            ?.takeIf { !overlaps(it.range, consumed) }
            ?.let { match ->
                val date = if (match.value.lowercase() == "today") today else today.minusDays(1)
                return dateField(date, match.value, "high") to match.range
            }

        // "3 Aug", "Aug 3", "3 August 2024" — the forms people actually type.
        val monthNames = MONTHS.keys.joinToString("|") { "$it[a-z]*" }
        val dayFirst = Regex("""\b(\d{1,2})\s+($monthNames)\.?(?:\s+(\d{4}|\d{2}))?\b""", RegexOption.IGNORE_CASE)
        val monthFirst = Regex("""\b($monthNames)\.?\s+(\d{1,2})(?:\s*,?\s*(\d{4}|\d{2}))?\b""", RegexOption.IGNORE_CASE)

        for ((regex, dayIndex, monthIndex) in listOf(
            Triple(dayFirst, 1, 2), Triple(monthFirst, 2, 1),
        )) {
            val match = regex.find(text)?.takeIf { !overlaps(it.range, consumed) } ?: continue
            val day = match.groupValues[dayIndex].toIntOrNull() ?: continue
            val month = MONTHS.entries.firstOrNull {
                match.groupValues[monthIndex].lowercase().startsWith(it.key)
            }?.value ?: continue
            val year = match.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }

            val date = resolveDate(day, month, year, today) ?: continue
            return dateField(date, match.value, if (year != null) "high" else "medium") to match.range
        }

        // 3/8/2024 — day first, because that is how it is written here.
        Regex("""\b(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})\b""").find(text)
            ?.takeIf { !overlaps(it.range, consumed) }
            ?.let { match ->
                val day = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt()
                val year = match.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
                resolveDate(day, month, year, today)?.let {
                    return dateField(it, match.value, "high") to match.range
                }
            }

        return null
    }

    /**
     * A date without a year means the most recent one that has already happened.
     *
     * People record what they did, not what they will do. Reading "3 Aug" typed
     * in September as next year's August would file a purchase in the future,
     * where it would sit outside every report until it arrived.
     */
    private fun resolveDate(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching {
            if (year != null) {
                LocalDate.of(year, month, day)
            } else {
                val thisYear = LocalDate.of(today.year, month, day)
                if (thisYear.isAfter(today)) thisYear.minusYears(1) else thisYear
            }
        }.getOrNull()
    }

    private fun dateField(date: LocalDate, source: String, confidence: String) = Field(
        key = "startDate",
        label = "Date",
        value = date.toString(),
        display = date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)),
        sourceText = source.trim(),
        confidence = confidence,
    )

    // --- institution and type -------------------------------------------------

    /**
     * Two different matches, deliberately ranked differently.
     *
     * A full name that appears in the text wins on length, so "State Bank of
     * India" beats "India". But when only the first word matched — someone typed
     * "at ICICI" — the *shortest* full name wins, because a household with both
     * "ICICI Bank" and "ICICI Prudential Mutual Fund" means the bank when they
     * say ICICI. Ranking those by length too silently filed gold purchases at a
     * fund house.
     */
    private fun parseInstitution(
        text: String,
        vocabulary: Vocabulary,
        consumed: List<IntRange>,
    ): Pair<Field, IntRange>? {
        val lower = text.lowercase()

        vocabulary.institutions
            .sortedByDescending { it.name.length }
            .forEach { institution ->
                val needle = institution.name.lowercase()
                val at = lower.indexOf(needle)
                if (at >= 0) {
                    val range = at until (at + needle.length)
                    if (!overlaps(range, consumed)) {
                        return institutionField(institution, text, range, "high")
                    }
                }
            }

        return vocabulary.institutions
            .sortedBy { it.name.length }
            .firstNotNullOfOrNull { institution ->
                val firstWord = institution.name.lowercase().substringBefore(' ')
                if (firstWord.length < 4) return@firstNotNullOfOrNull null
                Regex("""\b${Regex.escape(firstWord)}\b""").find(lower)
                    ?.takeIf { !overlaps(it.range, consumed) }
                    ?.let { institutionField(institution, text, it.range, "medium") }
            }
    }

    private fun institutionField(
        institution: InstitutionVocabulary,
        text: String,
        range: IntRange,
        confidence: String,
    ) = Field(
        key = "institutionId", label = "Where",
        value = institution.id.toString(), display = institution.name,
        sourceText = text.substring(range), confidence = confidence,
    ) to range

    /**
     * Returns every range that pointed at the chosen type, not just the first.
     *
     * "gold wedding coins" contains two words for the same thing. Consuming only
     * one leaves the other in the leftover text, where it becomes part of the
     * suggested name — so the chip reads "gold wedding" instead of "wedding".
     */
    private fun parseType(
        text: String,
        vocabulary: Vocabulary,
        consumed: List<IntRange>,
    ): Pair<Field, List<IntRange>>? {
        val lower = text.lowercase()
        val chosen = vocabulary.types
            .flatMap { type -> type.keywords.map { type to it } }
            .sortedByDescending { it.second.length }
            .firstNotNullOfOrNull { (type, keyword) ->
                Regex("""\b${Regex.escape(keyword)}\b""").find(lower)
                    ?.takeIf { !overlaps(it.range, consumed) }
                    ?.let { type to it }
            } ?: return null

        val (type, firstMatch) = chosen
        val allRanges = type.keywords
            .flatMap { keyword ->
                Regex("""\b${Regex.escape(keyword)}\b""").findAll(lower).map { it.range }
            }
            .filterNot { overlaps(it, consumed) }
            .distinct()

        return Field(
            key = "typeId", label = "Type",
            value = type.typeId.toString(), display = type.label,
            sourceText = text.substring(firstMatch.range), confidence = "high",
        ) to allRanges
    }

    // --- leftovers ------------------------------------------------------------

    private fun overlaps(range: IntRange, consumed: List<IntRange>) =
        consumed.any { it.first <= range.last && range.first <= it.last }

    private fun remainder(text: String, consumed: List<IntRange>): String {
        val kept = text.indices.filterNot { index -> consumed.any { index in it } }
        return kept.map { text[it] }.joinToString("")
            // Connectives left behind once their neighbours are consumed read as
            // noise, not as a name.
            .replace(Regex("""\b(at|in|on|from|of|the|a|an|for|with)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""[\s,]+"""), " ")
            .trim()
    }

    private val FIELD_ORDER = listOf(
        "typeId", "title", "investedAmount", "quantity", "institutionId", "startDate",
    )
}
