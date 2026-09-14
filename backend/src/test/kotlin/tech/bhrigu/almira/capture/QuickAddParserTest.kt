package tech.bhrigu.almira.capture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.capture.QuickAddParser.InstitutionVocabulary
import tech.bhrigu.almira.capture.QuickAddParser.TypeVocabulary
import tech.bhrigu.almira.capture.QuickAddParser.Vocabulary
import java.time.LocalDate
import java.util.UUID

/**
 * The parser proposes; it never saves. So the tests care about two things: that
 * it reads what people actually type, and that it says nothing when it is not
 * sure — inventing a date or an amount is how a registry fills with fiction.
 */
@DisplayName("Quick-add parsing")
class QuickAddParserTest {

    private val goldId = UUID.randomUUID()
    private val fdId = UUID.randomUUID()
    private val stockId = UUID.randomUUID()
    private val propertyId = UUID.randomUUID()
    private val angelTypeId = UUID.randomUUID()
    private val iciciId = UUID.randomUUID()
    private val iciciAmcId = UUID.randomUUID()
    private val sbiId = UUID.randomUUID()
    private val angelOneId = UUID.randomUUID()
    private val hoablId = UUID.randomUUID()
    private val hdfcId = UUID.randomUUID()
    private val sipId = UUID.randomUUID()

    private val vocabulary = Vocabulary(
        types = listOf(
            TypeVocabulary(goldId, "gold_physical", "Physical Gold", setOf("gold", "coins")),
            TypeVocabulary(fdId, "fd", "Fixed Deposit", setOf("fd", "fixed deposit", "deposit")),
            TypeVocabulary(stockId, "stock_listed", "Listed Shares", setOf("shares", "stock")),
            TypeVocabulary(propertyId, "property", "Property", setOf("flat", "plot", "house", "property")),
            // A custom type, matched exactly like a built-in one.
            TypeVocabulary(angelTypeId, "angel_investment", "Angel Investment", setOf("angel investment")),
            TypeVocabulary(sipId, "mf_sip", "Mutual Fund — SIP", setOf("sip", "mutual fund", "mf")),
        ),
        institutions = listOf(
            InstitutionVocabulary(iciciId, "ICICI Bank"),
            InstitutionVocabulary(iciciAmcId, "ICICI Prudential Mutual Fund"),
            InstitutionVocabulary(sbiId, "State Bank of India"),
            InstitutionVocabulary(angelOneId, "Angel One"),
            InstitutionVocabulary(hoablId, "House of Abhinandan Lodha"),
            InstitutionVocabulary(hdfcId, "HDFC Bank"),
        ),
    )

    private val today = LocalDate.parse("2026-09-06")

    private fun parse(text: String) = QuickAddParser.parse(text, vocabulary, today)

    private fun QuickAddParser.Parsed.field(key: String) = fields.firstOrNull { it.key == key }

    // --- the headline case ---------------------------------------------------

    @Test
    fun `reads the example from the brief`() {
        val result = parse("1L gold 6.3g at ICICI Aug 3")

        assertThat(result.field("investedAmount")?.value).isEqualTo("100000")
        assertThat(result.field("investedAmount")?.display).isEqualTo("₹1,00,000")
        assertThat(result.field("quantity")?.display).isEqualTo("6.3 g")
        assertThat(result.field("typeId")?.value).isEqualTo(goldId.toString())
        assertThat(result.field("institutionId")?.display).isEqualTo("ICICI Bank")
        assertThat(result.field("startDate")?.value).isEqualTo("2026-08-03")
        assertThat(result.note).isNull()
    }

    // --- Indian amounts ------------------------------------------------------

    @Test
    fun `understands how amounts are actually written here`() {
        mapOf(
            "1L gold" to "100000",
            "1.5L gold" to "150000",
            "2 lakh gold" to "200000",
            "50k gold" to "50000",
            "2cr gold" to "20000000",
            "2.5 crore gold" to "25000000",
            "₹1,00,000 gold" to "100000",
            "100000 gold" to "100000",
        ).forEach { (input, expected) ->
            assertThat(parse(input).field("investedAmount")?.value)
                .describedAs(input).isEqualTo(expected)
        }
    }

    /**
     * "6.3" on its own is far more likely a weight or a day than six rupees.
     * Guessing it as money would put a nonsense figure into someone's net worth.
     */
    @Test
    fun `a bare small number is not treated as money`() {
        assertThat(parse("gold 6.3g").field("investedAmount")).isNull()
        assertThat(parse("gold 3 Aug").field("investedAmount")).isNull()
    }

    @Test
    fun `the quantity is read before the amount, so its unit is not swallowed`() {
        val result = parse("1L gold 6.3g")
        assertThat(result.field("quantity")?.value).isEqualTo("6.3")
        assertThat(result.field("investedAmount")?.value).isEqualTo("100000")
    }

