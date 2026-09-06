package tech.bhrigu.almira.returns

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class TransactionRow(
    val id: UUID,
    val investmentId: UUID,
    val txnType: String,
    val amount: BigDecimal?,
    val quantity: BigDecimal?,
    val price: BigDecimal?,
    val txnDate: LocalDate,
    val fromAccountId: UUID?,
    val fromAccountLabel: String?,
    val ratio: String?,
    val notes: String?,
    val createdAt: Instant,
    val version: Int,
)

data class LotRow(
    val id: UUID,
    val acquiredOn: LocalDate,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val remainingQty: BigDecimal,
)

data class DisposalRow(
    val id: UUID,
    val investmentId: UUID,
    val quantity: BigDecimal,
    val costBasis: BigDecimal,
    val proceeds: BigDecimal,
    val acquiredOn: LocalDate,
    val disposedOn: LocalDate,
    val holdingDays: Int,
    val gainTerm: String,
    val gain: BigDecimal,
)

@Repository
class TransactionRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(
        id: UUID,
        investmentId: UUID,
        txnType: String,
        amount: BigDecimal?,
        quantity: BigDecimal?,
        price: BigDecimal?,
        txnDate: LocalDate,
        fromAccountId: UUID?,
        ratio: String?,
        notes: String?,
        createdBy: UUID,
    ) = jdbc.update(
        """
        insert into transactions
          (id, investment_id, txn_type, amount, quantity, price, txn_date,
           from_account_id, ratio, notes, created_by)
        values (:id, :investmentId, :type, :amount, :quantity, :price, :date,
                :accountId, :ratio, :notes, :createdBy)
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("id", id).addValue("investmentId", investmentId)
            .addValue("type", txnType).addValue("amount", amount)
            .addValue("quantity", quantity).addValue("price", price)
            .addValue("date", txnDate).addValue("accountId", fromAccountId)
            .addValue("ratio", ratio).addValue("notes", notes)
            .addValue("createdBy", createdBy),
    )

    fun delete(id: UUID): Int =
        jdbc.update("delete from transactions where id = :id", mapOf("id" to id))

    fun list(investmentId: UUID): List<TransactionRow> = jdbc.query(
        """
        select t.*, a.label as account_label
        from transactions t
        left join accounts a on a.id = t.from_account_id
        where t.investment_id = :id
        order by t.txn_date desc, t.created_at desc
        """.trimIndent(),
        mapOf("id" to investmentId), transactionMapper,
    )

    /** Ordered for replay: oldest first, ties broken deterministically. */
    fun forReplay(investmentId: UUID): List<TransactionRow> = jdbc.query(
        """
        select t.*, null as account_label from transactions t
        where t.investment_id = :id
        order by t.txn_date, t.id
        """.trimIndent(),
        mapOf("id" to investmentId), transactionMapper,
    )

    // --- lots and disposals, always rewritten wholesale ----------------------

    fun replaceLots(investmentId: UUID, lots: List<TaxLotEngine.Lot>) {
        jdbc.update("delete from tax_lots where investment_id = :id", mapOf("id" to investmentId))
        lots.forEach { lot ->
            jdbc.update(
                """
                insert into tax_lots
                  (investment_id, acquired_on, quantity, unit_cost, remaining_qty,
                   source_txn_id, sequence)
                values (:id, :acquired, :quantity, :unitCost, :remaining, :source, :sequence)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", investmentId)
                    .addValue("acquired", lot.acquiredOn)
                    .addValue("quantity", lot.quantity)
                    .addValue("unitCost", lot.unitCost)
                    .addValue("remaining", lot.remainingQty)
                    .addValue("source", lot.sourceTxnId)
                    .addValue("sequence", lot.sequence),
            )
        }
    }

    fun replaceDisposals(investmentId: UUID, disposals: List<TaxLotEngine.Disposal>) {
        jdbc.update(
            "delete from tax_lot_disposals where investment_id = :id",
            mapOf("id" to investmentId),
        )
        disposals.forEach { disposal ->
            jdbc.update(
                """
                insert into tax_lot_disposals
                  (investment_id, sell_txn_id, quantity, cost_basis, proceeds,
                   acquired_on, disposed_on, holding_days, gain_term, gain)
                values (:id, :sellTxn, :quantity, :cost, :proceeds,
                        :acquired, :disposed, :days, :term, :gain)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("id", investmentId)
                    .addValue("sellTxn", disposal.sellTxnId)
                    .addValue("quantity", disposal.quantity)
                    .addValue("cost", disposal.costBasis)
                    .addValue("proceeds", disposal.proceeds)
                    .addValue("acquired", disposal.acquiredOn)
                    .addValue("disposed", disposal.disposedOn)
                    .addValue("days", disposal.holdingDays.toInt())
                    .addValue("term", disposal.gainTerm)
                    .addValue("gain", disposal.gain),
            )
        }
    }

    fun lots(investmentId: UUID): List<LotRow> = jdbc.query(
        """
        select id, acquired_on, quantity, unit_cost, remaining_qty from tax_lots
        where investment_id = :id order by acquired_on, sequence
        """.trimIndent(),
        mapOf("id" to investmentId),
    ) { rs, _ ->
        LotRow(
            rs.getObject("id", UUID::class.java),
            rs.getDate("acquired_on").toLocalDate(),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("unit_cost"),
            rs.getBigDecimal("remaining_qty"),
        )
    }

    /** Realized disposals across a household, optionally within a window. */
    fun disposals(householdId: UUID, from: LocalDate?, until: LocalDate?): List<DisposalRow> =
        jdbc.query(
            """
            select d.* from tax_lot_disposals d
            join investments i on i.id = d.investment_id
            where i.household_id = :hid and i.deleted_at is null
              and (cast(:from as date) is null or d.disposed_on >= cast(:from as date))
              and (cast(:until as date) is null or d.disposed_on <= cast(:until as date))
            order by d.disposed_on desc
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("from", from).addValue("until", until),
        ) { rs, _ ->
            DisposalRow(
                rs.getObject("id", UUID::class.java),
                rs.getObject("investment_id", UUID::class.java),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("cost_basis"),
                rs.getBigDecimal("proceeds"),
                rs.getDate("acquired_on").toLocalDate(),
                rs.getDate("disposed_on").toLocalDate(),
                rs.getInt("holding_days"),
                rs.getString("gain_term"),
                rs.getBigDecimal("gain"),
            )
        }

    /** Signed cash flows, from the view that owns the sign convention. */
    fun cashFlows(investmentId: UUID): List<Pair<LocalDate, BigDecimal>> = jdbc.query(
        """
        select flow_date, flow_amount from investment_cash_flows
        where investment_id = :id order by flow_date
        """.trimIndent(),
        mapOf("id" to investmentId),
    ) { rs, _ -> rs.getDate("flow_date").toLocalDate() to rs.getBigDecimal("flow_amount") }

    private val transactionMapper = { rs: ResultSet, _: Int ->
        TransactionRow(
            id = rs.getObject("id", UUID::class.java),
            investmentId = rs.getObject("investment_id", UUID::class.java),
            txnType = rs.getString("txn_type"),
            amount = rs.getBigDecimal("amount"),
            quantity = rs.getBigDecimal("quantity"),
            price = rs.getBigDecimal("price"),
            txnDate = rs.getDate("txn_date").toLocalDate(),
            fromAccountId = rs.getObject("from_account_id", UUID::class.java),
            fromAccountLabel = runCatching { rs.getString("account_label") }.getOrNull(),
            ratio = rs.getString("ratio"),
            notes = rs.getString("notes"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            version = rs.getInt("version"),
        )
    }
}
