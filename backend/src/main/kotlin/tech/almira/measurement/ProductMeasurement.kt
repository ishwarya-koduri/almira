package tech.almira.measurement

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tech.almira.security.RequestUserContext
import java.sql.Array as SqlArray
import java.util.UUID

/**
 * The whole list of what Almira measures. docs/what-we-measure.md describes each
 * one in plain words, and V70's check constraint holds the same list; a code
 * here that is not there cannot be stored.
 */
enum class ProductEvent(val code: String) {
    SIGN_IN_COMPLETED("sign_in_completed"),
    HOUSEHOLD_CREATED("household_created"),
    INVITE_SENT("invite_sent"),
    INVITE_ACCEPTED("invite_accepted"),
    /** Counted by the database alongside a household's first HOLDING_ADDED; never recorded directly. */
    FIRST_HOLDING_ADDED("first_holding_added"),
    HOLDING_ADDED("holding_added"),
    LIABILITY_ADDED("liability_added"),
    DOCUMENT_UPLOADED("document_uploaded"),
    ESTATE_CONTACT_ADDED("estate_contact_added"),
    STILL_TRUE_CONFIRMED("still_true_confirmed"),
    HANDBOOK_PRINTED("handbook_printed"),
    CAPTURE_ABANDONED("capture_abandoned"),
}

/**
 * Counts a product event, per day, about nobody.
 *
 * What is passed in — the acting user, the household, member and record ids —
 * is used by `app.count_product_event` to decide WHETHER to count (opted out? a
 * minor involved? the household's first holding?) and is never stored. What is
 * stored is the day, the event, a step for abandonment, and a count. No amounts,
 * names or content are accepted by this API at all, so none can be sent.
 *
 * It never gets in the way of what it measures:
 *
 *  - counted **after the action commits**, in its own transaction, so a
 *    rolled-back save is not counted and a failed count cannot roll a save back;
 *  - any failure is logged by class name at WARN and swallowed;
 *  - `almira.measurement.enabled=false` (ALMIRA_MEASUREMENT_ENABLED) turns every
 *    call into nothing.
 *
 * Server-side only: there is no script, cookie, pixel or third party anywhere in
 * this, and nothing leaves the database.
 */
@Component
class ProductMeasurement(
    private val jdbc: NamedParameterJdbcTemplate,
    transactionManager: PlatformTransactionManager,
    private val userContext: RequestUserContext,
    @Value("\${almira.measurement.enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val ownTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun record(
        event: ProductEvent,
        actor: UUID? = null,
        householdId: UUID? = null,
        memberIds: Collection<UUID?> = emptyList(),
        investmentIds: Collection<UUID> = emptyList(),
        liabilityIds: Collection<UUID> = emptyList(),
        accountIds: Collection<UUID> = emptyList(),
        step: Int = 0,
    ) {
        if (!enabled) return
        require(event != ProductEvent.FIRST_HOLDING_ADDED) { "first_holding_added is derived, not recorded" }
        require(step == 0 || event == ProductEvent.CAPTURE_ABANDONED) { "only capture_abandoned has steps" }

        // Captured now, on the request thread, in case the commit callback runs
        // after the principal has changed.
        val actingUser = actor ?: userContext.currentUserId()
        val write = {
            runCatching {
                ownTransaction.executeWithoutResult {
                    jdbc.jdbcTemplate.execute(COUNT) { statement ->
                        val connection = statement.connection
                        fun uuids(values: Collection<UUID?>): SqlArray =
                            connection.createArrayOf("uuid", values.filterNotNull().distinct().toTypedArray())
                        statement.setString(1, event.code)
                        statement.setShort(2, step.toShort())
                        statement.setObject(3, actingUser)
                        statement.setObject(4, householdId)
                        statement.setArray(5, uuids(memberIds))
                        statement.setArray(6, uuids(investmentIds))
                        statement.setArray(7, uuids(liabilityIds))
                        statement.setArray(8, uuids(accountIds))
                        statement.execute()
                    }
                }
            }.onFailure { log.warn("product event {} not counted: {}", event.code, it.javaClass.simpleName) }
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() { write() }
            })
        } else {
            write()
        }
    }

    private companion object {
        const val COUNT = "select app.count_product_event(?, ?, ?, ?, ?, ?, ?, ?)"
    }
}