    @Test
    fun `reads the units people use`() {
        assertThat(parse("gold 250 gms").field("quantity")?.display).isEqualTo("250 g")
        assertThat(parse("100 shares").field("quantity")?.display).isEqualTo("100 shares")
        assertThat(parse("50 units").field("quantity")?.display).isEqualTo("50 units")
    }

    // --- dates ---------------------------------------------------------------

    @Test
    fun `reads dates both ways round, and with or without a year`() {
        assertThat(parse("gold Aug 3").field("startDate")?.value).isEqualTo("2026-08-03")
        assertThat(parse("gold 3 Aug").field("startDate")?.value).isEqualTo("2026-08-03")
        assertThat(parse("gold 3 August 2024").field("startDate")?.value).isEqualTo("2024-08-03")
        assertThat(parse("gold 3/8/2024").field("startDate")?.value).isEqualTo("2024-08-03")
        assertThat(parse("gold today").field("startDate")?.value).isEqualTo("2026-09-06")
        assertThat(parse("gold yesterday").field("startDate")?.value).isEqualTo("2026-09-05")
    }

    /**
     * People record what they did. A date with no year that has not happened yet
     * this year is last year's — otherwise a purchase lands in the future, where
     * it sits outside every report until it arrives.
     */
    @Test
    fun `a yearless date in the future is read as last year`() {
        // December has not happened yet in September 2026.
        assertThat(parse("gold 25 Dec").field("startDate")?.value).isEqualTo("2025-12-25")
        // August has, so it is this year's.
        assertThat(parse("gold 3 Aug").field("startDate")?.value).isEqualTo("2026-08-03")
    }

    @Test
    fun `an impossible date is left alone rather than corrected into a real one`() {
        assertThat(parse("gold 31 Feb").field("startDate"))
            .describedAs("silently making it 28 Feb would be inventing a fact")
            .isNull()
    }

    // --- institutions and types ----------------------------------------------

    @Test
    fun `matches an institution by its first word`() {
        assertThat(parse("1L gold at ICICI").field("institutionId")?.value)
            .isEqualTo(iciciId.toString())
    }

    /**
     * A household with both "ICICI Bank" and "ICICI Prudential Mutual Fund"
     * means the bank when they say ICICI. Ranking first-word matches by length
     * — as full-name matches rightly are — filed gold purchases at a fund house.
     */
    @Test
    fun `a bare first word means the plainest institution that starts with it`() {
        assertThat(parse("1L gold at ICICI").field("institutionId")?.display)
            .isEqualTo("ICICI Bank")
    }

    @Test
    fun `but a full name still wins wherever it appears in the text`() {
        assertThat(parse("2L SIP in ICICI Prudential Mutual Fund").field("institutionId")?.display)
            .isEqualTo("ICICI Prudential Mutual Fund")
    }

    @Test
    fun `prefers the longer institution name where both would match`() {
        assertThat(parse("1L FD at State Bank of India").field("institutionId")?.display)
            .isEqualTo("State Bank of India")
    }

    @Test
    fun `matches a multi-word type`() {
        assertThat(parse("2L fixed deposit at ICICI").field("typeId")?.value)
            .isEqualTo(fdId.toString())
    }

    /**
     * Types and institutions are drawn from the same words, so neither can go
     * first: "Angel Investment" the type and "Angel One" the broker both begin
     * with "angel". Read in a fixed order, whichever ran first swallowed the
     * word and the other silently lost — the custom type became a title reading
     * "investment" filed under a broker nobody mentioned.
     */
    @Test
    fun `where a type and an institution share a word, the longer match keeps it`() {
        val result = parse("5L angel investment")

        assertThat(result.field("typeId")?.value).isEqualTo(angelTypeId.toString())
        assertThat(result.field("institutionId")).isNull()
        assertThat(result.field("title"))
            .describedAs("the words became the type, so nothing is left to name")
            .isNull()
    }

    @Test
    fun `and the same rule lets the institution win when it is the longer one`() {
        val result = parse("50L plot at House of Abhinandan Lodha")

        assertThat(result.field("institutionId")?.display).isEqualTo("House of Abhinandan Lodha")
        assertThat(result.field("typeId")?.value)
            .describedAs("read again from what was left, rather than lost")
            .isEqualTo(propertyId.toString())
        assertThat(result.field("typeId")?.sourceText).isEqualTo("plot")
    }

    // --- what is left over ---------------------------------------------------

    @Test
    fun `whatever is left becomes a suggested name, at low confidence`() {
        val result = parse("1L gold wedding coins at ICICI")
        val title = result.field("title")
        assertThat(title?.value).isEqualTo("wedding")
        assertThat(title?.confidence)
            .describedAs("a guess, offered rather than asserted")
            .isEqualTo("low")
    }

