package tech.bhrigu.almira.returns

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Replays transactions into tax lots and disposals.
 *
 * Deliberately pure: transactions in, lots and disposals out, no database and no
 * clock. Lots are always REBUILT from the movements rather than mutated in
 * place, so they can never drift from what actually happened — and a bug here is
 * reproducible from a list of transactions rather than from a database state
 * nobody can reconstruct.
 *
 * *Informational, not tax advice* (docs/01 §9). The rules encoded here are the
 * ordinary Indian ones and are stated in one place so they can be corrected
 * without hunting through the codebase.
 */
object TaxLotEngine {

    data class Txn(
        val id: UUID,
        val type: String,
        val date: LocalDate,
        val quantity: BigDecimal?,
        val amount: BigDecimal?,
        val price: BigDecimal?,
        val ratio: String? = null,
    )

    data class Lot(
        val sequence: Int,
        val acquiredOn: LocalDate,
        val quantity: BigDecimal,
        val unitCost: BigDecimal,
        val remainingQty: BigDecimal,
        val sourceTxnId: UUID?,
    )

    data class Disposal(
        val lotSequence: Int?,
        val sellTxnId: UUID,
        val quantity: BigDecimal,
        val costBasis: BigDecimal,
        val proceeds: BigDecimal,
        val acquiredOn: LocalDate,
        val disposedOn: LocalDate,
        val holdingDays: Long,
        val gainTerm: String,
        val gain: BigDecimal,
    )

    /**
     * Warnings are returned rather than thrown. A sale of more units than were
     * ever recorded is bad data, not a crash: the right response is to record
     * what can be recorded and tell the person what looks wrong, because the
     * alternative is a screen that shows nothing and explains nothing.
     */
    data class Replay(
        val lots: List<Lot>,
        val disposals: List<Disposal>,
        val warnings: List<String>,
    )

    private val QTY = 6
    private val MONEY = 4

    fun replay(
        transactions: List<Txn>,
        costBasisMethod: String,
        holdingPeriodMonths: Int,
    ): Replay {
        // Date first, then insertion order, so two transactions on one day
        // replay in the order they were recorded rather than at random.
        val ordered = transactions.sortedWith(compareBy({ it.date }, { it.id.toString() }))

        val lots = mutableListOf<MutableLot>()
        val disposals = mutableListOf<Disposal>()
        val warnings = mutableListOf<String>()
        var sequence = 0

        ordered.forEach { txn ->
            when (txn.type) {
                "buy" -> {
                    val quantity = txn.quantity ?: return@forEach
                    if (quantity.signum() <= 0) return@forEach
                    val unitCost = txn.price
                        ?: txn.amount?.divide(quantity, QTY, RoundingMode.HALF_UP)
                        ?: BigDecimal.ZERO
                    lots += MutableLot(sequence++, txn.date, quantity, unitCost, quantity, txn.id)
                }

                "sell" -> {
                    val quantity = txn.quantity ?: return@forEach
                    if (quantity.signum() <= 0) return@forEach
                    val unitProceeds = txn.price
                        ?: txn.amount?.divide(quantity, QTY, RoundingMode.HALF_UP)
                        ?: BigDecimal.ZERO
                    consume(
                        lots, txn, quantity, unitProceeds, costBasisMethod,
                        holdingPeriodMonths, disposals, warnings,
                    )
                }

                // A split does not create an acquisition. Existing lots keep
                // their dates — which matters, because the holding period of
                // shares you have owned for years does not restart because the
                // company split them.
                "split" -> {
                    val factor = ratioFactor(txn.ratio)
                    if (factor == null) {
                        warnings += "Couldn't read the split ratio '${txn.ratio}' on ${txn.date}."
                        return@forEach
                    }
                    lots.forEach { lot ->
                        lot.quantity = lot.quantity.multiply(factor).setScale(QTY, RoundingMode.HALF_UP)
                        lot.remainingQty = lot.remainingQty.multiply(factor).setScale(QTY, RoundingMode.HALF_UP)
                        lot.unitCost = lot.unitCost.divide(factor, QTY, RoundingMode.HALF_UP)
                    }
                }

                // Bonus shares are their OWN acquisition, at nil cost, with their
                // own holding period. Folding them into existing lots would
                // understate a later gain and misdate it — India treats them as
                // newly acquired on the bonus date.
                "bonus" -> {
                    val held = lots.fold(BigDecimal.ZERO) { acc, lot -> acc + lot.remainingQty }
                    val factor = ratioFactor(txn.ratio)
                    val bonusQty = txn.quantity
                        ?: factor?.let { held.multiply(it).setScale(QTY, RoundingMode.HALF_UP) }
                    if (bonusQty == null || bonusQty.signum() <= 0) {
                        warnings += "Couldn't work out the bonus quantity on ${txn.date}."
                        return@forEach
                    }
                    lots += MutableLot(
                        sequence++, txn.date, bonusQty, BigDecimal.ZERO, bonusQty, txn.id,
                    )
                }

                else -> Unit // cash movements do not touch units
            }
        }

        return Replay(
            lots = lots.map {
                Lot(it.sequence, it.acquiredOn, it.quantity, it.unitCost, it.remainingQty, it.sourceTxnId)
            },
            disposals = disposals,
            warnings = warnings,
        )
    }

