package tech.bhrigu.almira.sharing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.springframework.test.annotation.DirtiesContext
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A single-view link is served once, however two opens race.
 *
 * The race is made certain: the lookup of the link waits at a barrier for a
 * second one, so both opens have read the link — view count 0 — and passed the
 * limit check on that copy before either counts its view. What stops the second
 * one must therefore be a check made as the view is counted.
 */
// A context of its own (a spy or a replaced bean), closed after this class so
// its connection pools do not stay open beside every cached context.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("A guest link's view limit holds under concurrency")
class GuestShareViewLimitApiTest : ApiTestBase() {

    @MockitoSpyBean(name = "jdbc")
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Test
    fun `two opens racing for the last view - exactly one is served and counted`() {
        val owner = signIn()
        val householdId = createHousehold(owner, "Koduri", "household", "Ishwarya").path("id").asText()
        capture(owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household")
        val created = post(
            "/api/v1/households/$householdId/shares", owner,
            mapOf("label" to "Once", "scope" to "handbook", "maxViews" to 1),
        ).json()
        val token = created.path("url").asText().substringAfterLast('/')
        val shareId = created.path("id").asText()

        val barrier = CyclicBarrier(2)
        Mockito.doAnswer { invocation ->
            runCatching { barrier.await(5, TimeUnit.SECONDS) }
            invocation.callRealMethod()
        }.`when`(jdbc).query(
            ArgumentMatchers.contains("resolve_guest_share"),
            ArgumentMatchers.anyMap<String, Any>(),
            ArgumentMatchers.any(RowMapper::class.java),
        )

        val pool = Executors.newFixedThreadPool(2)
        val answers = try {
            List(2) { pool.submit<HttpStatus> { HttpStatus.valueOf(get("/api/v1/share/$token").statusCode.value()) } }
                .map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            Mockito.reset(jdbc)
        }

        assertThat(answers).describedAs("answers to two concurrent opens of a single-view link")
            .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.NOT_FOUND)
        assertThat(
            db.queryForObject("select view_count from guest_shares where id = ?::uuid", Int::class.java, shareId),
        ).isEqualTo(1)
        assertThat(
            db.queryForObject("select count(*) from guest_share_views where share_id = ?::uuid", Int::class.java, shareId),
        ).isEqualTo(1)
    }
}