    @Test
    fun `connectives left behind are not mistaken for a name`() {
        assertThat(parse("1L gold at ICICI").field("title")).isNull()
    }

    @Test
    fun `every field says which words it came from, so a chip can point at them`() {
        val result = parse("1L gold 6.3g at ICICI Aug 3")
        assertThat(result.fields).allSatisfy {
            assertThat(it.sourceText).describedAs(it.key).isNotBlank()
        }
        assertThat(result.field("investedAmount")?.sourceText).isEqualTo("1L")
    }

    // --- when it cannot tell -------------------------------------------------

    @Test
    fun `gibberish produces nothing and says so`() {
        val result = parse("asdf qwer")
        assertThat(result.field("typeId")).isNull()
        assertThat(result.note).contains("Pick a type")
    }

    @Test
    fun `empty input asks for an example rather than failing`() {
        val result = parse("   ")
        assertThat(result.fields).isEmpty()
        assertThat(result.note).contains("1L gold")
    }

    @Test
    fun `parsing the same text twice gives the same answer`() {
        val text = "2.5L fd at State Bank of India 15 Jan 2025"
        assertThat(parse(text).fields.map { it.value })
            .isEqualTo(parse(text).fields.map { it.value })
    }

    // --- real sentences (X-39) ------------------------------------------------

    /**
     * The sentence that started this: everything past the amount used to land in
     * the name, so the chip read "7.1% matures nominee Aarav".
     */
    @Test
    fun `reads the rate, the maturity and the nominee out of a real sentence`() {
        val result = parse("HDFC FD 3 lakh 7.1% matures 5 March 2028 nominee Aarav")

        assertThat(result.field("typeId")?.value).isEqualTo(fdId.toString())
        assertThat(result.field("institutionId")?.display).isEqualTo("HDFC Bank")
        assertThat(result.field("investedAmount")?.value).isEqualTo("300000")
        assertThat(result.field("investedAmount")?.hint).isEqualTo("Three Lakh Rupees")
        assertThat(result.field("attributes.interest_rate")?.value).isEqualTo("7.1")
        assertThat(result.field("attributes.interest_rate")?.label).isEqualTo("Rate")
        assertThat(result.field("maturityDate")?.value).isEqualTo("2028-03-05")
        assertThat(result.field("maturityDate")?.label).isEqualTo("Matures")
        assertThat(result.field("nomineeName")?.value).isEqualTo("Aarav")
        assertThat(result.field("startDate"))
            .describedAs("a maturity is not a purchase date")
            .isNull()
        assertThat(result.field("title")).isNull()
        assertThat(result.notUnderstood).isEmpty()
    }

    @Test
    fun `every chip says exactly where in the sentence its words are`() {
        val text = "HDFC FD 3 lakh 7.1% matures 5 March 2028 nominee Aarav"
        val result = parse(text)

        assertThat(result.fields).allSatisfy {
            assertThat(it.start).describedAs(it.key).isNotNull()
            assertThat(text.substring(it.start!!, it.end!!)).describedAs(it.key).isEqualTo(it.sourceText)
        }
        assertThat(result.field("maturityDate")?.sourceText).isEqualTo("matures 5 March 2028")
        assertThat(result.field("nomineeName")?.sourceText).isEqualTo("nominee Aarav")
    }

    @Test
    fun `positions are counted in what was sent, leading spaces included`() {
        val result = parse("   2L FD")
        assertThat(result.field("investedAmount")?.start).isEqualTo(3)
        assertThat(result.field("investedAmount")?.end).isEqualTo(5)
    }

    @Test
    fun `when the bank is not one we know, it is the name and not a guess`() {
        val result = parse("Canara FD 2L @ 6.8% p.a. from 12 Jan 2025 maturity 12/01/2030 nominee wife Priya")

        assertThat(result.field("title")?.value).isEqualTo("Canara")
        assertThat(result.field("attributes.interest_rate")?.value).isEqualTo("6.8")
        assertThat(result.field("attributes.interest_rate")?.sourceText).isEqualTo("@ 6.8% p.a.")
        assertThat(result.field("startDate")?.value).isEqualTo("2025-01-12")
        assertThat(result.field("maturityDate")?.value).isEqualTo("2030-01-12")
        assertThat(result.field("nomineeName")?.value).isEqualTo("Priya")
        assertThat(result.field("nomineeRelationship")?.value).isEqualTo("wife")
        assertThat(result.notUnderstood).isEmpty()
    }

