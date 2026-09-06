package tech.bhrigu.almira.money

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/households/{householdId}/rates")
class CurrencyController(private val service: CurrencyService) {

    /** Every rate that would be used, with its date and where it came from. */
    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "INR") quote: String,
    ): List<RateQuote> = service.rates(householdId, quote)

    /** A household's own rate, which beats anything that shipped with the app. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: RecordRate,
    ): RateQuote = service.record(householdId, body)

    @GetMapping("/convert")
    fun convert(
        @PathVariable householdId: UUID,
        @RequestParam amount: BigDecimal,
        @RequestParam from: String,
        @RequestParam(defaultValue = "INR") to: String,
        @RequestParam(required = false) on: LocalDate?,
    ): Converted = service.convert(amount, from, to, householdId, on ?: LocalDate.now())
}
