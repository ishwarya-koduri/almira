package tech.almira.capture

import tech.almira.common.IndianNumbers
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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
 *
 * The second rule came from a real sentence. "HDFC FD 3 lakh 7.1% matures 5
 * March 2028 nominee Aarav" used to come back with "7.1% matures nominee Aarav"
 * in the name: everything the parser had no reader for fell through to the
 * title. So the rate, the maturity and the nominee have readers now, and words
 * that still mean nothing are handed back as "didn't understand" rather than
 * folded into the name (docs/03 §3).
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
        /** Where the source words start in the input, so the client can underline them. Null when not from the input. */
        val start: Int? = null,
        /** Where they end, exclusive. */
        val end: Int? = null,
        /** A second line for the chip: an amount in words, so a missing zero is caught before it is saved. */
        val hint: String? = null,
    )

    /** A run of words in the input, with where it is. */
    data class WordSpan(val text: String, val start: Int, val end: Int)

    data class Parsed(
        val input: String,
        val fields: List<Field>,
        /** Words nothing was made of. Shown, not swallowed — and never the name. */
        val unparsed: String,
        val note: String?,
        /** The same words as [unparsed], one entry per run, with positions: the "Didn't understand" chips. */
        val notUnderstood: List<WordSpan> = emptyList(),
    )

    fun parse(input: String, vocabulary: Vocabulary, today: LocalDate = LocalDate.now()): Parsed {
        val text = input.trim()
        if (text.isEmpty()) {
            return Parsed(input, emptyList(), "", "Type something like “1L gold 6.3g at ICICI”.")
        }
        // Positions are reported against what was sent, not the trimmed copy.
        val offset = input.indexOf(text)

        val found = mutableListOf<Found>()
        val consumed = mutableListOf<IntRange>()
        fun take(result: Found?) {
            if (result == null) return
            found += result
            consumed += result.ranges
        }

        // Order is most-specific-first, and each step above the amount exists
        // because of a case that got this wrong:
        //
        //   quantity before amount — "6.3g" would otherwise read as the number
        //   6.3 and leave a stray "g";
        //
        //   rate before amount — "8.5% p.a." left "p.a." behind as the name;
        //
        //   maturity before the plain date — "matures 5 March 2028" read as a
        //   start date filed a purchase that had not happened yet;
        //
        //   dates before amount — in "3 August 2024" the year is a four-digit
        //   number, and an amount parser that ran first claimed it as ₹2,024 and
        //   left the date unreadable;
        //
        //   nominee before type and institution — in "nominee Angel" Angel is a
        //   person, not the broker.
        take(parseQuantity(text, consumed))
        take(parseRate(text, consumed))
        take(parseMaturity(text, today, consumed))
        take(parseDate(text, today, consumed))
        take(parseNominee(text, vocabulary, consumed))
        take(parseAmount(text, consumed))

        // Types and institutions are drawn from the same words — "Angel
        // Investment" the custom type and "Angel One" the broker both start with
        // "angel" — so neither can simply go first. Both are read, the longer and
        // therefore more specific match keeps the words it matched, and the other
        // is read again from what is left.
        val typeFirst = parseType(text, vocabulary, consumed)
        val institutionFirst = parseInstitution(text, vocabulary, consumed)
        var type = typeFirst
        var institution = institutionFirst
        if (typeFirst != null && institutionFirst != null &&
            institutionFirst.ranges.any { overlaps(it, typeFirst.ranges) }
        ) {
            if (typeFirst.fields.first().sourceText.length >= institutionFirst.fields.first().sourceText.length) {
                institution = parseInstitution(text, vocabulary, consumed + typeFirst.ranges)
            } else {
                type = parseType(text, vocabulary, consumed + institutionFirst.ranges)
            }
        }
        take(type)
        take(institution)

        val fields = found.flatMap { it.fields }.toMutableList()
        val (name, leftovers) = nameAndLeftovers(text, found, consumed)
        // The words beside the type are the most likely name for the thing, but
        // it is a guess, so it is offered at low confidence rather than asserted.
        if (name != null) {
            fields += Field(
                key = "title", label = "Name", value = name.text, display = name.text,
                sourceText = name.text, confidence = "low", start = name.start, end = name.end,
            )
        }

        return Parsed(
            input = input,
            fields = fields
                .map { if (it.start == null || it.end == null) it else it.copy(start = it.start + offset, end = it.end + offset) }
                .sortedBy { FIELD_ORDER.indexOf(it.key).takeIf { i -> i >= 0 } ?: 99 },
            unparsed = leftovers.joinToString(" ") { it.text },
            note = when {
                fields.isEmpty() -> "We couldn't make anything of that. Try “1L gold at ICICI”."
                fields.none { it.key == "typeId" } -> "Pick a type and we'll fill in the rest."
                else -> null
            },
            notUnderstood = leftovers.map { it.copy(start = it.start + offset, end = it.end + offset) },
        )
    }

    /**
     * What one reader found: the fields it proposes and every stretch of the
     * input it used. [isFact] marks readers of facts — an amount, a rate, a
     * date, a nominee — as opposed to the words that say what the thing is,
     * which is what decides where a name has to stop (see [nameAndLeftovers]).
     */
    private data class Found(val fields: List<Field>, val ranges: List<IntRange>, val isFact: Boolean)

    private fun found(field: Field, range: IntRange, isFact: Boolean = true) =
        Found(listOf(field.copy(start = range.first, end = range.last + 1)), listOf(range), isFact)

    // --- amounts --------------------------------------------------------------

    /**
     * Indian amounts, the way they are written and said here: 1L is a lakh, 2.5Cr
     * is two and a half crore, 50k is fifty thousand — and so are "three lakh",
     * "one crore twenty lakh", "1 lakh 50 thousand", "dedh lakh" and "Rs.
     * 3,00,000/-". A parser that only understood "100000" would be refusing the
     * way its users think.
     *
     * Read over word tokens rather than with one regular expression, because the
     * spoken forms are sequences — a number and a scale, then perhaps another —
     * and a pattern that spelled every sequence out stopped being readable long
     * before it was complete.
     */
    private fun parseAmount(text: String, consumed: List<IntRange>): Found? {
        val tokens = TOKEN.findAll(text)
            .filterNot { overlaps(it.range, consumed) }
            .map { Token(it.value.lowercase(), it.range) }
            .toList()
        return tokens.indices.firstNotNullOfOrNull { readAmountAt(text, tokens, it) }
    }

    private data class Token(val word: String, val range: IntRange)

    private fun readAmountAt(text: String, tokens: List<Token>, from: Int): Found? {
        var i = from
        var currency = false
        if (tokens[i].word in LEADING_CURRENCY) {
            if (!adjacent(text, tokens, i)) return null
            currency = true
            i++
        }

        var total = BigDecimal.ZERO
        var groups = 0
        var scaled = false
        var sawDigits = false
        var last = i - 1

        while (i < tokens.size) {
            if (i > from && !adjacent(text, tokens, i - 1)) break
            val (number, afterNumber) = readNumber(text, tokens, i) ?: break
            var value = number
            var j = afterNumber
            if (tokens[i].word.first().isDigit()) sawDigits = true

            // "two and a half lakh"
            if (j + 2 < tokens.size && tokens[j].word == "and" && tokens[j + 1].word == "a" &&
                tokens[j + 2].word == "half" && (j - 1..j + 1).all { adjacent(text, tokens, it) }
            ) {
                value += BigDecimal("0.5")
                j += 3
            }

            val scale = tokens.getOrNull(j)
                ?.takeIf { adjacent(text, tokens, j - 1) && !glued(text, it.range.last + 1) }
                ?.let { SCALES[it.word] }
            if (scale == null) {
                // An unscaled number only ever stands alone: "3 lakh 2028" is an
                // amount and a year, not ₹3,02,028.
                if (groups == 0) {
                    total = value
                    groups = 1
                    last = j - 1
                }
                break
            }
            total += value.multiply(scale)
            groups++
            scaled = true
            last = j
            i = j + 1
        }
        if (groups == 0 || last < from) return null

        // "3 lakh rupees", "5000 Rs"
        tokens.getOrNull(last + 1)
            ?.takeIf { it.word in TRAILING_CURRENCY && adjacent(text, tokens, last) }
            ?.let { currency = true; last += 1 }

        // A bare small number with no scale is far more likely a quantity, a
        // tenure or a day than an amount, so it is left for something else; and
        // a number said only in words, with nothing saying it is money, is just
        // as likely "five years".
        if (!scaled && !currency && (!sawDigits || total < BigDecimal(1000))) return null
        if (total.signum() <= 0) return null

        val start = tokens[from].range.first
        var end = tokens[last].range.last
        if (text.startsWith("/-", end + 1)) end += 2

        val amount = total.stripTrailingZeros()
        return found(
            Field(
                key = "investedAmount",
                label = "Amount",
                value = amount.toPlainString(),
                display = "₹" + IndianNumbers.group(amount),
                sourceText = text.substring(start, end + 1),
                confidence = if (scaled || currency) "high" else "medium",
                hint = IndianNumbers.words(amount) + " Rupees",
            ),
            start..end,
        )
    }

    /** Tokens [i] and [i]+1 are separated only by a space, a hyphen, a dot, or nothing ("1L"). */
    private fun adjacent(text: String, tokens: List<Token>, i: Int): Boolean {
        if (i < 0) return true
        if (i + 1 >= tokens.size) return false
        return GAP.matches(text.substring(tokens[i].range.last + 1, tokens[i + 1].range.first))
    }

    /** A letter or digit right against a position: the token there is part of a code, not a word. */
    private fun glued(text: String, at: Int) = at in text.indices && text[at].isLetterOrDigit()

    /** A number at [i], in digits or words, and the index after it. */
    private fun readNumber(text: String, tokens: List<Token>, i: Int): Pair<BigDecimal, Int>? {
        val word = tokens[i].word

        if (word.first().isDigit()) {
            // "INF846K01WO1" is an ISIN, not ₹8,46,000.
            if (glued(text, tokens[i].range.first - 1)) return null
            return word.replace(",", "").toBigDecimalOrNull()?.let { it to i + 1 }
        }
        // "a lakh", "dedh lakh" — only ever in front of a scale, because "a" on
        // its own is an article.
        FRACTION_WORDS[word]?.let { fraction ->
            val nextIsScale = tokens.getOrNull(i + 1)?.let { SCALES.containsKey(it.word) } == true &&
                adjacent(text, tokens, i)
            return if (nextIsScale) fraction to i + 1 else null
        }

        var j = i
        fun small(): Int? {
            val w = tokens.getOrNull(j)?.takeIf { j == i || adjacent(text, tokens, j - 1) }?.word ?: return null
            ONES_WORDS[w]?.let { j++; return it }
            val tens = TENS_WORDS[w] ?: return null
            j++
            val unit = tokens.getOrNull(j)?.takeIf { adjacent(text, tokens, j - 1) }?.let { ONES_WORDS[it.word] }
            if (unit != null && unit in 1..9) { j++; return tens + unit }
            return tens
        }
        var value = small() ?: return null
        if (tokens.getOrNull(j)?.word == "hundred" && adjacent(text, tokens, j - 1)) {
            value *= 100
            j++
            val afterHundred = j
            if (tokens.getOrNull(j)?.word == "and" && adjacent(text, tokens, j - 1)) j++
            val rest = small()
            if (rest != null) value += rest else j = afterHundred
        }
        return BigDecimal(value) to j
    }

    // --- quantity -------------------------------------------------------------

    private val QUANTITY = Regex(
        """(?<![\w.])(\d+(?:\.\d+)?)\s*(g|gm|gms|gram|grams|kg|units?|shares?|sq\s?ft)(?![\w])""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseQuantity(text: String, consumed: List<IntRange>): Found? {
        val match = QUANTITY.findAll(text).firstOrNull { !overlaps(it.range, consumed) } ?: return null
        val number = match.groupValues[1].toBigDecimalOrNull() ?: return null
        val rawUnit = match.groupValues[2].lowercase().replace(" ", "")
        val unit = when {
            rawUnit.startsWith("kg") -> "kg"
            rawUnit.startsWith("g") -> "g"
            rawUnit.startsWith("share") -> "shares"
            rawUnit.startsWith("unit") -> "units"
            else -> rawUnit
        }
        return found(
            Field(
                key = "quantity",
                label = "Quantity",
                value = number.stripTrailingZeros().toPlainString(),
                display = "${number.stripTrailingZeros().toPlainString()} $unit",
                sourceText = match.value.trim(),
                confidence = "high",
            ),
            match.range,
        )
    }

    // --- interest rate --------------------------------------------------------

    /**
     * "7.1%", "@ 7.25 % p.a.", "8 percent". Only with the sign or the word: a
     * bare "7.1" is as likely grams or a NAV.
     */
    private val RATE = Regex(
        """(?:@\s*)?(?<![\w.])(\d{1,2}(?:\.\d{1,3})?)\s*(?:%|percent\b|pc\b)""" +
            """(?:\s*(?:p\.?\s?a\b\.?|per\s+annum\b|a\s+year\b|yearly\b|interest\b))?""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseRate(text: String, consumed: List<IntRange>): Found? {
        val match = RATE.findAll(text).firstOrNull { !overlaps(it.range, consumed) } ?: return null
        val rate = match.groupValues[1].toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val shown = rate.stripTrailingZeros().toPlainString()
        return found(
            Field(
                // interest_rate is what deposits, small savings and loans given
                // call it; the form carries it to a bond's coupon where that is
                // the field the type has.
                key = "attributes.interest_rate",
                label = "Rate",
                value = shown,
                display = "$shown% a year",
                sourceText = match.value.trim(),
                confidence = "high",
            ),
            match.range,
        )
    }

    // --- dates ----------------------------------------------------------------

    private val MONTHS = (1..12).associate { month ->
        java.time.Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() to month
    }

    private val MONTH_NAMES = MONTHS.keys.joinToString("|") { "$it[a-z]*" }

    /** A two-digit "year" followed by a scale is an amount: "3 Aug 25 lakh". */
    private const val YEAR = """(\d{4}|\d{2}(?!\s*(?:l|lac|lakhs?|cr|crores?|k|thousand)\b))(?!\d)"""

    // "3 Aug", "3rd Aug", "Aug 3", "3 August 2024" — the forms people actually type.
    private val DAY_FIRST = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?\s+($MONTH_NAMES)\.?(?:(?:\s*,\s*|\s+)$YEAR)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MONTH_FIRST = Regex(
        """\b($MONTH_NAMES)\.?\s+(\d{1,2})(?!\d)(?:st|nd|rd|th)?(?:(?:\s*,\s*|\s+)$YEAR)?\b""",
        RegexOption.IGNORE_CASE,
    )

    // 3/8/2024 — day first, because that is how it is written here.
    private val NUMERIC = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4}|\d{2})\b""")

    private val DATE_FORMS = listOf(DAY_FIRST, MONTH_FIRST, NUMERIC)

    private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    private fun parseDate(text: String, today: LocalDate, consumed: List<IntRange>): Found? {
        Regex("""\b(today|yesterday)\b""", RegexOption.IGNORE_CASE).findAll(text)
            .firstOrNull { !overlaps(it.range, consumed) }
            ?.let { match ->
                val date = if (match.value.lowercase() == "today") today else today.minusDays(1)
                return found(dateField("startDate", "Date", date, match.value, "high"), match.range)
            }

        // The earliest date in the sentence, whichever way round it was written.
        return DATE_FORMS
            .flatMap { form -> form.findAll(text).filterNot { overlaps(it.range, consumed) }.map { form to it }.toList() }
            .sortedBy { (_, match) -> match.range.first }
            .firstNotNullOfOrNull { (form, match) ->
                readDate(form, match, today, future = false)?.let { (date, confidence) ->
                    found(dateField("startDate", "Date", date, match.value, confidence), match.range)
                }
            }
    }

    /**
     * "matures 5 March 2028", "maturity on 05/03/2028", "due 12 Jan".
     *
     * The word is what makes it a maturity, and a date has to follow it straight
     * away. "matures in March 2028" names no day, and picking one would be
     * inventing it — so those words are handed back, for the form to ask.
     */
    private val MATURITY_WORD = Regex(
        """\b(?:matures?|maturing|maturity(?:\s+date)?|due|till|until)\b(?:\s*(?:on\b|date\b|:|-))?\s*""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseMaturity(text: String, today: LocalDate, consumed: List<IntRange>): Found? {
        for (cue in MATURITY_WORD.findAll(text)) {
            if (overlaps(cue.range, consumed)) continue
            val at = cue.range.last + 1
            for (form in DATE_FORMS) {
                val match = form.find(text, at)?.takeIf { it.range.first == at } ?: continue
                if (overlaps(match.range, consumed)) continue
                val (date, confidence) = readDate(form, match, today, future = true) ?: continue
                val range = cue.range.first..match.range.last
                return found(dateField("maturityDate", "Matures", date, text.substring(range), confidence), range)
            }
        }
        return null
    }

    private fun readDate(form: Regex, match: MatchResult, today: LocalDate, future: Boolean): Pair<LocalDate, String>? {
        val groups = match.groupValues
        if (form === NUMERIC) {
            val year = groups[3].toInt().let { if (it < 100) 2000 + it else it }
            return resolveDate(groups[1].toInt(), groups[2].toInt(), year, today, future)?.let { it to "high" }
        }
        val (dayIndex, monthIndex) = if (form === DAY_FIRST) 1 to 2 else 2 to 1
        val day = groups[dayIndex].toIntOrNull() ?: return null
        val month = MONTHS.entries.firstOrNull { groups[monthIndex].lowercase().startsWith(it.key) }?.value
            ?: return null
        val year = groups[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        return resolveDate(day, month, year, today, future)?.let { it to if (year != null) "high" else "medium" }
    }

    /**
     * A date without a year means the nearest one on the side it belongs to.
     *
     * People record what they did, not what they will do. Reading "3 Aug" typed
     * in September as next year's August would file a purchase in the future,
     * where it would sit outside every report until it arrived. A maturity is the
     * other way round: "matures 12 Jan" is the coming 12 January, not the last.
     */
    private fun resolveDate(day: Int, month: Int, year: Int?, today: LocalDate, future: Boolean): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        return runCatching {
            if (year != null) {
                LocalDate.of(year, month, day)
            } else {
                val thisYear = LocalDate.of(today.year, month, day)
                when {
                    future && thisYear.isBefore(today) -> thisYear.plusYears(1)
                    !future && thisYear.isAfter(today) -> thisYear.minusYears(1)
                    else -> thisYear
                }
            }
        }.getOrNull()
    }

    private fun dateField(key: String, label: String, date: LocalDate, source: String, confidence: String) = Field(
        key = key,
        label = label,
        value = date.toString(),
        display = date.format(DISPLAY_DATE),
        sourceText = source.trim(),
        confidence = confidence,
    )

    // --- nominee --------------------------------------------------------------

    private val NOMINEE_WORD = Regex(
        """\b(?:nominee|nominated|nomination)(?:\s+(?:is|to))?\s*[:\-]?\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_WORD = Regex("""\p{L}[\p{L}'.]*""")

    /**
     * "nominee Aarav", "nominee: wife Priya", "nominee is Lakshmi Devi".
     *
     * The name is the first word after the cue and the capitalised words straight
     * after it, stopping at anything plainly not a name: an acronym like "HDFC",
     * a type word, an institution, a month. A lower-case first word is taken
     * alone, because in "nominee aarav fd sbi" nothing says where a name ends.
     *
     * A relationship said first is its own chip — it is what the nominee form
     * asks for next, and "wife" is nobody's name.
     */
    private fun parseNominee(text: String, vocabulary: Vocabulary, consumed: List<IntRange>): Found? {
        val stopWords = buildSet {
            vocabulary.types.forEach { type -> type.keywords.forEach { addAll(it.split(' ')) } }
            vocabulary.institutions.forEach { add(it.name.lowercase().substringBefore(' ')) }
            addAll(MONTHS.keys)
            (1..12).forEach { add(java.time.Month.of(it).getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase()) }
            addAll(CONNECTIVES)
            addAll(SCALES.keys)
            addAll(listOf("matures", "maturity", "due", "nominee", "rupees", "today", "yesterday"))
        }

        for (cue in NOMINEE_WORD.findAll(text)) {
            if (overlaps(cue.range, consumed)) continue
            var at = cue.range.last + 1
            val words = mutableListOf<MatchResult>()
            var relationship: MatchResult? = null

            while (words.size < 3) {
                val word = NAME_WORD.find(text, at)
                    ?.takeIf { text.substring(at, it.range.first).isBlank() && !overlaps(it.range, consumed) }
                    ?: break
                val lower = word.value.lowercase().trimEnd('.')
                if (words.isEmpty() && relationship == null && lower in RELATIONSHIPS) {
                    relationship = word
                    at = word.range.last + 1
                    continue
                }
                if (lower in stopWords) break
                val isAcronym = word.value.count { it.isLetter() } >= 2 && word.value.none { it.isLowerCase() }
                if (isAcronym) break
                if (words.isNotEmpty() && !(words.first().value.first().isUpperCase() && word.value.first().isUpperCase())) break
                words += word
                at = word.range.last + 1
            }
            if (words.isEmpty()) continue

            val nameEnd = words.last().range.last + 1 - words.last().value.length +
                words.last().value.trimEnd('.').length
            val name = text.substring(words.first().range.first, nameEnd)
            val range = cue.range.first until nameEnd
            val fields = mutableListOf(
                Field(
                    key = "nomineeName", label = "Nominee", value = name, display = name,
                    sourceText = text.substring(range).trim(),
                    confidence = if (name.first().isUpperCase()) "high" else "medium",
                    start = range.first, end = range.last + 1,
                ),
            )
            relationship?.let { rel ->
                val value = rel.value.lowercase().trimEnd('.')
                fields += Field(
                    key = "nomineeRelationship", label = "Relationship", value = value, display = value,
                    sourceText = rel.value, confidence = "high",
                    start = rel.range.first, end = rel.range.first + value.length,
                )
            }
            return Found(fields, listOf(range), isFact = true)
        }
        return null
    }

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
    ): Found? {
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
                Regex("""\b${Regex.escape(firstWord)}\b""").findAll(lower)
                    .firstOrNull { !overlaps(it.range, consumed) }
                    ?.let { institutionField(institution, text, it.range, "medium") }
            }
    }

    private fun institutionField(
        institution: InstitutionVocabulary,
        text: String,
        range: IntRange,
        confidence: String,
    ) = found(
        Field(
            key = "institutionId", label = "Where",
            value = institution.id.toString(), display = institution.name,
            sourceText = text.substring(range), confidence = confidence,
        ),
        range,
        isFact = false,
    )

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
    ): Found? {
        val lower = text.lowercase()
        val chosen = vocabulary.types
            .flatMap { type -> type.keywords.map { type to it } }
            .sortedByDescending { it.second.length }
            .firstNotNullOfOrNull { (type, keyword) ->
                Regex("""\b${Regex.escape(keyword)}\b""").findAll(lower)
                    .firstOrNull { !overlaps(it.range, consumed) }
                    ?.let { type to it }
            } ?: return null

        val (type, firstMatch) = chosen
        val allRanges = type.keywords
            .flatMap { keyword ->
                Regex("""\b${Regex.escape(keyword)}\b""").findAll(lower).map { it.range }
            }
            .filterNot { overlaps(it, consumed) }
            .distinct()

        return Found(
            listOf(
                Field(
                    key = "typeId", label = "Type",
                    value = type.typeId.toString(), display = type.label,
                    sourceText = text.substring(firstMatch.range), confidence = "high",
                    start = firstMatch.range.first, end = firstMatch.range.last + 1,
                ),
            ),
            allRanges,
            isFact = false,
        )
    }

    // --- leftovers ------------------------------------------------------------

    private fun overlaps(range: IntRange, consumed: List<IntRange>) =
        consumed.any { it.first <= range.last && range.first <= it.last }

    /**
     * Splits what nobody read into a name and the words that were not understood.
     *
     * A run of leftover words can be the name in two places only: before the
     * first fact (an amount, a rate, a date, a nominee), or right beside the
     * words that say what the thing is — the type or the institution. So in
     * "1L gold wedding coins at ICICI" the name is "wedding", and in "SBI FD 2L
     * 7% joint with Sita" it is "SBI". Anywhere else a run is words we did not
     * understand: in "HDFC FD 3 lakh 7.1% matures 5 March 2028 nominee Aarav
     * joint with Sita" there is no name at all, and "joint with Sita" comes back
     * as not understood — where it can be seen and dealt with — instead of being
     * saved as the name of a deposit because it happened to be left over.
     *
     * Qualifying runs join into one name unless a fact sits between them.
     */
    private fun nameAndLeftovers(text: String, found: List<Found>, consumed: List<IntRange>): Pair<WordSpan?, List<WordSpan>> {
        val runs = mutableListOf<WordSpan>()
        var index = 0
        while (index < text.length) {
            if (consumed.any { index in it }) { index++; continue }
            val start = index
            while (index < text.length && consumed.none { index in it }) index++
            trimRun(text, start, index)?.let { runs += it }
        }

        val facts = found.filter { it.isFact }.flatMap { it.ranges }
        val labels = found.filterNot { it.isFact }.flatMap { it.ranges }
        val firstFact = facts.minOfOrNull { it.first }

        fun couldBeName(run: WordSpan): Boolean {
            // A run that opens with a cue — "matures in March 2028" — is a fact
            // we could not read, and the one thing it certainly is not is a name.
            if (run.text.substringBefore(' ').lowercase().trimEnd(':', '.') in CUE_WORDS) return false
            if (firstFact == null || run.end <= firstFact) return true
            val left = (facts + labels).filter { it.last < run.start }.maxByOrNull { it.last }
            val right = (facts + labels).filter { it.first >= run.end }.minByOrNull { it.first }
            return (left != null && left in labels) || (right != null && right in labels)
        }

        val first = runs.indexOfFirst(::couldBeName)
        if (first < 0) return null to runs

        val parts = mutableListOf(runs[first])
        val rest = runs.filterIndexed { i, _ -> i < first }.toMutableList()
        for (run in runs.drop(first + 1)) {
            val gap = parts.last().end until run.start
            if (parts.last() === runs[runs.indexOf(run) - 1] && couldBeName(run) && !overlaps(gap, facts)) parts += run
            else rest += run
        }
        val name = WordSpan(parts.joinToString(" ") { it.text }, parts.first().start, parts.last().end)
        return name to rest.sortedBy { it.start }
    }

    private val CUE_WORDS = setOf(
        "matures", "mature", "maturing", "maturity", "due", "till", "until",
        "nominee", "nominated", "nomination", "rate", "interest", "roi", "@",
    )

    /** The words in one run of leftover text, with connectives and punctuation trimmed off its edges. */
    private fun trimRun(text: String, from: Int, until: Int): WordSpan? {
        val words = WORD.findAll(text.substring(from, until))
            .map { it.value.trimEnd('.') to from + it.range.first }
            .filter { (word, _) -> word.isNotEmpty() }
            .toMutableList()
        // Connectives left behind once their neighbours are consumed read as
        // noise, not as a name.
        while (words.isNotEmpty() && words.first().first.lowercase() in CONNECTIVES) words.removeAt(0)
        while (words.isNotEmpty() && words.last().first.lowercase() in CONNECTIVES) words.removeAt(words.size - 1)
        if (words.isEmpty()) return null
        val start = words.first().second
        val end = words.last().second + words.last().first.length
        return WordSpan(text.substring(start, end), start, end)
    }

    private val WORD = Regex("""[^\s,;:@/()]+""")

    private val CONNECTIVES = setOf(
        "at", "in", "on", "from", "of", "the", "a", "an", "for", "with", "and", "is", "to", "by", "-",
    )

    private val TOKEN = Regex("""₹|\d+(?:,\d+)*(?:\.\d+)?|\p{L}+""")
    private val GAP = Regex("""\.?[\s\-]*""")

    private val LEADING_CURRENCY = setOf("₹", "rs", "inr")
    private val TRAILING_CURRENCY = setOf("rs", "inr", "rupees", "rupee")

    private val SCALES: Map<String, BigDecimal> = buildMap {
        listOf("cr", "crs", "crore", "crores").forEach { put(it, BigDecimal(10_000_000)) }
        listOf("l", "lac", "lacs", "lakh", "lakhs").forEach { put(it, BigDecimal(100_000)) }
        listOf("k", "thousand").forEach { put(it, BigDecimal(1_000)) }
    }

    /** "a lakh", "half a crore" aside, the fractions said before a scale all over North and South India alike. */
    private val FRACTION_WORDS: Map<String, BigDecimal> = mapOf(
        "a" to BigDecimal.ONE,
        "half" to BigDecimal("0.5"),
        "sawa" to BigDecimal("1.25"),
        "dedh" to BigDecimal("1.5"), "derh" to BigDecimal("1.5"),
        "dhai" to BigDecimal("2.5"), "adhai" to BigDecimal("2.5"),
    )

    private val ONES_WORDS: Map<String, Int> = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { (i, w) -> w to i }

    private val TENS_WORDS: Map<String, Int> = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val RELATIONSHIPS = setOf(
        "wife", "husband", "spouse", "son", "daughter", "mother", "father",
        "brother", "sister", "grandson", "granddaughter",
    )

    private val FIELD_ORDER = listOf(
        "typeId", "title", "investedAmount", "quantity", "attributes.interest_rate", "institutionId",
        "startDate", "maturityDate", "nomineeName", "nomineeRelationship",
    )
}