    /**
     * The other half of the fix: what still cannot be read is shown as not
     * understood, never saved as part of the name.
     */
    @Test
    fun `words after a fact that mean nothing go to didn't understand, not the name`() {
        val result = parse("SBI FD 2L 7% joint with Sita")

        assertThat(result.field("title")?.value).isEqualTo("SBI")
        assertThat(result.notUnderstood.map { it.text }).containsExactly("joint with Sita")
        assertThat(result.unparsed).isEqualTo("joint with Sita")
        val span = result.notUnderstood.single()
        assertThat("SBI FD 2L 7% joint with Sita".substring(span.start, span.end)).isEqualTo("joint with Sita")
    }

    @Test
    fun `a maturity with no day is left for the form to ask, and is not the name`() {
        val result = parse("3 lakh FD matures in March 2028")

        assertThat(result.field("maturityDate")).isNull()
        assertThat(result.field("startDate")).isNull()
        assertThat(result.field("title")).isNull()
        assertThat(result.notUnderstood.map { it.text }).containsExactly("matures in March 2028")
    }

    @Test
    fun `a yearless maturity is the next one, not the last`() {
        // Today is 6 September 2026.
        assertThat(parse("FD matures 12 Jan").field("maturityDate")?.value).isEqualTo("2027-01-12")
        assertThat(parse("FD due on 30 Nov").field("maturityDate")?.value).isEqualTo("2026-11-30")
    }

    @Test
    fun `the name keeps its capitalised words and stops at what is plainly not a name`() {
        assertThat(parse("nominee Lakshmi Devi 5L FD").field("nomineeName")?.value).isEqualTo("Lakshmi Devi")
        val beforeBank = parse("nominee Aarav HDFC FD 1L")
        assertThat(beforeBank.field("nomineeName")?.value).isEqualTo("Aarav")
        assertThat(beforeBank.field("institutionId")?.display).isEqualTo("HDFC Bank")
        assertThat(parse("FD 2L nominee: son Rohan").field("nomineeName")?.value).isEqualTo("Rohan")
    }

    @Test
    fun `a lower-case nominee is taken alone, and offered less confidently`() {
        val result = parse("fd 1l nominee aarav sbi")
        assertThat(result.field("nomineeName")?.value).isEqualTo("aarav")
        assertThat(result.field("nomineeName")?.confidence).isEqualTo("medium")
    }

    @Test
    fun `amounts said in words, the way they are said here`() {
        mapOf(
            "three lakh FD" to "300000",
            "fifty thousand rupees FD" to "50000",
            "twenty five thousand SIP" to "25000",
            "two and a half lakh FD" to "250000",
            "one crore twenty lakh flat" to "12000000",
            "1 lakh 50 thousand gold" to "150000",
            "dedh lakh gold" to "150000",
            "dhai crore plot" to "25000000",
            "a lakh FD" to "100000",
            "Rs. 3,00,000/- FD" to "300000",
            "INR 45000 FD" to "45000",
            "5 lakhs FD" to "500000",
        ).forEach { (input, expected) ->
            assertThat(parse(input).field("investedAmount")?.value).describedAs(input).isEqualTo(expected)
        }
        assertThat(parse("Rs. 3,00,000/- FD").field("investedAmount")?.sourceText).isEqualTo("Rs. 3,00,000/-")
        assertThat(parse("Rs. 3,00,000/- FD").field("title")).isNull()
    }

    @Test
    fun `numbers that are not money stay out of the amount`() {
        assertThat(parse("stock INE009A01021").field("investedAmount"))
            .describedAs("an ISIN has digits and a K in it, and is not ₹8,46,000")
            .isNull()
        assertThat(parse("FD for five years").field("investedAmount")).isNull()
        assertThat(parse("3 lakh FD 2028").field("investedAmount")?.value)
            .describedAs("a year after an amount is not added to it")
            .isEqualTo("300000")
        assertThat(parse("gold 3 Aug 25 lakh").field("investedAmount")?.value).isEqualTo("2500000")
        assertThat(parse("gold 3 Aug 25 lakh").field("startDate")?.value).isEqualTo("2026-08-03")
    }

    @Test
    fun `a fund name before the amount is the name, a stray word after it is not`() {
        val result = parse("Parag Parikh Flexi Cap SIP 5k monthly")
        assertThat(result.field("typeId")?.value).isEqualTo(sipId.toString())
        assertThat(result.field("title")?.value).isEqualTo("Parag Parikh Flexi Cap")
        assertThat(result.field("investedAmount")?.value).isEqualTo("5000")
        assertThat(result.notUnderstood.map { it.text }).containsExactly("monthly")
    }

    @Test
    fun `rates are only read with a percent`() {
        assertThat(parse("FD 1L 7.25 percent").field("attributes.interest_rate")?.value).isEqualTo("7.25")
        assertThat(parse("gold 7.1").field("attributes.interest_rate")).isNull()
    }

    @Test
    fun `a name either side of the type is still one name`() {
        assertThat(parse("gold coins 20g for Meera's wedding").field("title")?.value).isEqualTo("Meera's wedding")
    }
}
