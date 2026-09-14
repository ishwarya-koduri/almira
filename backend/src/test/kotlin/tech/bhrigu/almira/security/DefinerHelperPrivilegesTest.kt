package tech.bhrigu.almira.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.support.ApiTestBase

/**
 * Definer functions that answer a yes or no about a person or household the
 * caller names read past row-level security, so the runtime role must not be
 * able to call them (V107, R__grants). Asked of the catalogue as the owner:
 * the point is what `almira_app` holds, not what one request happens to do.
 *
 * The behaviour they served is proven where it now lives: consent to messages
 * in DataRightsApiTest, remembrance days in StillTrueSweepTest, memorials in
 * the continuity and lifecycle tests, and measurement in MeasurementApiTest.
 */
@DisplayName("Definer helpers about someone else are not the runtime role's to ask")
class DefinerHelperPrivilegesTest : ApiTestBase() {

    private fun runtimeMayExecute(signature: String): Boolean = db.queryForObject(
        "select has_function_privilege('almira_app', ?, 'execute')", Boolean::class.java, signature,
    )!!

    @Test
    fun `birthdays, memorials and consent choices cannot be asked about by the runtime role`() {
        listOf(
            "app.is_remembrance_day(uuid, date)",
            "app.notifications_stopped(uuid, uuid)",
            "app.messages_consent_withdrawn(uuid)",
            "app.member_present_since(uuid, timestamptz)",
        ).forEach { signature ->
            assertThat(runtimeMayExecute(signature)).describedAs(signature).isFalse()
        }
    }

    @Test
    fun `counting a product event says nothing back about who was involved`() {
        val signature = "app.count_product_event(text, smallint, uuid, uuid, uuid[], uuid[], uuid[], uuid[])"
        assertThat(runtimeMayExecute(signature)).describedAs("it is still the one way in").isTrue()
        val returns = db.queryForObject(
            "select prorettype::regtype::text from pg_proc where oid = ?::regprocedure", String::class.java, signature,
        )
        assertThat(returns).describedAs("a result would say whether a member is a minor or a user opted out")
            .isEqualTo("void")
    }
}
