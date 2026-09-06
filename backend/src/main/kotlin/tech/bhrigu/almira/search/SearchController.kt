package tech.bhrigu.almira.search

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/households/{householdId}")
class SearchController(private val service: SearchService) {

    @GetMapping("/search")
    fun search(
        @PathVariable householdId: UUID,
        @RequestParam q: String,
        @RequestParam(defaultValue = "8") limit: Int,
    ): SearchResults = service.search(householdId, q, limit)
}