    /**
     * Consumes lots for a sale.
     *
     * Selection is ALWAYS oldest-first. That is not a preference — for listed
     * shares and mutual funds it is how Indian capital gains are computed, and
     * the holding period of the specific units sold decides whether the gain is
     * short or long.
     *
     * `costBasisMethod` therefore affects only the COST attributed to those
     * units: `fifo` uses each lot's own cost, `average` uses the weighted average
     * across open lots at the moment of sale. Averaging is an accounting view,
     * offered because people keep books that way; the dates come from FIFO
     * regardless, so the tax classification does not change with it.
     */
    private fun consume(
        lots: MutableList<MutableLot>,
        txn: Txn,
        quantity: BigDecimal,
        unitProceeds: BigDecimal,
        costBasisMethod: String,
        holdingPeriodMonths: Int,
        disposals: MutableList<Disposal>,
        warnings: MutableList<String>,
    ) {
        val averageCost = if (costBasisMethod == "average") weightedAverageCost(lots) else null
        var remaining = quantity

        for (lot in lots) {
            if (remaining.signum() <= 0) break
            if (lot.remainingQty.signum() <= 0) continue

            val taken = minOf(remaining, lot.remainingQty)
            val unitCost = averageCost ?: lot.unitCost
            val costBasis = taken.multiply(unitCost).setScale(MONEY, RoundingMode.HALF_UP)
            val proceeds = taken.multiply(unitProceeds).setScale(MONEY, RoundingMode.HALF_UP)
            val heldDays = ChronoUnit.DAYS.between(lot.acquiredOn, txn.date)

            disposals += Disposal(
                lotSequence = lot.sequence,
                sellTxnId = txn.id,
                quantity = taken,
                costBasis = costBasis,
                proceeds = proceeds,
                acquiredOn = lot.acquiredOn,
                disposedOn = txn.date,
                holdingDays = heldDays,
                gainTerm = term(lot.acquiredOn, txn.date, holdingPeriodMonths),
                gain = proceeds - costBasis,
            )

            lot.remainingQty = lot.remainingQty - taken
            remaining -= taken
        }

        if (remaining.signum() > 0) {
            // Recorded anyway, at nil cost, so the proceeds are not silently lost
            // from the realized total — and flagged, because the real cause is
            // almost always a missing buy.
            val proceeds = remaining.multiply(unitProceeds).setScale(MONEY, RoundingMode.HALF_UP)
            disposals += Disposal(
                lotSequence = null,
                sellTxnId = txn.id,
                quantity = remaining,
                costBasis = BigDecimal.ZERO,
                proceeds = proceeds,
                acquiredOn = txn.date,
                disposedOn = txn.date,
                holdingDays = 0,
                gainTerm = "short",
                gain = proceeds,
            )
            warnings += "Sold ${remaining.stripTrailingZeros().toPlainString()} more units on " +
                "${txn.date} than we have a record of buying. The gain assumes they cost " +
                "nothing — add the missing purchase and it'll correct itself."
        }
    }

    private fun weightedAverageCost(lots: List<MutableLot>): BigDecimal {
        val open = lots.filter { it.remainingQty.signum() > 0 }
        val quantity = open.fold(BigDecimal.ZERO) { acc, lot -> acc + lot.remainingQty }
        if (quantity.signum() <= 0) return BigDecimal.ZERO
        val cost = open.fold(BigDecimal.ZERO) { acc, lot -> acc + lot.remainingQty.multiply(lot.unitCost) }
        return cost.divide(quantity, QTY, RoundingMode.HALF_UP)
    }

    /**
     * Short or long.
     *
     * Measured in months from the acquisition date, not in days: "held for more
     * than twelve months" is how the rule is written, and 365 days is not the
     * same thing in a leap year.
     */
    private fun term(acquiredOn: LocalDate, disposedOn: LocalDate, months: Int): String =
        if (disposedOn.isAfter(acquiredOn.plusMonths(months.toLong()))) "long" else "short"

    /** "2:1" -> 2, "1:1" -> 1. Also accepts a bare number. */
    private fun ratioFactor(ratio: String?): BigDecimal? {
        val text = ratio?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            if (":" in text) {
                val (a, b) = text.split(":", limit = 2)
                BigDecimal(a.trim()).divide(BigDecimal(b.trim()), QTY, RoundingMode.HALF_UP)
            } else {
                BigDecimal(text)
            }
        }.getOrNull()?.takeIf { it.signum() > 0 }
    }

    private class MutableLot(
        val sequence: Int,
        val acquiredOn: LocalDate,
        var quantity: BigDecimal,
        var unitCost: BigDecimal,
        var remainingQty: BigDecimal,
        val sourceTxnId: UUID?,
    )
}
