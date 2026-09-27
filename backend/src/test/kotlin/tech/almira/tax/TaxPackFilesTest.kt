package tech.almira.tax

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.almira.common.IndianNumbers
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The two files a CA receives. Pure: a pack in, bytes out, read back the way
 * the CA's software would read them.
 */
@DisplayName("The CA files: Schedule 112A CSV and the tax-pack PDF")
class TaxPackFilesTest {

    private val infosys = CapitalGainsRules.Holding("stock_listed", "equity", mapOf("isin" to "INE009A01021"))

    private fun gainLine(
        holding: CapitalGainsRules.Holding,
        title: String,
        disposal: CapitalGainsRules.Disposal,
        fmv: BigDecimal? = null,
    ): GainLine {
        val line = CapitalGainsRules.line(holding, disposal, fmv)
        return GainLine(
            investmentId = UUID.randomUUID(), title = title, isin = CapitalGainsRules.isin(holding),
            assetClass = line.assetClass.name.lowercase(), assetClassLabel = line.assetClass.label,
            acquiredOn = disposal.acquiredOn, transferredOn = disposal.transferredOn,
            term = line.term, deemedShortTerm = line.deemedShortTerm, section = line.section.code,
            sectionNewAct = line.section.newAct, period = line.period.name.lowercase(),
            quantity = line.quantity, proceeds = line.proceeds, actualCost = line.actualCost,
            fmvPerUnit2018 = line.fmvPerUnit2018, fmvTotal2018 = line.fmvTotal2018,
            grandfatheredValue = line.grandfatheredValue, fmvMissing = line.fmvMissing,
            costOfAcquisition = line.costOfAcquisition, indexedCost = line.indexedCost,
            gain = line.gain, gainFormatted = IndianNumbers.rupees(line.gain), indexedGain = line.indexedGain,
            indexationApplied = line.indexationApplied, ratePercent = line.ratePercent,
            taxableGain = line.taxableGain, taxAtRate = line.taxAtRate, basis = line.basis, notes = line.notes,
        )
    }

    private fun disposal(qty: Long, cost: Long, proceeds: Long, bought: String, sold: String) =
        CapitalGainsRules.Disposal(
            BigDecimal(qty), BigDecimal(proceeds), BigDecimal(cost), LocalDate.parse(bought), LocalDate.parse(sold),
        )

    private val lines = listOf(
        gainLine(infosys, "Infosys Ltd.", disposal(100, 50_000, 150_000, "2016-06-10", "2024-09-15"), BigDecimal(1_200)),
        gainLine(infosys, "Infosys Ltd.", disposal(40, 60_000, 60_000, "2019-03-01", "2024-09-15")),
        gainLine(
            CapitalGainsRules.Holding("mf_lumpsum", "mutual_funds", mapOf("scheme_category" to "equity")),
            "Parag Parikh Flexi Cap", disposal(500, 20_000, 45_000, "2020-01-01", "2024-10-01"),
        ),
        gainLine(CapitalGainsRules.Holding("gold_physical", "gold", emptyMap()), "Gold coins",
            disposal(1, 100_000, 180_000, "2019-05-01", "2024-06-01")),
    )

    // --- CSV -----------------------------------------------------------------

    @Test
    fun `a grandfathered lot is its own BE row with every column worked as the schedule defines`() {
        val rows = Schedule112ACsv.rows(lines)
        val be = rows.first { it[0] == "BE" }
        assertThat(be).containsExactly(
            "BE", "AE", "INE009A01021", "Infosys Ltd", "100", "1500", "150000",
            "120000", "50000", "120000", "1200", "120000", "0", "120000", "30000",
        )
    }

    @Test
    fun `later acquisitions are one consolidated AE row, and gold is not in the schedule at all`() {
        val rows = Schedule112ACsv.rows(lines)
        assertThat(rows).hasSize(2)
        val ae = rows.first { it[0] == "AE" }
        assertThat(ae.subList(0, 4)).containsExactly("AE", "AE", "INNOTREQUIRD", "CONSOLIDATED")
        // 60,000 + 45,000 sold; 60,000 + 20,000 cost; 25,000 balance.
        assertThat(ae[6]).isEqualTo("105000")
        assertThat(ae[7]).isEqualTo("80000")
        assertThat(ae[14]).isEqualTo("25000")
        assertThat(ae[4]).isEmpty()
    }

    @Test
    fun `no value carries a character the portal rejects`() {
        val csv = Schedule112ACsv.csv(lines).toString(Charsets.UTF_8)
        val body = csv.lines().drop(1).filter { it.isNotBlank() }
        body.forEach { row ->
            row.split(",").forEach { cell ->
                assertThat(cell).describedAs(row).doesNotContain("/", "_", "(", ")", "&", "@", "'", "\"", ";", ":")
            }
        }
        assertThat(Schedule112ACsv.name("Tata Motors (DVR) & Co.")).isEqualTo("Tata Motors DVR Co")
        assertThat(Schedule112ACsv.name("గోల్డ్")).isEqualTo("UNNAMED")
    }

