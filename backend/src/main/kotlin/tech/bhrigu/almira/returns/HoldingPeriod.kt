package tech.bhrigu.almira.returns

import tech.bhrigu.almira.tax.CapitalGainsRules
import java.time.LocalDate

/**
 * How long something must be held before a gain counts as long-term.
 *
 * *Informational, not tax advice* (docs/01 §9). The thresholds themselves live
 * in [CapitalGainsRules], keyed to the date of the transfer, because they
 * changed on 23 July 2024 and a sale is judged by the rule on its own day. This
 * stays as the narrow door the lot engine and the "if you sold today" view use.
 *
 * The mutual-fund case is why this takes attributes: an equity fund and a debt
 * fund sit in the same category and are treated quite differently, and the
 * distinction lives in the scheme's own `scheme_category`. The type is why it
 * takes a type code: a REIT and a flat are both "real estate".
 */
object HoldingPeriod {

    /** Months after which a gain is long-term, for a transfer on [on]. */
    fun monthsFor(
        categoryCode: String,
        attributes: Map<String, Any?>,
        typeCode: String? = null,
        on: LocalDate = LocalDate.now(),
    ): Int = CapitalGainsRules.holdingMonths(
        CapitalGainsRules.assetClass(
            CapitalGainsRules.Holding(typeCode ?: "", categoryCode, attributes),
        ),
        on,
    )

    /** A line to sit beside the figure, so it is never a bare claim. */
    fun explain(categoryCode: String, attributes: Map<String, Any?>, typeCode: String? = null): String =
        "Held more than ${monthsFor(categoryCode, attributes, typeCode)} months counts as long-term."
}
