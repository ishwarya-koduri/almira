package tech.bhrigu.almira.tax

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The tax layer is the part most likely to be believed without checking, so the
 * tests are as much about what it refuses to overstate as what it computes.
 */
@DisplayName("The India tax layer")
class TaxApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String

    private val fy = FinancialYear.current()

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse, role = "admin")
    }

    private fun meters(token: String = owner, member: String? = ownerMemberId) =
        get(
            "/api/v1/households/$householdId/tax/deductions" +
                "?fy=${fy.label}" + (member?.let { "&member=$it" } ?: ""),
            token,
        ).json().associateBy { it.path("section").asText() }

    // --- deductions ----------------------------------------------------------

    @Test
    fun `80C adds up the things that qualify and caps at the limit`() {
        capture(
            owner, householdId, "ppf", "PPF", BigDecimal(500_000), "household",
            attributes = mapOf("account_no" to "PPF-1", "yearly_contribution" to "150000"),
        )
        capture(
            owner, householdId, "insurance_term", "LIC term", BigDecimal(0), "household",
            attributes = mapOf(
                "policy_no" to "5567", "sum_assured" to "10000000",
                "premium_amount" to "18400", "premium_frequency" to "yearly",
            ),
        )

        val meter = meters()["80C"]!!
        assertThat(meter.path("used").decimalValue())
            .describedAs("₹1,50,000 + ₹18,400 is ₹1,68,400, but only ₹1,50,000 can be claimed")
            .isEqualByComparingTo(BigDecimal(150_000))
        assertThat(meter.path("remaining").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(meter.path("note").asText()).contains("only ₹1,50,000")
        assertThat(meter.path("sources")).hasSize(2)
    }

    @Test
    fun `an ELSS fund counts towards 80C, an ordinary equity fund does not`() {
        capture(
            owner, householdId, "mf_sip", "Axis ELSS", BigDecimal(120_000), "household",
            attributes = mapOf(
                "scheme_name" to "Axis ELSS", "sip_amount" to "5000",
                "sip_day" to "5", "scheme_category" to "elss",
            ),
        )
        capture(
            owner, householdId, "mf_sip", "Flexi cap", BigDecimal(200_000), "household",
            attributes = mapOf(
                "scheme_name" to "PPFAS", "sip_amount" to "25000",
                "sip_day" to "5", "scheme_category" to "equity",
            ),
        )

        val sources = meters()["80C"]!!.path("sources").map { it.path("title").asText() }
        assertThat(sources).containsExactly("Axis ELSS")
    }

    @Test
    fun `a monthly premium is counted as a year of premiums`() {
        capture(
            owner, householdId, "insurance_health", "Star Health", BigDecimal(0), "household",
            attributes = mapOf(
                "policy_no" to "H1", "cover_amount" to "1000000",
                "premium_amount" to "2000", "premium_frequency" to "monthly",
            ),
        )
        val meter = meters()["80D"]!!
        assertThat(meter.path("used").decimalValue())
            .describedAs("₹2,000 a month is ₹24,000 a year")
            .isEqualByComparingTo(BigDecimal(24_000))
    }

    /**
     * A declared annual figure is an intention; a recorded payment is a fact.
     * The response says which it used, because someone filing a return needs to
     * know whether to go and check.
     */
    @Test
    fun `recorded contributions win over declared ones, and the source is reported`() {
        val ppf = capture(
            owner, householdId, "ppf", "PPF", BigDecimal(500_000), "household",
            attributes = mapOf("account_no" to "PPF-1", "yearly_contribution" to "150000"),
        ).path("id").asText()

        post(
            "/api/v1/households/$householdId/investments/$ppf/transactions", owner,
            mapOf(
                "txnType" to "contribution", "amount" to 40_000,
                "txnDate" to fy.start.plusMonths(1).toString(),
            ),
        )

        val source = meters()["80C"]!!.path("sources").first()
        assertThat(source.path("amount").decimalValue())
            .describedAs("what was actually paid, not what was intended")
            .isEqualByComparingTo(BigDecimal(40_000))
        assertThat(source.path("basis").asText()).isEqualTo("transactions")
    }

    @Test
    fun `NPS sits in its own section, not inside 80C`() {
        capture(
            owner, householdId, "nps", "NPS Tier I", BigDecimal(300_000), "household",
            attributes = mapOf("pran" to "P1", "tier" to "1", "yearly_contribution" to "50000"),
        )
        val all = meters()
        assertThat(all["80CCD1B"]!!.path("used").decimalValue())
            .isEqualByComparingTo(BigDecimal(50_000))
        assertThat(all["80C"]!!.path("sources")).isEmpty()
    }

    @Test
    fun `a section with nothing against it says so`() {
        assertThat(meters()["80D"]!!.path("note").asText()).isEqualTo("Nothing recorded against this yet.")
    }

    @Test
    fun `home loan interest is labelled as an estimate, not a figure to file`() {
        post(
            "/api/v1/households/$householdId/liabilities", owner,
            mapOf(
                "title" to "HDFC home loan", "kind" to "home", "outstanding" to 2_850_000,
                "interestRate" to 8.6, "visibility" to "household",
            ),
        )
        val meter = meters()["24B"]!!
        assertThat(meter.path("used").decimalValue().toDouble())
            .describedAs("capped at the ₹2,00,000 limit")
            .isEqualTo(200_000.0)
        assertThat(meter.path("note").asText())
            .contains("interest certificate is the figure to file")
        assertThat(meter.path("sources").first().path("basis").asText()).isEqualTo("estimated")
    }

    // --- capital gains -------------------------------------------------------

    @Test
    fun `realized gains are split into short and long term`() {
        val stock = capture(
            owner, householdId, "stock_listed", "Infosys", BigDecimal(0), "household",
            attributes = mapOf("symbol" to "INFY", "exchange" to "nse"),
        ).path("id").asText()

        fun txn(type: String, date: String, qty: Int, price: Int) = post(
            "/api/v1/households/$householdId/investments/$stock/transactions", owner,
            mapOf(
                "txnType" to type, "txnDate" to date, "quantity" to qty,
                "price" to price, "amount" to qty * price,
            ),
        )

        // Bought two years ago and two months ago; both sold inside this FY.
        txn("buy", fy.start.minusYears(2).toString(), 100, 1000)
        txn("buy", fy.start.plusDays(5).toString(), 100, 1500)
        txn("sell", fy.start.plusDays(90).toString(), 150, 2000)

        val gains = get(
            "/api/v1/households/$householdId/tax/capital-gains?fy=${fy.label}", owner,
        ).json()

        val buckets = gains.path("realized").associateBy { it.path("term").asText() }
        assertThat(buckets.keys).containsExactlyInAnyOrder("long", "short")
        assertThat(buckets["long"]!!.path("gain").decimalValue())
            .describedAs("100 units bought at 1,000, sold at 2,000")
            .isEqualByComparingTo(BigDecimal(100_000))
        assertThat(buckets["short"]!!.path("gain").decimalValue())
            .describedAs("50 units bought at 1,500, sold at 2,000")
            .isEqualByComparingTo(BigDecimal(25_000))
        assertThat(gains.path("netRealized").decimalValue()).isEqualByComparingTo(BigDecimal(125_000))
    }

    @Test
    fun `a sale in a different financial year is not in this year's summary`() {
        val stock = capture(
            owner, householdId, "stock_listed", "Wipro", BigDecimal(0), "household",
            attributes = mapOf("symbol" to "WIPRO", "exchange" to "nse"),
        ).path("id").asText()

        post(
            "/api/v1/households/$householdId/investments/$stock/transactions", owner,
            mapOf("txnType" to "buy", "txnDate" to fy.start.minusYears(3).toString(),
                  "quantity" to 100, "price" to 200, "amount" to 20_000),
        )
        // Sold on 31 March of the PREVIOUS financial year — one day outside.
        post(
            "/api/v1/households/$householdId/investments/$stock/transactions", owner,
            mapOf("txnType" to "sell", "txnDate" to fy.start.minusDays(1).toString(),
                  "quantity" to 100, "price" to 300, "amount" to 30_000),
        )

        assertThat(
            get("/api/v1/households/$householdId/tax/capital-gains?fy=${fy.label}", owner)
                .json().path("realized"),
        ).describedAs("31 March belongs to the year before").isEmpty()

        assertThat(
            get("/api/v1/households/$householdId/tax/capital-gains?fy=${fy.previous().label}", owner)
                .json().path("netRealized").decimalValue(),
        ).isEqualByComparingTo(BigDecimal(10_000))
    }

    // --- privacy -------------------------------------------------------------

    /**
     * Deductions are personal. A spouse's private ELSS would raise the household
     * 80C, and showing it would be both wrong for the return and a disclosure.
     */
    @Test
    fun `a private holding never appears in another member's tax pack`() {
        capture(
            spouse, householdId, "mf_sip", "Ravi's private ELSS", BigDecimal(100_000), "private",
            attributes = mapOf(
                "scheme_name" to "ELSS", "sip_amount" to "12500",
                "sip_day" to "5", "scheme_category" to "elss",
            ),
        )

        val hers = get("/api/v1/households/$householdId/tax/pack?fy=${fy.label}", owner).json()
        val sections = hers.path("deductions").associateBy { it.path("section").asText() }
        assertThat(sections["80C"]!!.path("used").decimalValue())
            .describedAs("she cannot see it, so it is not in her figures")
            .isEqualByComparingTo(BigDecimal.ZERO)

        val his = get("/api/v1/households/$householdId/tax/pack?fy=${fy.label}", spouse).json()
        assertThat(
            his.path("deductions").first { it.path("section").asText() == "80C" }
                .path("used").decimalValue(),
        ).isEqualByComparingTo(BigDecimal(150_000))
    }

    // --- disclaimers ---------------------------------------------------------

    @Test
    fun `every response says it is not tax advice`() {
        val pack = get("/api/v1/households/$householdId/tax/pack?fy=${fy.label}", owner).json()
        assertThat(pack.path("disclaimer").asText()).contains("not tax advice")
        assertThat(pack.path("capitalGains").path("disclaimer").asText()).contains("not tax advice")
    }

    @Test
    fun `an unreadable financial year is refused with the format`() {
        assertThat(
            get("/api/v1/households/$householdId/tax/pack?fy=whenever", owner).errorCode(),
        ).isEqualTo("fy_invalid")
    }
}
