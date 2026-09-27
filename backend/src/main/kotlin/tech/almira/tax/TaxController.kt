package tech.almira.tax

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tech.almira.reports.Export
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
class TaxController(
    private val service: TaxService,
    private val gains: CapitalGainsService,
    private val exports: TaxExportService,
) {

    @GetMapping("/pack")
    fun pack(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): TaxPack = service.pack(householdId, member, fy)

    /** The CA-ready PDF: a one-page cover summary, then the working. */
    @GetMapping("/pack/pdf")
    fun packPdf(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): ResponseEntity<ByteArray> = download(exports.pdf(householdId, member, fy))

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

    /** Lot by lot: section, rate, grandfathering and indexation (docs/tax/capital-gains.md). */
    @GetMapping("/capital-gains/schedule")
    fun capitalGainsSchedule(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): CapitalGainsSchedule = gains.schedule(householdId, member, FinancialYear.parse(fy))

    /** Long-term listed-equity gains, in the columns of Schedule 112A, as CSV. */
    @GetMapping("/schedule-112a")
    fun schedule112A(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): ResponseEntity<ByteArray> = download(exports.schedule112A(householdId, member, fy))

    /**
     * The fair market value per unit on 31 January 2018, for a listed share or
     * equity fund bought on or before that day. Entered once per holding.
     */
    @PutMapping("/grandfathering/{investmentId}")
    fun setFmv2018(
        @PathVariable householdId: UUID,
        @PathVariable investmentId: UUID,
        @RequestBody body: SetFmv2018,
    ): GrandfatheringEntry = gains.setFmv2018(householdId, investmentId, body)

    @DeleteMapping("/grandfathering/{investmentId}")
    fun clearFmv2018(
        @PathVariable householdId: UUID,
        @PathVariable investmentId: UUID,
    ): ResponseEntity<Void> {
        gains.clearFmv2018(householdId, investmentId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/interest-income")
    fun interestIncome(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) member: UUID?,
        @RequestParam(required = false) fy: String?,
    ): InterestIncome = service.interestIncome(householdId, member, FinancialYear.parse(fy))

    private fun download(export: Export): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(MediaType.parseMediaType(export.contentType))
        .body(export.bytes)
}
