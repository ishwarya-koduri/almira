package tech.bhrigu.almira.tax

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The tax layer. Every response carries its own disclaimer rather than relying
 * on a footer somewhere — these figures will be read out of context, pasted into
 * emails, and forwarded to accountants.
 *
 * `member` scopes to one person, because deductions are personal: 80C belongs to
 * a taxpayer, not to a household. Omitting it gives the household view, which is
 * useful for planning and is nobody's return.
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/tax")
class TaxController(private val service: TaxService) {

    @GetMapping("/pack")
    fun pack(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): TaxPack = service.pack(householdId, member, fy)

    @GetMapping("/deductions")
    fun deductions(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): List<DeductionMeter> =
        service.deductions(householdId, member, FinancialYear.parse(fy))

    @GetMapping("/capital-gains")
    fun capitalGains(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): CapitalGains = service.capitalGains(householdId, member, FinancialYear.parse(fy))

    @GetMapping("/interest-income")
    fun interestIncome(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): InterestIncome = service.interestIncome(householdId, member, FinancialYear.parse(fy))
}
