package tech.bhrigu.almira.reports

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
    private val completeness: CompletenessService,
    private val insights: InsightsService,
    private val exports: ExportService,
) {

    @GetMapping("/net-worth-trend")
    fun netWorthTrend(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "12") months: Int,
        @RequestParam(defaultValue = "household") scope: String,
        @RequestParam(required = false) member: UUID?,
    ): NetWorthTrend = trends.netWorthTrend(householdId, months, scope, member)

    /**
     * How usable these records would be to someone who did not create them —
     * with the records to fix, so the client can offer one tap rather than a
     * scolding.
     */
    @GetMapping("/completeness")
    fun completeness(@PathVariable householdId: UUID): Completeness =
        completeness.report(householdId)

    /** Where the money is bunched, and how quickly it could be reached. */
    @GetMapping("/insights")
    fun insights(@PathVariable householdId: UUID): Insights = insights.build(householdId)

    /**
     * Your data, out. Contains exactly what you can see — it is built under your
     * own row-level security, like everything else.
     */
    @GetMapping("/export")
    fun export(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "csv") format: String,
    ): ResponseEntity<ByteArray> {
        val export = exports.holdings(householdId, format)
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + export.fileName + "\"",
            )
            .contentType(MediaType.parseMediaType(export.contentType))
            .body(export.bytes)
    }

    /** Money in and money out, month by month, projected from what's recorded. */
    @GetMapping("/cashflow")
    fun cashflow(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "3") months: Int,
    ): Cashflow = cashflow.calendar(householdId, months)
}
