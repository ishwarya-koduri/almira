package tech.bhrigu.almira.search

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import java.math.BigDecimal
import java.util.UUID

data class SearchHit(
    val id: UUID,
    val title: String,
    val subtitle: String?,
    val amount: BigDecimal?,
    val amountFormatted: String?,
    /** Where the client should navigate. */
    val route: String,
)

data class SearchResults(
    val query: String,
    val investments: List<SearchHit>,
    val liabilities: List<SearchHit>,
    val accounts: List<SearchHit>,
    val people: List<SearchHit>,
    val total: Int,
)

/**
 * One search across everything (docs/03 §9).
 *
 * Every query runs through the caller's own row-level security, so search is not
 * a way around visibility — a private record simply is not among the rows. That
 * matters more here than anywhere else: search is the classic side-channel,
 * because a result count or a stray title leaks the existence of something even
 * when the detail page is properly locked.
 *
 * Matching is trigram-based on the fields a person would actually remember: a
 * title, a bank, an amount they typed. Attributes are searched as text so a
 * policy number or a folio finds its record, which is usually what someone has
 * in their hand when they search.
 */
@Service
class SearchService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {

    @Transactional(readOnly = true)
    fun search(householdId: UUID, query: String, limitPerGroup: Int = 8): SearchResults {
        households.get(householdId)
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            return SearchResults(trimmed, emptyList(), emptyList(), emptyList(), emptyList(), 0)
        }

        val params = MapSqlParameterSource()
            .addValue("hid", householdId)
            .addValue("q", trimmed)
            .addValue("limit", limitPerGroup.coerceIn(1, 25))

        val investments = jdbc.query(
            """
            select i.id, i.title, t.label as type_label, inst.name as institution_name,
                   v.effective_value
            from investments i
            join investment_types t on t.id = i.type_id
            left join institutions inst on inst.id = i.institution_id
            left join investment_value v on v.investment_id = i.id
            where i.household_id = :hid and i.deleted_at is null
              and ( i.title ilike '%' || :q || '%'
                 or t.label ilike '%' || :q || '%'
                 or coalesce(inst.name, '') ilike '%' || :q || '%'
                 or coalesce(i.storage_location, '') ilike '%' || :q || '%'
                 or i.attributes::text ilike '%' || :q || '%' )
            order by i.title
            limit :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            SearchHit(
                id = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                subtitle = listOfNotNull(
                    rs.getString("type_label"), rs.getString("institution_name"),
                ).joinToString(" · "),
                amount = rs.getBigDecimal("effective_value"),
                amountFormatted = rs.getBigDecimal("effective_value")?.let(IndianNumbers::rupees),
                route = "investments",
            )
        }

        val liabilities = jdbc.query(
            """
            select l.id, l.title, l.kind, inst.name as lender_name, lc.outstanding
            from liabilities l
            join liability_current lc on lc.liability_id = l.id
            left join institutions inst on inst.id = l.institution_id
            where l.household_id = :hid and l.deleted_at is null
              and ( l.title ilike '%' || :q || '%'
                 or l.kind ilike '%' || :q || '%'
                 or coalesce(inst.name, '') ilike '%' || :q || '%' )
            order by l.title
            limit :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            SearchHit(
                id = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                subtitle = listOfNotNull(
                    rs.getString("kind").replace('_', ' '), rs.getString("lender_name"),
                ).joinToString(" · "),
                amount = rs.getBigDecimal("outstanding"),
                amountFormatted = rs.getBigDecimal("outstanding")?.let(IndianNumbers::rupees),
                route = "liabilities",
            )
        }

        val accounts = jdbc.query(
            """
            select a.id, a.label, a.account_kind, a.number_masked, inst.name as institution_name
            from accounts a
            left join institutions inst on inst.id = a.institution_id
            where a.household_id = :hid and a.deleted_at is null
              and ( a.label ilike '%' || :q || '%'
                 or coalesce(a.number_masked, '') ilike '%' || :q || '%'
                 or coalesce(a.ifsc, '') ilike '%' || :q || '%'
                 or coalesce(inst.name, '') ilike '%' || :q || '%' )
            order by a.label
            limit :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            SearchHit(
                id = rs.getObject("id", UUID::class.java),
                title = rs.getString("label"),
                subtitle = listOfNotNull(
                    rs.getString("institution_name"), rs.getString("number_masked"),
                ).joinToString(" · "),
                amount = null, amountFormatted = null, route = "accounts",
            )
        }

        val people = jdbc.query(
            """
            select m.id, m.display_name, m.relationship
            from members m
            where m.household_id = :hid and m.deleted_at is null
              and ( m.display_name ilike '%' || :q || '%'
                 or coalesce(m.relationship, '') ilike '%' || :q || '%' )
            order by m.display_name
            limit :limit
            """.trimIndent(),
            params,
        ) { rs, _ ->
            SearchHit(
                id = rs.getObject("id", UUID::class.java),
                title = rs.getString("display_name"),
                subtitle = rs.getString("relationship"),
                amount = null, amountFormatted = null, route = "family",
            )
        }

        return SearchResults(
            query = trimmed,
            investments = investments,
            liabilities = liabilities,
            accounts = accounts,
            people = people,
            total = investments.size + liabilities.size + accounts.size + people.size,
        )
    }
}
