package tech.bhrigu.almira.returns

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.common.IndianNumbers
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class RecordTransactionBody(
    val txnType: String,
    val amount: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val price: BigDecimal? = null,
    val txnDate: LocalDate,
    val fromAccountId: UUID? = null,
    /** For a split or bonus: "2:1". */
    val ratio: String? = null,
    val notes: String? = null,
)

data class TransactionResponse(
    val id: UUID,
    val txnType: String,
    val amount: BigDecimal?,
    val amountFormatted: String?,
    val quantity: BigDecimal?,
    val price: BigDecimal?,
    val txnDate: LocalDate,
    val fromAccountId: UUID?,
    val fromAccountLabel: String?,
    val ratio: String?,
    val notes: String?,
)

data class TaxLotResponse(
    val id: UUID,
    val acquiredOn: LocalDate,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val remainingQty: BigDecimal,
    /** True once every unit in the lot has been sold. */
    val exhausted: Boolean,
)

@RestController
@RequestMapping("/api/v1/households/{householdId}")
class ReturnsController(private val service: ReturnsService) {

    @PostMapping("/investments/{investmentId}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @PathVariable householdId: UUID,
        @PathVariable investmentId: UUID,
        @RequestBody @Valid body: RecordTransactionBody,
    ): List<TransactionResponse> = service.record(
        householdId, investmentId, body.txnType, body.amount, body.quantity,
        body.price, body.txnDate, body.fromAccountId, body.ratio, body.notes,
    ).map { it.toResponse() }

    @GetMapping("/investments/{investmentId}/transactions")
    fun transactions(
        @PathVariable householdId: UUID,
        @PathVariable investmentId: UUID,
    ): List<TransactionResponse> =
        service.transactions(householdId, investmentId).map { it.toResponse() }

    @DeleteMapping("/investments/{investmentId}/transactions/{transactionId}")
    fun remove(
        @PathVariable householdId: UUID,
        @PathVariable investmentId: UUID,
        @PathVariable transactionId: UUID,
    ): List<TransactionResponse> =
        service.remove(householdId, investmentId, transactionId).map { it.toResponse() }

    /** Which units are still held, when they were bought, and at what cost. */
    @GetMapping("/investments/{investmentId}/tax-lots")
    fun lots(
        @PathVariable householdId: UUID,
        @PathVariable investmentId: UUID,
    ): List<TaxLotResponse> = service.lots(householdId, investmentId).map {
        TaxLotResponse(
            it.id, it.acquiredOn, it.quantity, it.unitCost, it.remainingQty,
            exhausted = it.remainingQty.signum() == 0,
        )
    }

    @GetMapping("/investments/{investmentId}/returns")
    fun forInvestment(
        @PathVariable householdId: UUID,
        @PathVariable investmentId: UUID,
    ): Performance = service.forInvestment(householdId, investmentId)

    /**
     * Performance across the household. `groupBy` is total, category, member or
     * investment — the same primitives every way, so a category total and the
     * sum of its holdings cannot disagree.
     */
    @GetMapping("/returns")
    fun portfolio(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "total") groupBy: String,
    ): List<Performance> = service.portfolio(householdId, groupBy)

    private fun TransactionRow.toResponse() = TransactionResponse(
        id = id, txnType = txnType, amount = amount,
        amountFormatted = amount?.let(IndianNumbers::rupees),
        quantity = quantity, price = price, txnDate = txnDate,
        fromAccountId = fromAccountId, fromAccountLabel = fromAccountLabel,
        ratio = ratio, notes = notes,
    )
}
