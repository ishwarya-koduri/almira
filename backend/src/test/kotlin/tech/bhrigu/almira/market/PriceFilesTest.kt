package tech.bhrigu.almira.market

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The published price files, read from excerpts of the real ones.
 *
 * The fixtures are cut from the files as published for 11 September 2026 —
 * AMFI's NAVAll.txt, NSE's zipped UDiFF bhavcopy and BSE's plain one — keeping
 * their headers, separators, line endings and the section lines between funds.
 * A parser tested against a file someone typed would pass against a format
 * nobody publishes.
 */
@DisplayName("Price files: AMFI NAVs and the NSE/BSE bhavcopies")
class PriceFilesTest {

    private fun fixture(name: String) = javaClass.getResourceAsStream("/market/$name")!!.readAllBytes()

    private val nav by lazy { AmfiNavFile.parse(fixture("NAVAll-2026-09-11-excerpt.txt")) }

    @Test
    fun `reads every scheme line, and nothing from the headings between them`() {
        assertThat(nav).hasSize(8)
        assertThat(nav.map { it.source }).containsOnly("amfi")
    }

    @Test
    fun `a scheme carries its code, its ISINs, its NAV and the NAV's own date`() {
        val flexiCap = nav.single { it.code == "122639" }
        assertThat(flexiCap.name).isEqualTo("Parag Parikh Flexi Cap Fund")
        assertThat(flexiCap.isins).containsExactly("INF879O01027")
        assertThat(flexiCap.price).isEqualByComparingTo("89.5712")
        assertThat(flexiCap.asOf).isEqualTo(LocalDate.parse("2026-09-11"))
    }

    @Test
    fun `a dash is no ISIN, and a payout and a reinvestment ISIN both match the one NAV`() {
        assertThat(nav.single { it.code == "153964" }.isins).containsExactly("INF879O01308")
        assertThat(nav.single { it.code == "120717" }.isins).containsExactly("INF789F01WY2", "INF789F01WZ9")
    }

    @Test
    fun `a scheme that stopped publishing keeps its last date, for the caller to judge`() {
        assertThat(nav.single { it.code == "106290" }.asOf).isEqualTo(LocalDate.parse("2017-09-26"))
    }

    /**
     * The file used to have six columns; it now has eight. Columns are found by
     * name, so both read the same — and neither reads a plan name as a price.
     */
    @Test
    fun `the earlier six-column layout reads the same`() {
        val older = """
            Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date

            Open Ended Schemes(Equity Scheme - Flexi Cap Fund)

            PPFAS Mutual Fund

            122639;INF879O01027;-;Parag Parikh Flexi Cap Fund - Direct Plan - Growth;89.5712;11-Sep-2026
        """.trimIndent().replace("\n", "\r\n").toByteArray()

        val price = AmfiNavFile.parse(older).single()
        assertThat(price.price).isEqualByComparingTo("89.5712")
        assertThat(price.isins).containsExactly("INF879O01027")
    }

    @Test
    fun `a NAV that is not a number is skipped, not read as zero`() {
        val file = """
            Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date
            122639;INF879O01027;-;Parag Parikh Flexi Cap Fund;Direct Plan;Growth;N.A.;11-Sep-2026
            122640;INF879O01019;-;Parag Parikh Flexi Cap Fund;Regular Plan;Growth;81.6012;11-Sep-2026
        """.trimIndent().toByteArray()
        assertThat(AmfiNavFile.parse(file).map { it.code }).containsExactly("122640")
    }

    @Test
    fun `a page that is not the NAV file is refused`() {
        assertThatThrownBy { AmfiNavFile.parse("<html>Maintenance</html>".toByteArray()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reads NSE's zipped bhavcopy, closing prices only`() {
        val prices = BhavcopyFile.parse(fixture("BhavCopy_NSE_CM_0_0_0_20260911_F_0000-excerpt.csv.zip"), "nse")

        assertThat(prices).hasSize(5)
        val infosys = prices.single { it.symbol == "INFY" }
        assertThat(infosys.source).isEqualTo("nse")
        assertThat(infosys.isins).containsExactly("INE009A01021")
        assertThat(infosys.series).isEqualTo("EQ")
        assertThat(infosys.price).describedAs("ClsPric, not the last traded price").isEqualByComparingTo("1037.70")
        assertThat(infosys.asOf).isEqualTo(LocalDate.parse("2026-09-11"))
        assertThat(prices.single { it.symbol == "EMBASSY" }.series).isEqualTo("RR")
    }

    @Test
    fun `reads BSE's plain bhavcopy in the same layout`() {
        val prices = BhavcopyFile.parse(fixture("BhavCopy_BSE_CM_0_0_0_20260911_F_0000-excerpt.CSV"), "bse")

        assertThat(prices).hasSize(5)
        assertThat(prices.single { it.symbol == "INFY" }.price).isEqualByComparingTo("1038.20")
        assertThat(prices.single { it.symbol == "INFY" }.code).isEqualTo("500209")
    }

    @Test
    fun `a quoted field with a comma in it stays one field`() {
        assertThat(BhavcopyFile.splitCsv("""a,"TATA, SONS",c""")).containsExactly("a", "TATA, SONS", "c")
    }

    @Test
    fun `a file without the UDiFF columns is refused`() {
        assertThatThrownBy { BhavcopyFile.parse("SYMBOL,SERIES,OPEN,HIGH,LOW,CLOSE\nINFY,EQ,1,2,3,4\n".toByteArray(), "nse") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("TradDt")
    }
}
