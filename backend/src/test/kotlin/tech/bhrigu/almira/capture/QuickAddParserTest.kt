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
    private val sbiId = UUID.randomUUID()
    private val angelOneId = UUID.randomUUID()
    private val hoablId = UUID.randomUUID()

    private val vocabulary = Vocabulary(
        types = listOf(
            TypeVocabulary(goldId, "gold_physical", "Physical Gold", setOf("gold", "coins")),
            TypeVocabulary(fdId, "fd", "Fixed Deposit", setOf("fd", "fixed deposit", "deposit")),
            TypeVocabulary(stockId, "stock_listed", "Listed Shares", setOf("shares", "stock")),
            TypeVocabulary(propertyId, "property", "Property", setOf("flat", "plot", "house", "property")),
            // A custom type, matched exactly like a built-in one.
            TypeVocabulary(angelTypeId, "angel_investment", "Angel Investment", setOf("angel investment")),
        ),
        institutions = listOf(
            InstitutionVocabulary(iciciId, "ICICI Bank"),
            InstitutionVocabulary(sbiId, "State Bank of India"),
            InstitutionVocabulary(angelOneId, "Angel One"),
            InstitutionVocabulary(hoablId, "House of Abhinandan Lodha"),
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
}