    // --- PDF -----------------------------------------------------------------

    private fun pack(memberName: String? = "Ishwarya", withLines: List<GainLine> = lines): TaxPack {
        val fy = FinancialYear(2024)
        val buckets = CapitalGainsRules.buckets(
            withLines.map { l ->
                CapitalGainsRules.line(
                    when (l.assetClass) {
                        "gold" -> CapitalGainsRules.Holding("gold_physical", "gold", emptyMap())
                        "equity_fund" -> CapitalGainsRules.Holding("mf_lumpsum", "mutual_funds", mapOf("scheme_category" to "equity"))
                        else -> infosys
                    },
                    CapitalGainsRules.Disposal(l.quantity, l.proceeds, l.actualCost, l.acquiredOn, l.transferredOn),
                    l.fmvPerUnit2018,
                )
            },
            fy,
        ).map { b ->
            GainBucket(
                b.section.code, b.section.newAct, b.period.name.lowercase(), b.period.label, b.section.code,
                b.ratePercent, b.lines, b.proceeds, b.gain, b.taxableGain, IndianNumbers.rupees(b.taxableGain),
                b.exemptionApplied, b.taxAtRate, b.taxAtRate?.let(IndianNumbers::rupees),
            )
        }
        val net = withLines.fold(BigDecimal.ZERO) { acc, l -> acc + l.gain }
        val schedule = CapitalGainsSchedule(
            financialYear = fy.label, memberId = null, lines = withLines, buckets = buckets,
            exemption112A = BigDecimal(125_000), exemption112AFormatted = "₹1,25,000",
            netGain = net, netGainFormatted = IndianNumbers.rupees(net),
            netGainInWords = "Rupees " + IndianNumbers.words(net),
            grandfathering = listOf(
                GrandfatheringEntry(UUID.randomUUID(), "Infosys Ltd.", "INE009A01021", BigDecimal("1200.50"), "BSE high", null, 1),
            ),
            warnings = listOf("Some sales are larger than the purchases recorded, so part of their cost is nil."),
            assumptions = listOf("Units are matched to sales oldest first."),
            disclaimer = "Informational only, not tax advice.",
        )
        val meter = DeductionMeter(
            "80C", "Section 80C", "PPF, ELSS", BigDecimal(150_000), "₹1,50,000", BigDecimal(150_000), "₹1,50,000",
            BigDecimal.ZERO, "₹0", BigDecimal(100), emptyList(), null,
        )
        return TaxPack(
            financialYear = fy.label, memberId = null, memberName = memberName,
            deductions = listOf(meter),
            capitalGains = CapitalGains(fy.label, emptyList(), net, IndianNumbers.rupees(net), emptyList(), "not tax advice"),
            interestIncome = InterestIncome(fy.label, BigDecimal(42_000), "₹42,000", emptyList()),
            disclaimer = "Informational only, not tax advice. Check it with your CA before filing.",
            capitalGainsSchedule = schedule,
        )
    }

    private fun textOf(bytes: ByteArray, page: Int? = null): String = Loader.loadPDF(bytes).use { doc ->
        PDFTextStripper().apply {
            if (page != null) { startPage = page; endPage = page }
        }.getText(doc)
    }

    @Test
    fun `the first page is a cover that says what the year came to, and that it is not advice`() {
        val bytes = TaxPackPdf.render(pack(), "Koduri", LocalDate.of(2026, 9, 14))
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")

        val cover = textOf(bytes, 1)
        assertThat(cover).contains("FY 2024-25")
        assertThat(cover).contains("For Ishwarya")
        assertThat(cover).contains("not tax advice")
        // 30,000 + 0 + 25,000 + 80,000: the net figure, before indexation or exemption.
        assertThat(cover).contains("₹1,35,000")
        assertThat(cover).contains("Worth a look before filing")
    }

    @Test
    fun `the working follows the cover, with every lot, the schedule and the index`() {
        val text = textOf(TaxPackPdf.render(pack(), "Koduri", LocalDate.of(2026, 9, 14)))
        assertThat(text).contains("Capital gains, lot by lot")
        assertThat(text).contains("Schedule 112A")
        assertThat(text).contains("INNOTREQUIRD")
        assertThat(text).contains("Cost Inflation Index used")
        assertThat(text).contains("384")
        assertThat(text).contains("Page 1 of")
    }

    @Test
    fun `a holding named in Telugu does not stop the pack from being made`() {
        val telugu = gainLine(infosys, "బంగారం షేర్లు", disposal(1, 100, 200, "2020-01-01", "2024-09-01"))
        val bytes = TaxPackPdf.render(pack(memberName = "ఇశ్వర్య", withLines = listOf(telugu)), "కొడూరి")
        assertThat(textOf(bytes)).contains("not tax advice")
    }

    @Test
    fun `a year with nothing sold still makes a pack`() {
        val bytes = TaxPackPdf.render(pack(withLines = emptyList()), "Koduri")
        assertThat(textOf(bytes, 1)).contains("Nothing recorded as sold in this year.")
    }
}
