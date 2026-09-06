package tech.bhrigu.almira.continuity

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.investment.InvestmentService
import java.util.UUID

data class TransmissionStep(val step: String, val detail: String)

/**
 * A document the family will be asked for, and whether this household has
 * already recorded something that answers it.
 *
 * `ready` is deliberately conservative: it is true only where the record itself
 * says so. "We think you have this" is worse than silence when someone is
 * standing at a bank counter.
 */
data class RequiredDocument(val name: String, val ready: Boolean, val note: String?)

data class TransmissionGuide(
    val investmentId: UUID,
    val title: String,
    val typeLabel: String,
    val institutionName: String?,
    val title2: String,
    val summary: String,
    val steps: List<TransmissionStep>,
    val documents: List<RequiredDocument>,
    val contactHint: String,
    val authority: String?,
    val typicalDays: Int?,
    /** People this household has recorded for this holding. */
    val contacts: List<TransmissionContact>,
    val nominees: List<String>,
    val whereItIsKept: String?,
    val disclaimer: String = DISCLAIMER,
)

data class TransmissionContact(val name: String, val kind: String, val phone: String?, val role: String?)

internal const val DISCLAIMER =
    "What a family usually has to do, not legal advice. Forms and thresholds change — " +
        "confirm with the institution before you post anything original."

/**
 * How a family claims each thing (docs/01 §10, docs/10 Phase 3).
 *
 * Knowing a policy exists is only half of it. The other half is knowing that
 * LIC wants Form 3783 and the original policy, that shares move by a
 * transmission request rather than by closing an account, and that a flat still
 * has to be mutated in the municipal records afterwards. All of that is
 * learnable in an afternoon and unbearable to learn in the week after a funeral.
 *
 * The playbooks are reference data. Everything household-specific here — the
 * nominee, the contact, where it is kept — is read under the caller's own
 * row-level security, so a guide can never describe a holding the caller
 * cannot see.
 */
@Service
class TransmissionService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val investments: InvestmentService,
    private val mapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun forInvestment(householdId: UUID, investmentId: UUID): TransmissionGuide {
        households.get(householdId)
        val record = investments.get(householdId, investmentId)

        val playbook = findPlaybook(record.typeCode, record.categoryCode)
            ?: throw ApiException.notFound("We don't have a claim guide for that kind of holding yet.")

        val contacts = jdbc.query(
            """
            select c.name, c.kind, c.phone, l.role
            from contact_links l join contacts c on c.id = l.contact_id
            where l.entity_type = 'investment' and l.entity_id = :id and c.deleted_at is null
            order by lower(c.name)
            """.trimIndent(),
            mapOf("id" to investmentId),
        ) { rs, _ ->
            TransmissionContact(
                rs.getString("name"), rs.getString("kind"), rs.getString("phone"), rs.getString("role"),
            )
        }

        val hasProof = jdbc.queryForObject(
            """
            select exists (select 1 from document_links
                           where entity_type = 'investment' and entity_id = :id)
            """.trimIndent(),
            mapOf("id" to investmentId),
            Boolean::class.javaObjectType,
        ) == true

        return TransmissionGuide(
            investmentId = record.id,
            title = record.title,
            typeLabel = record.typeLabel,
            institutionName = record.institutionName,
            title2 = playbook.title,
            summary = playbook.summary,
            steps = playbook.steps,
            documents = playbook.documents.map { name -> readiness(name, record.nominees.size, hasProof) },
            contactHint = playbook.contactHint ?: "The institution named on the record.",
            authority = playbook.authority,
            typicalDays = playbook.typicalDays,
            contacts = contacts,
            nominees = record.nominees.map { it.name },
            whereItIsKept = record.storageLocation,
        )
    }

    /**
     * Matched most-specific-first: a playbook for `insurance_term` beats the
     * one for the whole insurance category. A household that has customised its
     * taxonomy still lands on its category's guide rather than on nothing.
     */
    private fun findPlaybook(typeCode: String, categoryCode: String): Playbook? = jdbc.query(
        """
        select * from transmission_playbooks
        where type_code = :type or category_code = :category
        order by case when type_code = :type then 0 else 1 end
        limit 1
        """.trimIndent(),
        mapOf("type" to typeCode, "category" to categoryCode),
    ) { rs, _ ->
        Playbook(
            title = rs.getString("title"),
            summary = rs.getString("summary"),
            steps = mapper.readValue(rs.getString("steps")),
            documents = mapper.readValue(rs.getString("documents")),
            contactHint = rs.getString("contact_hint"),
            authority = rs.getString("authority"),
            typicalDays = rs.getInt("typical_days").takeIf { !rs.wasNull() },
        )
    }.firstOrNull()

    /**
     * Only two of these can be answered from the record, and the rest are
     * honestly left open. A checklist that guesses is worse than one that asks.
     */
    private fun readiness(name: String, nomineeCount: Int, hasProof: Boolean): RequiredDocument {
        val lower = name.lowercase()
        return when {
            lower.contains("nominee kyc") || lower.contains("claimant kyc") ->
                RequiredDocument(
                    name,
                    ready = nomineeCount > 0,
                    note = if (nomineeCount > 0) "A nominee is recorded here."
                    else "No nominee recorded — worth registering one with the institution.",
                )

            lower.contains("policy document") || lower.contains("certificate") ||
                lower.contains("passbook") || lower.contains("deed") || lower.contains("receipt") ->
                RequiredDocument(
                    name,
                    ready = hasProof,
                    note = if (hasProof) "A scan is in the vault." else "Nothing attached yet.",
                )

            else -> RequiredDocument(name, ready = false, note = null)
        }
    }

    private data class Playbook(
        val title: String,
        val summary: String,
        val steps: List<TransmissionStep>,
        val documents: List<String>,
        val contactHint: String?,
        val authority: String?,
        val typicalDays: Int?,
    )
}
