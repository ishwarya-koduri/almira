package tech.almira.tax

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.almira.audit.AuditService
import tech.almira.household.HouseholdService
import tech.almira.reports.Export
import tech.almira.security.RequestUserContext
import java.util.UUID

/**
 * The tax pack, out: the CA-ready PDF and the Schedule 112A-shaped CSV.
 *
 * Both are built from the same [TaxPack] the screen shows, under the caller's
 * own row-level security, so a file can never hold more than the person who
 * asked for it could see. Each download is audited — a tax pack is the most
 * forwardable thing the product makes.
 */
@Service
class TaxExportService(
    private val tax: TaxService,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    // Read-write: the audit row is part of the same transaction.
    @Transactional
    fun pdf(householdId: UUID, memberId: UUID?, fyLabel: String?): Export {
        val userId = userContext.require()
        val household = households.get(householdId)
        val pack = tax.pack(householdId, memberId, fyLabel)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "tax.pack.pdf",
            diff = mapOf("fy" to pack.financialYear, "member" to memberId?.toString()),
        )
        return pdfOf(pack, household.name)
    }

    // Read-write: the audit row is part of the same transaction.
    @Transactional
    fun schedule112A(householdId: UUID, memberId: UUID?, fyLabel: String?): Export {
        val userId = userContext.require()
        households.get(householdId)
        val pack = tax.pack(householdId, memberId, fyLabel)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "tax.schedule_112a.csv",
            diff = mapOf("fy" to pack.financialYear, "member" to memberId?.toString()),
        )
        return csvOf(pack)
    }

    companion object {
        fun pdfOf(pack: TaxPack, householdName: String) = Export(
            fileName = "almira-tax-pack-${pack.financialYear}.pdf",
            contentType = "application/pdf",
            bytes = TaxPackPdf.render(pack, householdName),
        )

        fun csvOf(pack: TaxPack) = Export(
            fileName = "almira-schedule-112a-${pack.financialYear}.csv",
            contentType = "text/csv; charset=utf-8",
            bytes = Schedule112ACsv.csv(pack.capitalGainsSchedule.lines),
        )
    }
}
