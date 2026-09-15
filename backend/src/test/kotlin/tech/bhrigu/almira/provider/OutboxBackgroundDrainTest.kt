package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.support.ApiTestBase
import java.time.Duration

/**
 * The background path a real server uses — the wake after commit and the poll —
 * which the rest of the suite switches off (ApiTestBase) so that many cached
 * contexts do not race each other's outbox tests over one database.
 *
 * Its own context, closed after the class, so its worker is alive only while
 * this class runs. Nothing here calls drain().
 */
@DisplayName("Notification outbox, draining on its own")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = ["almira.test.outbox-background=true"])
class OutboxBackgroundDrainTest : ApiTestBase() {

    @Autowired private lateinit var props: AlmiraProperties

    @Test
    fun `a queued notification is sent without anyone asking the worker to drain`() {
        assertThat(props.outbox.background).describedAs("this class must run with the background worker on").isTrue()

        val owner = signIn()
        val householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        val trusted = post(
            "/api/v1/households/$householdId/members", owner,
            mapOf("displayName" to "Meera", "relationship" to "sibling"),
        ).json().path("id").asText().also { joinHousehold(owner, householdId, it, signIn()) }
        val named = post(
            "/api/v1/households/$householdId/emergency/contacts", owner,
            mapOf("trustedMemberId" to trusted, "waitDays" to 14),
        )
        assertThat(named.statusCode.is2xxSuccessful).describedAs(named.body).isTrue()

        fun unsent() = db.queryForObject(
            """
            select count(*) from outbound_messages
            where household_id = ?::uuid and template = 'emergency.named' and channel <> 'in_app' and status = 'queued'
            """.trimIndent(),
            Int::class.java, householdId,
        )!!
        fun queuedOrSent() = db.queryForObject(
            "select count(*) from outbound_messages where household_id = ?::uuid and template = 'emergency.named' and channel <> 'in_app'",
            Int::class.java, householdId,
        )!!

        assertThat(queuedOrSent()).isEqualTo(3)
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (unsent() > 0 && System.nanoTime() < deadline) Thread.sleep(100)
        assertThat(unsent()).describedAs("rows still queued with nobody calling drain()").isZero()
    }
}
