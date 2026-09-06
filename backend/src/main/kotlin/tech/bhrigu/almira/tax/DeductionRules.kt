package tech.bhrigu.almira.tax

import java.math.BigDecimal

/**
 * Which holdings count towards which deduction, and up to how much.
 *
 * *Informational, not tax advice* (docs/01 §9). Everything specific to Indian
 * tax law lives here, in one table, so it can be corrected in one place when the
 * rules change — and so anyone checking a figure can see the whole assumption at
 * a glance rather than reconstructing it from queries.
 *
 * A holding counts when its TYPE qualifies and, where the type offers one, the
 * person has ticked the corresponding flag. The flag matters: a PPF account
 * belonging to someone who has already exhausted 80C elsewhere should not
 * silently inflate their meter.
 */
object DeductionRules {

    data class Section(
        val code: String,
        val label: String,
        val limit: BigDecimal,
        val description: String,
        /** Investment type codes that can count towards it. */
        val typeCodes: Set<String>,
        /** When set, the attribute the person must have ticked. */
        val requiresFlag: String?,
        /** Attributes to read a declared annual figure from, in order of preference. */
        val declaredAmountKeys: List<String>,
    )

    val SECTIONS: List<Section> = listOf(
        Section(
            code = "80C",
            label = "Section 80C",
            limit = BigDecimal(150_000),
            description = "Life insurance, PPF, EPF, ELSS, NSC, Sukanya Samriddhi, " +
                "tax-saver deposits and home-loan principal, up to ₹1,50,000 together.",
            typeCodes = setOf(
                "ppf", "epf", "ssy", "nsc_kvp", "tax_saver_fd",
                "insurance_term", "insurance_endowment", "insurance_ulip",
            ),
            requiresFlag = null,
            declaredAmountKeys = listOf("yearly_contribution", "premium_amount"),
        ),
        Section(
            code = "80CCD1B",
            label = "Section 80CCD(1B)",
            limit = BigDecimal(50_000),
            description = "An extra ₹50,000 for NPS, over and above 80C.",
            typeCodes = setOf("nps"),
            requiresFlag = "claimed_80ccd1b",
            declaredAmountKeys = listOf("yearly_contribution"),
        ),
        Section(
            code = "80D",
            label = "Section 80D",
            limit = BigDecimal(25_000),
            description = "Health insurance premiums, up to ₹25,000 — more if the " +
                "person insured is a senior citizen.",
            typeCodes = setOf("insurance_health"),
            requiresFlag = null,
            declaredAmountKeys = listOf("premium_amount"),
        ),
    )

    /** ELSS is a mutual fund, so it qualifies by scheme rather than by type. */
    fun countsTowards80C(typeCode: String, attributes: Map<String, Any?>): Boolean =
        typeCode in SECTIONS.first { it.code == "80C" }.typeCodes ||
            (typeCode.startsWith("mf_") && attributes["scheme_category"] == "elss")

    fun qualifies(section: Section, typeCode: String, attributes: Map<String, Any?>): Boolean {
        val byType = if (section.code == "80C") {
            countsTowards80C(typeCode, attributes)
        } else {
            typeCode in section.typeCodes
        }
        if (!byType) return false

        // An explicit "no" is respected; an absent flag is treated as "yes" for
        // types that only exist for this purpose, since asking someone to tick a
        // box on a tax-saver FD to say it is a tax-saver FD is busywork.
        val flag = section.requiresFlag ?: return true
        return attributes[flag] != false
    }

    /** The interest deduction, which comes from a loan rather than a holding. */
    val HOME_LOAN_INTEREST = Section(
        code = "24B",
        label = "Section 24(b)",
        limit = BigDecimal(200_000),
        description = "Interest on a home loan for a property you live in, up to ₹2,00,000.",
        typeCodes = setOf("home"),
        requiresFlag = null,
        declaredAmountKeys = emptyList(),
    )
}
