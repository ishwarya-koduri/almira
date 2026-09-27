package tech.almira.plans

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import tech.almira.household.HouseholdService
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** What a price is, or that there is none yet. Always for the whole household. */
data class PlanPrice(
    /** False until somebody decides a price. Then [amountInr] and [period] are set. */
    val decided: Boolean,
    val amountInr: Long?,
    val period: String?,
    /** Always true: one price covers everyone in the household (docs/28 §1). */
    val perHousehold: Boolean = true,
    val summary: String,
)

data class PlanOffer(val code: String, val name: String, val price: PlanPrice)

/** The price page, before there is a price. */
data class PlanCatalogue(
    val plans: List<PlanOffer>,
    /** False: there is no payment gateway, so nothing here can take money. */
    val paymentsEnabled: Boolean,
    val neverDo: List<String>,
    val alwaysAvailable: List<String>,
    val promise: String,
)

data class HouseholdPlanView(
    val planCode: String,
    val planName: String,
    /** `active`, `grace` (ended, still fully usable) or `read_only`. */
    val state: String,
    /** The last day paid for. Null: nothing ends. */
    val paidThrough: LocalDate?,
    /** The first day the household is read-only. Null: never. */
    val readOnlyFrom: LocalDate?,
    val readOnly: Boolean,
    val price: PlanPrice,
    val alwaysAvailable: List<String>,
    val promise: String,
)

/**
 * A household's plan, and whether it can still be changed (docs/28 §2).
 *
 * Worked out on every read from `paid_through`, the grace period and today's
 * date in India — never stored as a status, so nothing has to run for a plan
 * to end and nothing that fails to run can end one early.
 *
 * Read on the runtime connection with the caller's identity, so row-level
 * security decides who may know a household's plan: a non-member gets no row,
 * which reads as the default plan, and the household endpoint's own 404 follows.
 */
@Service
class HouseholdPlanService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val props: PlanProperties,
) {
    private val clock: Clock = Clock.system(INDIA)

    @Transactional(readOnly = true)
    fun forHousehold(householdId: UUID): HouseholdPlanView {
        households.get(householdId)
        return view(stored(householdId))
    }

    fun catalogue(): PlanCatalogue = PlanCatalogue(
        plans = props.definitions.map { (code, definition) -> PlanOffer(code, definition.name, price(definition)) },
        paymentsEnabled = false,
        neverDo = NEVER_DO,
        alwaysAvailable = ALWAYS_AVAILABLE,
        promise = PROMISE,
    )

    /**
     * For the read-only guard: true only when a plan row exists, is visible to
     * the caller, and its grace period is over. Must run inside a transaction
     * that carries the request's identity.
     */
    fun isReadOnly(householdId: UUID): Boolean = view(stored(householdId)).readOnly

    private data class Stored(val planCode: String, val paidThrough: LocalDate?, val graceDays: Int?)

    private fun stored(householdId: UUID): Stored? = jdbc.query(
        "select plan_code, paid_through, grace_days from household_plans where household_id = :hid",
        mapOf("hid" to householdId),
    ) { rs, _ ->
        Stored(
            planCode = rs.getString("plan_code"),
            paidThrough = rs.getDate("paid_through")?.toLocalDate(),
            graceDays = rs.getObject("grace_days") as Int?,
        )
    }.firstOrNull()

    private fun view(stored: Stored?): HouseholdPlanView {
        val code = stored?.planCode?.takeIf { props.definitions.containsKey(it) } ?: props.defaultPlan
        val definition = props.definition(code)
        val paidThrough = stored?.paidThrough
        val readOnlyFrom = paidThrough?.plusDays((stored?.graceDays ?: props.graceDays).toLong() + 1)
        val today = LocalDate.now(clock)
        val state = when {
            paidThrough == null || !today.isAfter(paidThrough) -> "active"
            readOnlyFrom != null && today.isBefore(readOnlyFrom) -> "grace"
            else -> "read_only"
        }
        return HouseholdPlanView(
            planCode = code,
            planName = definition.name,
            state = state,
            paidThrough = paidThrough,
            readOnlyFrom = readOnlyFrom,
            readOnly = state == "read_only",
            price = price(definition),
            alwaysAvailable = ALWAYS_AVAILABLE,
            promise = PROMISE,
        )
    }

    private fun price(definition: PlanProperties.PlanDefinition): PlanPrice =
        if (definition.priceInr == null) {
            PlanPrice(
                decided = false, amountInr = null, period = null,
                summary = "No price has been decided yet. There is nothing to pay.",
            )
        } else {
            PlanPrice(
                decided = true, amountInr = definition.priceInr, period = definition.period,
                summary = "One price for the whole family, however many people are in it.",
            )
        }

    companion object {
        val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

        const val PROMISE = "Your family's record is never held hostage."

        /** What a lapsed plan never takes away. The guard's allowlist is what makes it true. */
        val ALWAYS_AVAILABLE = listOf(
            "Seeing everything you could see before",
            "The family handbook",
            "Download everything",
            "Closing your account, and leaving a household",
            "Emergency access, and naming who carries the household on",
        )

        val NEVER_DO = listOf(
            "Sell or rent your data, to anyone",
            "Give you investment advice or sell you a product",
            "Move your money: Almira can't reach your accounts",
        )
    }
}

@RestController
class HouseholdPlanController(private val plans: HouseholdPlanService) {

    /** The plan a household is on, and whether it is read-only. Members only; 404 otherwise. */
    @GetMapping("/api/v1/households/{householdId}/plan")
    fun householdPlan(@PathVariable householdId: UUID): HouseholdPlanView = plans.forHousehold(householdId)

    /** The price page. There is no price yet, and it says so. */
    @GetMapping("/api/v1/plans")
    fun planCatalogue(): PlanCatalogue = plans.catalogue()
}
