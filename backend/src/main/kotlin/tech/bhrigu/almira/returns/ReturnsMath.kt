package tech.bhrigu.almira.returns

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * Return calculations, as pure functions.
 *
 * The guiding rule from docs/01 §8: report a number only where the data supports
 * it, and say why when it does not. Every function here returns null rather than
 * a plausible-looking figure derived from too little — a fabricated return is
 * worse than a blank, because a blank prompts someone to add a valuation and a
 * wrong number does not.
 */
object ReturnsMath {

    data class CashFlow(val date: LocalDate, val amount: Double)

    /** Absolute return: what it is worth now against what went in. */
    fun absoluteReturn(invested: BigDecimal?, currentValue: BigDecimal?): BigDecimal? {
        if (invested == null || currentValue == null || invested.signum() <= 0) return null
        return currentValue.subtract(invested)
            .multiply(BigDecimal(100))
            .divide(invested, 2, RoundingMode.HALF_UP)
    }

    /**
     * CAGR — only meaningful for a single lump sum held over a period.
     *
     * Deliberately refused under a year: annualising two months of growth
     * produces enormous, meaningless percentages that look like performance.
     */
    fun cagr(
        invested: BigDecimal?,
        currentValue: BigDecimal?,
        from: LocalDate?,
        to: LocalDate,
    ): BigDecimal? {
        if (invested == null || currentValue == null || from == null) return null
        if (invested.signum() <= 0 || currentValue.signum() < 0) return null

        val years = ChronoUnit.DAYS.between(from, to).toDouble() / 365.25
        if (years < 1.0) return null

        val growth = currentValue.toDouble() / invested.toDouble()
        if (growth <= 0) return null

        val rate = (growth.pow(1.0 / years) - 1.0) * 100.0
        if (!rate.isFinite()) return null
        return BigDecimal(rate, MathContext(6)).setScale(2, RoundingMode.HALF_UP)
    }

    /**
     * XIRR — the annualised rate that makes a series of dated flows net to zero.
     *
     * Newton-Raphson converges in a handful of steps for well-behaved series and
     * wanders off for anything else, so it falls back to bisection, which is
     * slower and always converges within a bracket. A SIP with a dozen years of
     * monthly contributions is exactly the case where Newton alone diverges, and
     * that is the case this product exists to compute.
     *
     * Returns null when the answer would be meaningless: fewer than two flows, or
     * all flows the same direction (money that only ever went out has no rate of
     * return until you say what it is worth).
     */
    fun xirr(flows: List<CashFlow>): BigDecimal? {
        if (flows.size < 2) return null
        val hasOutflow = flows.any { it.amount < 0 }
        val hasInflow = flows.any { it.amount > 0 }
        if (!hasOutflow || !hasInflow) return null

        val start = flows.minOf { it.date }
        val years = flows.map { ChronoUnit.DAYS.between(start, it.date).toDouble() / 365.0 }
        val amounts = flows.map { it.amount }

        newton(amounts, years)?.let { return asPercent(it) }
        bisect(amounts, years)?.let { return asPercent(it) }
        return null
    }

    private fun npv(rate: Double, amounts: List<Double>, years: List<Double>): Double =
        amounts.indices.sumOf { amounts[it] / (1.0 + rate).pow(years[it]) }

    private fun newton(amounts: List<Double>, years: List<Double>): Double? {
        var rate = 0.1
        repeat(60) {
            val value = npv(rate, amounts, years)
            if (abs(value) < TOLERANCE) return rate
            val derivative = amounts.indices.sumOf {
                -years[it] * amounts[it] / (1.0 + rate).pow(years[it] + 1.0)
            }
            if (abs(derivative) < 1e-12) return null
            val next = rate - value / derivative
            // Below -100% the discount factor is undefined; stop rather than
            // return a number produced from NaN.
            if (!next.isFinite() || next <= -0.9999) return null
            if (abs(next - rate) < 1e-10) return next
            rate = next
        }
        return null
    }

    private fun bisect(amounts: List<Double>, years: List<Double>): Double? {
        var low = -0.9999
        var high = 10.0
        var atLow = npv(low, amounts, years)
        var atHigh = npv(high, amounts, years)

        // Widen once for the genuinely extreme cases before giving up.
        if (atLow * atHigh > 0) {
            high = 100.0
            atHigh = npv(high, amounts, years)
            if (atLow * atHigh > 0) return null
        }

        repeat(200) {
            val mid = (low + high) / 2.0
            val atMid = npv(mid, amounts, years)
            if (abs(atMid) < TOLERANCE || (high - low) < 1e-10) return mid
            if (atLow * atMid < 0) {
                high = mid; atHigh = atMid
            } else {
                low = mid; atLow = atMid
            }
        }
        return null
    }

    private fun asPercent(rate: Double): BigDecimal? {
        if (!rate.isFinite()) return null
        val percent = rate * 100.0
        // A four-digit percentage is arithmetic, not a return. Something is
        // wrong with the flows, and printing it would only mislead.
        if (abs(percent) > 10_000) return null
        return BigDecimal(percent, MathContext(8)).setScale(2, RoundingMode.HALF_UP)
    }

    private const val TOLERANCE = 1e-9
}
