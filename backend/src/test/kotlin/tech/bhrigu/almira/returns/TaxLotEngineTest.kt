package tech.bhrigu.almira.returns

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.returns.TaxLotEngine.Txn
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Tax lots decide what someone reports to the government, so the arithmetic is
 * pinned rather than assumed. The engine is pure, which means every case here is
 * a list of transactions and an expected answer — no fixtures, no database.
 */
@DisplayName("Replaying transactions into tax lots")
class TaxLotEngineTest {

    private fun buy(date: String, qty: String, price: String) = Txn(
        UUID.randomUUID(), "buy", LocalDate.parse(date),
        BigDecimal(qty), BigDecimal(qty).multiply(BigDecimal(price)), BigDecimal(price),
    )

    private fun sell(date: String, qty: String, price: String) = Txn(
        UUID.randomUUID(), "sell", LocalDate.parse(date),
        BigDecimal(qty), BigDecimal(qty).multiply(BigDecimal(price)), BigDecimal(price),
    )

    private fun action(type: String, date: String, ratio: String, qty: String? = null) = Txn(
        UUID.randomUUID(), type, LocalDate.parse(date), qty?.let(::BigDecimal), null, null, ratio,
    )

    private fun replay(vararg txns: Txn, method: String = "fifo", months: Int = 12) =
        TaxLotEngine.replay(txns.toList(), method, months)

    // --- lots ----------------------------------------------------------------

    @Test
    fun `each purchase opens a lot at its own cost`() {
        val result = replay(buy("2024-01-10", "100", "1500"), buy("2024-06-10", "50", "1800"))

        assertThat(result.lots).hasSize(2)
        assertThat(result.lots[0].unitCost).isEqualByComparingTo(BigDecimal(1500))
        assertThat(result.lots[1].unitCost).isEqualByComparingTo(BigDecimal(1800))
        assertThat(result.lots.map { it.remainingQty }).allMatch { it.signum() > 0 }
    }

