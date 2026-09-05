package tech.bhrigu.almira.dashboard

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/households/{householdId}")
class DashboardController(private val service: DashboardService) {

    /**
     * The scope switcher from docs/03 §2. Each viewer's totals already come
     * through their own visibility filter, so "Household" means "the household
     * as far as you may see it" — which is the honest number, not a partial one.
     */
    @GetMapping("/dashboard")
    fun dashboard(
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "household") scope: String,
        @RequestParam(required = false) member: UUID?,
    ): Dashboard = service.build(householdId, scope, member)
}
