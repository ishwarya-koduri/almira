package tech.almira.guidance

import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import java.util.UUID

/**
 * The second family member's first screen (X-80, docs/03 §1.4).
 *
 * Three cards, every number in them counted from the real visibility rules
 * rather than described: what you will see (records you can read and do not
 * own), what stays yours (what you own that nobody else can read), and what
 * they will see of yours (what you own that is shared). The counting reads
 * through row-level security as the caller, so "you'll see 4 things" is exactly
 * the four things the database lets them read — never a promise the policies
 * do not keep.
 */
data class WelcomePreviewRecord(val title: String, val kind: String)

data class WelcomeCountCard(val count: Int, val preview: List<WelcomePreviewRecord>)

data class WelcomeResponse(
    val householdName: String,
    /** The household owner's name on the roster; null if the roster has none. */
    val ownerName: String?,
    val myRole: String,
    /** The owner never gets a welcome to a household they made. */
    val due: Boolean,
    val seen: Boolean,
    /** Records you can read that you do not own. At most three titles previewed. */
    val youWillSee: WelcomeCountCard,
    /** Records you own that are private: nobody else, not even an admin, can read them. */
    val staysYours: WelcomeCountCard,
    /** Records you own that are shared with the household or with named people. */
    val theyWillSeeOfYours: WelcomeCountCard,
    /** What new entries default to for you. */
    val defaultVisibility: String,
)

@Service
class WelcomeService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val userContext: RequestUserContext,
) {
    @Transactional(readOnly = true)
    fun get(householdId: UUID): WelcomeResponse {
        val userId = userContext.require()
        val household = households.get(householdId)
        val me = household.myMemberId
        val params = MapSqlParameterSource().addValue("hid", householdId).addValue("me", me).addValue("u", userId)

        val ownerName = jdbc.query(
            """
            select m.display_name from household_memberships hm
            join members m on m.household_id = hm.household_id and m.user_id = hm.user_id and m.deleted_at is null
            where hm.household_id = :hid and hm.role = 'owner' and hm.status = 'active'
            order by hm.created_at limit 1
            """.trimIndent(),
            params,
        ) { rs, _ -> rs.getString(1) }.firstOrNull()

        val seen = jdbc.queryForObject(
            "select exists (select 1 from first_session_progress where household_id = :hid and welcome_seen_at is not null)",
            params, Boolean::class.java,
        ) == true

        val defaultVisibility = jdbc.query(
            "select default_visibility from users where id = :u", params,
        ) { rs, _ -> rs.getString(1) }.firstOrNull() ?: household.defaultVisibility

        return WelcomeResponse(
            householdName = household.name,
            ownerName = ownerName,
            myRole = household.myRole,
            due = household.myRole != "owner" && !seen,
            seen = seen,
            youWillSee = card(params, "not mine"),
            staysYours = card(params, "mine private"),
            theyWillSeeOfYours = card(params, "mine shared"),
            defaultVisibility = defaultVisibility,
        )
    }

    @Transactional
    fun markSeen(householdId: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        jdbc.update(
            """
            insert into first_session_progress (user_id, household_id, welcome_seen_at)
            values (:u, :hid, now())
            on conflict (user_id, household_id) do update
              set welcome_seen_at = coalesce(first_session_progress.welcome_seen_at, now())
            """.trimIndent(),
            mapOf("u" to userId, "hid" to householdId),
        )
    }

    /**
     * One card, over holdings, loans and accounts together. [which] is one of
     * three fixed filters, never caller input.
     */
    private fun card(params: MapSqlParameterSource, which: String): WelcomeCountCard {
        val filter = when (which) {
            "not mine" -> "not r.mine"
            "mine private" -> "r.mine and r.visibility = 'private'"
            "mine shared" -> "r.mine and r.visibility <> 'private'"
            else -> error("unknown welcome card $which")
        }
        val rows = jdbc.query(
            """
            with r as (
              select i.title, 'investment' as kind, i.visibility, i.created_at,
                     exists (select 1 from investment_ownerships o
                             where o.investment_id = i.id and o.member_id = cast(:me as uuid)) as mine
              from investments i where i.household_id = :hid and i.deleted_at is null
              union all
              select l.title, 'liability', l.visibility, l.created_at,
                     exists (select 1 from liability_holders h
                             where h.liability_id = l.id and h.member_id = cast(:me as uuid))
              from liabilities l where l.household_id = :hid and l.deleted_at is null
              union all
              select a.label, 'account', a.visibility, a.created_at,
                     exists (select 1 from account_holders h
                             where h.account_id = a.id and h.member_id = cast(:me as uuid))
              from accounts a where a.household_id = :hid and a.deleted_at is null
            )
            select r.title, r.kind, count(*) over () as total
            from r where $filter
            order by r.created_at desc
            limit 3
            """.trimIndent(),
            params,
        ) { rs, _ -> Triple(rs.getString("title"), rs.getString("kind"), rs.getInt("total")) }
        return WelcomeCountCard(
            count = rows.firstOrNull()?.third ?: 0,
            preview = rows.map { WelcomePreviewRecord(it.first, it.second) },
        )
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/welcome")
class WelcomeController(private val service: WelcomeService) {

    @GetMapping
    fun welcome(@PathVariable householdId: UUID): WelcomeResponse = service.get(householdId)

    @PostMapping("/seen")
    fun markWelcomeSeen(@PathVariable householdId: UUID): ResponseEntity<Void> {
        service.markSeen(householdId)
        return ResponseEntity.noContent().build()
    }
}
