package tech.almira.sharing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.almira.support.ApiTestBase

/**
 * Every scope the code can write is a scope the database accepts.
 *
 * Written because it was not, and the cost was silent. V154 rebuilt
 * `guest_shares_scope_check` from the list in V20 and left out `heir_help`,
 * which V90 had added — so heir mode answered 400 to every attempt to hand a
 * task to a relative, and nothing in the new migration was wrong. It was what
 * it did not say. The same shape as V150 dropping V135's dormancy guard: a
 * thing replaced whole loses every line the new text forgets.
 *
 * A CHECK constraint is exactly that kind of thing, so this holds the database
 * to the code's own constants rather than to a list typed here — a copy would
 * drift the same way the constraint did. It is the same cross-check
 * MessagesConsentTest makes between the essential templates and
 * `app.message_is_essential`.
 */
@DisplayName("The scopes a link can have")
class ShareScopesMatchTheDatabaseTest : ApiTestBase() {

    @Test
    fun `every scope the code writes is one the constraint admits`() {
        val written = ShareService.SCOPES +
            ShareService.HELPER_SCOPE +
            ShareService.EXPORT_SCOPE

        val constraint = db.queryForObject(
            """
            select pg_get_constraintdef(oid) from pg_constraint
            where conname = 'guest_shares_scope_check'
            """.trimIndent(),
            String::class.java,
        )!!

        assertThat(written).describedAs("the scopes the code can insert").isNotEmpty
        written.forEach { scope ->
            assertThat(constraint)
                .describedAs("guest_shares_scope_check must admit '%s', which the code writes", scope)
                .contains("'$scope'")
        }
    }
}
