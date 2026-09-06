package tech.bhrigu.almira.returns

/**
 * How long something must be held before a gain counts as long-term.
 *
 * *Informational, not tax advice* (docs/01 §9). These are the ordinary Indian
 * thresholds, gathered in one place rather than scattered through the code,
 * because they change and because anyone reviewing a tax figure needs to see the
 * assumption behind it without reading the whole codebase.
 *
 * The mutual-fund case is why this takes attributes: an equity fund and a debt
 * fund sit in the same category and are treated quite differently, and the
 * distinction lives in the scheme's own `scheme_category`.
 */
object HoldingPeriod {

    /** Months after which a gain is long-term. */
    fun monthsFor(categoryCode: String, attributes: Map<String, Any?>): Int = when (categoryCode) {
        "equity", "ipo" -> TWELVE
        "mutual_funds" -> if (isEquityScheme(attributes)) TWELVE else THIRTY_SIX
        "real_estate" -> TWENTY_FOUR
        // Gold, bonds, alternatives, deposits, and anything else.
        else -> THIRTY_SIX
    }

    /** A line to sit beside the figure, so it is never a bare claim. */
    fun explain(categoryCode: String, attributes: Map<String, Any?>): String =
        "Held more than ${monthsFor(categoryCode, attributes)} months counts as long-term."

    private fun isEquityScheme(attributes: Map<String, Any?>): Boolean =
        (attributes["scheme_category"] as? String) in setOf("equity", "elss", "index")

    private const val TWELVE = 12
    private const val TWENTY_FOUR = 24
    private const val THIRTY_SIX = 36
}
