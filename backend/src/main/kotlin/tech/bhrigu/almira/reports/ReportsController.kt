package tech.bhrigu.almira.reports

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/households/{householdId}/reports")
class ReportsController(
    private val trends: TrendService,
    private val cashflow: CashflowService,
) {

    @GetMapping("/net-worth-trend")
    fun netWorthTrend(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "12") months: Int,
        @RequestParam(defaultValue = "household") scope: String,
        @RequestParam(required = false) member: UUID?,
    ): NetWorthTrend = trends.netWorthTrend(householdId, months, scope, member)

    /** Money in and money out, month by month, projected from what's recorded. */
    @GetMapping("/cashflow")
    fun cashflow(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "3") months: Int,
    ): Cashflow = cashflow.calendar(householdId, months)
}