    @Test
    fun `a sale consumes the oldest units first`() {
        val result = replay(
            buy("2024-01-10", "100", "1500"),
            buy("2024-06-10", "100", "1800"),
            sell("2025-03-01", "120", "2000"),
        )

        // 100 from the first lot, 20 from the second — never the other way round.
        assertThat(result.disposals).hasSize(2)
        assertThat(result.disposals[0].quantity).isEqualByComparingTo(BigDecimal(100))
        assertThat(result.disposals[0].acquiredOn).isEqualTo(LocalDate.parse("2024-01-10"))
        assertThat(result.disposals[1].quantity).isEqualByComparingTo(BigDecimal(20))
        assertThat(result.disposals[1].acquiredOn).isEqualTo(LocalDate.parse("2024-06-10"))

        assertThat(result.lots[0].remainingQty).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.lots[1].remainingQty).isEqualByComparingTo(BigDecimal(80))
    }

    @Test
    fun `the gain is proceeds minus what those units cost`() {
        val result = replay(
            buy("2024-01-10", "100", "1500"),
            sell("2025-03-01", "100", "2000"),
        )
        val disposal = result.disposals.single()
        assertThat(disposal.costBasis).isEqualByComparingTo(BigDecimal(150_000))
        assertThat(disposal.proceeds).isEqualByComparingTo(BigDecimal(200_000))
        assertThat(disposal.gain).isEqualByComparingTo(BigDecimal(50_000))
    }

    @Test
    fun `a loss is recorded as a negative gain, not dropped`() {
        val result = replay(
            buy("2024-01-10", "100", "2000"),
            sell("2024-03-01", "100", "1500"),
        )
        assertThat(result.disposals.single().gain).isEqualByComparingTo(BigDecimal(-50_000))
    }

    // --- short vs long -------------------------------------------------------

    @Test
    fun `twelve months and a day is long, twelve months exactly is not`() {
        val onTheDay = replay(
            buy("2024-01-10", "10", "100"), sell("2025-01-10", "10", "150"),
        ).disposals.single()
        val dayAfter = replay(
            buy("2024-01-10", "10", "100"), sell("2025-01-11", "10", "150"),
        ).disposals.single()

        assertThat(onTheDay.gainTerm).describedAs("held for exactly twelve months").isEqualTo("short")
        assertThat(dayAfter.gainTerm).describedAs("held for MORE than twelve months").isEqualTo("long")
    }

    /**
     * Measured in months rather than 365 days, because the rule is written in
     * months — and a leap year would otherwise flip the classification of a sale
     * on exactly the wrong day.
     */
    @Test
    fun `a leap year does not change the classification`() {
        val across = replay(
            buy("2024-02-29", "10", "100"), sell("2025-03-01", "10", "150"),
        ).disposals.single()
        assertThat(across.gainTerm).isEqualTo("long")
        assertThat(across.holdingDays).isEqualTo(366)
    }

    @Test
    fun `a longer holding period applies to things that are not equity`() {
        val result = replay(
            buy("2022-01-10", "10", "100"), sell("2024-01-11", "10", "150"),
            months = 36,
        )
        assertThat(result.disposals.single().gainTerm)
            .describedAs("two years is short when the threshold is three")
            .isEqualTo("short")
    }

    // --- corporate actions ---------------------------------------------------

    /**
     * A split is not an acquisition. If it reset the acquisition date, a holder
     * of ten years would be taxed as a short-term trader for the crime of owning
     * shares in a company that split them.
     */
    @Test
    fun `a split multiplies units and divides cost, keeping the original date`() {
        val result = replay(
            buy("2020-01-10", "100", "1000"),
            action("split", "2024-01-10", "2:1"),
            sell("2024-06-01", "200", "600"),
        )

        assertThat(result.lots.single().quantity).isEqualByComparingTo(BigDecimal(200))
        assertThat(result.lots.single().unitCost).isEqualByComparingTo(BigDecimal(500))

        val disposal = result.disposals.single()
        assertThat(disposal.acquiredOn)
            .describedAs("the shares were acquired in 2020, split or no split")
            .isEqualTo(LocalDate.parse("2020-01-10"))
        assertThat(disposal.costBasis)
            .describedAs("a split changes the count, never the total cost")
            .isEqualByComparingTo(BigDecimal(100_000))
        assertThat(disposal.gainTerm).isEqualTo("long")
    }

    /**
     * Bonus shares are their own acquisition at nil cost. Folding them into the
     * existing lot would both understate the gain and misdate it.
     */
    @Test
    fun `bonus shares open a new lot at nil cost, dated when they were issued`() {
        val result = replay(
            buy("2020-01-10", "100", "1000"),
            action("bonus", "2024-01-10", "1:1"),
        )

        assertThat(result.lots).hasSize(2)
        val bonus = result.lots[1]
        assertThat(bonus.quantity).isEqualByComparingTo(BigDecimal(100))
        assertThat(bonus.unitCost).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(bonus.acquiredOn).isEqualTo(LocalDate.parse("2024-01-10"))
    }

    @Test
    fun `selling after a bonus takes the original shares first`() {
        val result = replay(
            buy("2020-01-10", "100", "1000"),
            action("bonus", "2024-06-01", "1:1"),
            sell("2024-07-01", "150", "800"),
        )

        assertThat(result.disposals).hasSize(2)
        // 100 original at ₹1,000 — long-held and fully costed.
        assertThat(result.disposals[0].costBasis).isEqualByComparingTo(BigDecimal(100_000))
        assertThat(result.disposals[0].gainTerm).isEqualTo("long")
        // 50 bonus at nil cost, held a month.
        assertThat(result.disposals[1].costBasis).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.disposals[1].gainTerm).isEqualTo("short")
    }

    // --- cost basis method ---------------------------------------------------

    @Test
    fun `averaging changes the cost attributed but not the dates`() {
        val transactions = arrayOf(
            buy("2024-01-10", "100", "1000"),
            buy("2024-06-10", "100", "2000"),
            sell("2025-02-01", "100", "2500"),
        )

        val fifo = TaxLotEngine.replay(transactions.toList(), "fifo", 12).disposals.single()
        val average = TaxLotEngine.replay(transactions.toList(), "average", 12).disposals.single()

        assertThat(fifo.costBasis).isEqualByComparingTo(BigDecimal(100_000))
        assertThat(average.costBasis)
            .describedAs("(100x1000 + 100x2000) / 200 = 1500 each")
            .isEqualByComparingTo(BigDecimal(150_000))

        // Both consumed the same units, so the tax classification is identical.
        assertThat(average.acquiredOn).isEqualTo(fifo.acquiredOn)
        assertThat(average.gainTerm).isEqualTo(fifo.gainTerm)
    }

    // --- bad data ------------------------------------------------------------

    @Test
    fun `selling more than was ever bought is recorded and flagged, not dropped`() {
        val result = replay(
            buy("2024-01-10", "50", "1000"),
            sell("2024-06-01", "80", "1200"),
        )

        assertThat(result.disposals).hasSize(2)
        assertThat(result.disposals.sumOf { it.quantity })
            .describedAs("all 80 units are accounted for")
            .isEqualByComparingTo(BigDecimal(80))
        assertThat(result.disposals[1].costBasis)
            .describedAs("the unexplained 30 are assumed to have cost nothing")
            .isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.warnings).singleElement().asString()
            .contains("more units").contains("add the missing purchase")
    }

    @Test
    fun `an unreadable ratio is reported rather than silently ignored`() {
        val result = replay(
            buy("2024-01-10", "100", "1000"),
            action("split", "2024-06-01", "not a ratio"),
        )
        assertThat(result.lots.single().quantity)
            .describedAs("units are left alone rather than guessed at")
            .isEqualByComparingTo(BigDecimal(100))
        assertThat(result.warnings).singleElement().asString().contains("split ratio")
    }

    @Test
    fun `no transactions means no lots and no complaints`() {
        val result = TaxLotEngine.replay(emptyList(), "fifo", 12)
        assertThat(result.lots).isEmpty()
        assertThat(result.disposals).isEmpty()
        assertThat(result.warnings).isEmpty()
    }

    /** Interest and dividends are cash, not units — they must not open lots. */
    @Test
    fun `cash movements leave the units alone`() {
        val result = replay(
            buy("2024-01-10", "100", "1000"),
            Txn(UUID.randomUUID(), "dividend", LocalDate.parse("2024-04-01"), null, BigDecimal(5000), null),
            Txn(UUID.randomUUID(), "fee", LocalDate.parse("2024-04-02"), null, BigDecimal(120), null),
        )
        assertThat(result.lots).hasSize(1)
        assertThat(result.lots.single().remainingQty).isEqualByComparingTo(BigDecimal(100))
    }

    /**
     * Replaying is the whole safety property: the same transactions must always
     * give the same lots, whatever order they arrive in.
     */
    @Test
    fun `the result does not depend on the order transactions are supplied in`() {
        val transactions = listOf(
            buy("2024-01-10", "100", "1500"),
            sell("2025-03-01", "60", "2000"),
            buy("2024-06-10", "100", "1800"),
        )

        val forwards = TaxLotEngine.replay(transactions, "fifo", 12)
        val backwards = TaxLotEngine.replay(transactions.reversed(), "fifo", 12)

        assertThat(backwards.lots.map { it.remainingQty })
            .containsExactlyElementsOf(forwards.lots.map { it.remainingQty })
        assertThat(backwards.disposals.map { it.gain })
            .containsExactlyElementsOf(forwards.disposals.map { it.gain })
    }
}
