package tech.bhrigu.almira.plans

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * What plans exist, and what a price would be (docs/28 §1).
 *
 * Definitions are configuration because they change with a decision, not with
 * data: nobody has decided a price, and when somebody does, it is a line here
 * and a restart — no migration, no rewrite of what a household is on.
 *
 * **One price for the family.** A plan is priced per household, never per
 * person: adding a parent, a spouse or a child never costs more, because a
 * record that leaves someone out to save money is the failure this product
 * exists to prevent. There is deliberately no per-member field to configure.
 *
 * **No price is not a price of zero.** [PlanDefinition.priceInr] null means "not
 * decided", and every screen says exactly that. Zero would be a decision.
 *
 * The configuration refuses to start if the default plan is not defined, if a
 * price is negative, or if the grace period is outside 0..365 days.
 */
@ConfigurationProperties(prefix = "almira.plans")
data class PlanProperties(
    /** The plan of every household without a household_plans row, which today is all of them. */
    val defaultPlan: String = "family",
    /** Days of ordinary use after a plan's paid-through date, unless the row says otherwise. */
    val graceDays: Int = 30,
    val definitions: Map<String, PlanDefinition> = mapOf("family" to PlanDefinition()),
) {
    init {
        require(definitions.containsKey(defaultPlan)) {
            "almira.plans.default-plan is '$defaultPlan', which is not one of almira.plans.definitions " +
                "(${definitions.keys.joinToString()})"
        }
        require(graceDays in 0..MAX_GRACE_DAYS) {
            "almira.plans.grace-days is $graceDays; it must be between 0 and $MAX_GRACE_DAYS"
        }
        definitions.forEach { (code, definition) ->
            require(PLAN_CODE.matches(code)) { "almira.plans.definitions has a plan named '$code'; use a-z, 0-9 and _" }
            require(definition.priceInr == null || definition.priceInr >= 0) {
                "almira.plans.definitions.$code.price-inr is negative"
            }
            require(definition.period in PERIODS) {
                "almira.plans.definitions.$code.period is '${definition.period}'; it must be one of ${PERIODS.joinToString()}"
            }
        }
    }

    fun definition(code: String): PlanDefinition = definitions[code] ?: definitions.getValue(defaultPlan)

    data class PlanDefinition(
        val name: String = "Family plan",
        /** Whole rupees for the whole household for one [period]. Null: no price has been decided. */
        val priceInr: Long? = null,
        val period: String = "year",
    )

    companion object {
        const val MAX_GRACE_DAYS = 365
        val PERIODS = setOf("month", "year")

        /** The same rule as household_plans.plan_code (V101). */
        val PLAN_CODE = Regex("^[a-z][a-z0-9_]{1,39}$")
    }
}
