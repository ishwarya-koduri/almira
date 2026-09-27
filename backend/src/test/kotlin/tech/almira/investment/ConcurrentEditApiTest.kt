package tech.almira.investment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import tech.almira.support.ApiTestBase
import java.math.BigDecimal
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two people editing the same record at the same moment.
 *
 * The loser of that race must be told "someone else changed this" — 409
 * `stale_write` — which is the answer a client can act on: reload, show what
 * changed, offer to reapply. It used to be told 403 "you can read this, but it
 * isn't yours to change", about a record it owns, because the service decided
 * between the two by comparing the version it was sent with a copy of the row
 * read at the top of the same method. Under contention that copy is out of date
 * by construction: the other writer committed in between, so the versions match,
 * and the reader concludes nobody moved anything.
 *
 * Sequentially the same code path answered 409 correctly (scripts/e2e-phase0.sh
 * checks that), which is why the wrong answer only ever appeared under load —
 * where it is least likely to be reproduced and most likely to be shrugged off.
 */
@DisplayName("Two writers, one record")
class ConcurrentEditApiTest : ApiTestBase() {

    private lateinit var ishwarya: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        ishwarya = signIn()
        householdId = createHousehold(ishwarya, "Koduri", "household", "Ishwarya")
            .path("id").asText()
    }

    @Test
    fun `the loser of a concurrent edit is told the record moved, not that it isn't hers`() {
        val id = capture(
            ishwarya, householdId, "universal", "Flat, Kakinada", BigDecimal(4_000_000), "household",
        ).path("id").asText()

        // Both writers read before either writes — which is the whole point:
        // each holds a version that was current when it was read.
        val version = get("/api/v1/households/$householdId/investments/$id", ishwarya)
            .json().path("version").asInt()

        val start = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val responses = try {
            listOf("Flat, renamed by one", "Flat, renamed by two")
                .map { title ->
                    pool.submit<ResponseEntity<String>> {
                        start.await(10, TimeUnit.SECONDS)
                        patch(
                            "/api/v1/households/$householdId/investments/$id", ishwarya,
                            mapOf("version" to version, "title" to title),
                        )
                    }
                }
                .map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val statuses = responses.map { it.status() }
        assertThat(statuses)
            .describedAs("a record its owner may edit is never 403, however the race lands")
            .doesNotContain(HttpStatus.FORBIDDEN)
        assertThat(statuses.count { it == HttpStatus.OK })
            .describedAs("exactly one writer wins")
            .isEqualTo(1)

        val loser = responses.single { it.status() != HttpStatus.OK }
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(loser.errorCode()).isEqualTo("stale_write")
        assertThat(loser.json().path("error").path("details").path("currentVersion").asInt())
            .describedAs("the version to reload at, which is the one the winner wrote")
            .isEqualTo(version + 1)

        val stored = get("/api/v1/households/$householdId/investments/$id", ishwarya).json()
        assertThat(stored.path("version").asInt())
            .describedAs("one write landed, not two")
            .isEqualTo(version + 1)
        assertThat(stored.path("title").asText()).startsWith("Flat, renamed by")
    }

    @Test
    fun `a stale version sent on its own is still a conflict`() {
        val id = capture(
            ishwarya, householdId, "universal", "Gold", BigDecimal(100_000), "household",
        ).path("id").asText()
        val version = get("/api/v1/households/$householdId/investments/$id", ishwarya)
            .json().path("version").asInt()

        patch(
            "/api/v1/households/$householdId/investments/$id", ishwarya,
            mapOf("version" to version, "title" to "Gold, weighed"),
        )
        val late = patch(
            "/api/v1/households/$householdId/investments/$id", ishwarya,
            mapOf("version" to version, "title" to "Gold, weighed again"),
        )

        assertThat(late.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(late.errorCode()).isEqualTo("stale_write")
        assertThat(get("/api/v1/households/$householdId/investments/$id", ishwarya).json().path("title").asText())
            .describedAs("the late write did not land")
            .isEqualTo("Gold, weighed")
    }
}
