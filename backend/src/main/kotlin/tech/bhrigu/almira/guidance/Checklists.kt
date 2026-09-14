package tech.bhrigu.almira.guidance

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.household.HouseholdService
import java.util.UUID

/**
 * Checklists that tick themselves (P-23, docs/03 §1.5).
 *
 * Nothing on a checklist is ticked by hand. Each item is a question asked of
 * the records the caller can see, so a tick means the thing is there — and
 * disappears if it is removed. The words, and the short explainer beside each
 * item, live in the client in the reader's language; the server says only
 * which item codes are done.
 *
 * Two lists: getting started, and what a family would need on the worst day.
 * Deliberately short. A list of forty items is a list nobody starts.
 */
data class GuidanceChecklistItem(val code: String, val done: Boolean)

data class GuidanceChecklist(val code: String, val items: List<GuidanceChecklistItem>, val done: Int, val total: Int)

@Service
class ChecklistService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {
    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<GuidanceChecklist> {
        val household = households.get(householdId)
        val p = mapOf("hid" to householdId)
        fun exists(sql: String) = jdbc.queryForObject("select exists ($sql)", p, Boolean::class.java) == true

        val anyRecord = exists(
            """
            select 1 from investments where household_id = :hid and deleted_at is null
            union all select 1 from liabilities where household_id = :hid and deleted_at is null
            union all select 1 from accounts where household_id = :hid and deleted_at is null
            """.trimIndent(),
        )
        val tookCheck = jdbc.queryForObject(
            "select exists (select 1 from readiness_check_answers)", emptyMap<String, Any>(), Boolean::class.java,
        ) == true

        val gettingStarted = listOf(
            GuidanceChecklistItem("readiness_check", tookCheck),
            GuidanceChecklistItem("first_record", anyRecord),
            GuidanceChecklistItem("second_person", household.memberCount > 1),
            GuidanceChecklistItem("a_goal", exists("select 1 from goals where household_id = :hid and deleted_at is null")),
        )

        val forTheFamily = listOf(
            GuidanceChecklistItem(
                "nominee",
                exists(
                    """
                    select 1 from investment_nominees n join investments i on i.id = n.investment_id
                    where i.household_id = :hid and i.deleted_at is null
                    """.trimIndent(),
                ),
            ),
            // Only that a pointer is sealed, never what it says: the server
            // cannot read it, and this does not try.
            GuidanceChecklistItem(
                "where_papers",
                exists("select 1 from sealed_values where household_id = :hid and field_key = 'original_location'"),
            ),
            GuidanceChecklistItem(
                "a_contact",
                exists("select 1 from contacts where household_id = :hid and deleted_at is null"),
            ),
            GuidanceChecklistItem(
                "trusted_person",
                exists("select 1 from emergency_contacts where household_id = :hid"),
            ),
            GuidanceChecklistItem(
                "will",
                exists(
                    """
                    select 1 from estate_documents where household_id = :hid and deleted_at is null
                      and kind = 'will' and status <> 'revoked'
                    """.trimIndent(),
                ),
            ),
        )

        return listOf(
            GuidanceChecklist("getting_started", gettingStarted, gettingStarted.count { it.done }, gettingStarted.size),
            GuidanceChecklist("for_the_family", forTheFamily, forTheFamily.count { it.done }, forTheFamily.size),
        )
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/guidance")
class ChecklistController(private val service: ChecklistService) {

    @GetMapping("/checklists")
    fun checklists(@PathVariable householdId: UUID): List<GuidanceChecklist> = service.list(householdId)
}
