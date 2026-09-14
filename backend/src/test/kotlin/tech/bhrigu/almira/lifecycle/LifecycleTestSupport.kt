package tech.bhrigu.almira.lifecycle

import tech.bhrigu.almira.support.ApiTestBase

/** Fixtures the lifecycle tests share: a real step-up, and the messages a person was sent. */
abstract class LifecycleTestSupport : ApiTestBase() {

    protected fun stepUp(token: String) {
        val challenge = post("/api/v1/auth/step-up/request", token).json()
        val verified = post(
            "/api/v1/auth/step-up/verify", token,
            mapOf(
                "code" to challenge.path("developmentCode").asText(),
                "requestId" to challenge.path("requestId").asText(),
            ),
        )
        check(verified.statusCode.is2xxSuccessful) { "step-up failed: ${verified.body}" }
    }

    protected fun userId(token: String): String = get("/api/v1/me", token).json().path("id").asText()

    /** Templates of the in-app messages written for a user. Owner connection: a claim about storage. */
    protected fun templatesFor(userId: String): List<String> = db.queryForList(
        "select template from outbound_messages where user_id = ?::uuid and channel = 'in_app' order by created_at",
        String::class.java, userId,
    )

    /**
     * One boolean query as [userId], in its own transaction on the owner
     * connection — for asking a capability function directly what it decides.
     */
    protected fun asUser(userId: String, sql: String): Boolean = db.execute(
        org.springframework.jdbc.core.ConnectionCallback { connection ->
            val auto = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.prepareStatement("select set_config('app.user_id', ?, true)").use {
                    it.setString(1, userId)
                    it.execute()
                }
                connection.createStatement().use { st ->
                    st.executeQuery(sql).use { rs -> rs.next() && rs.getBoolean(1) }
                }
            } finally {
                connection.rollback()
                connection.autoCommit = auto
            }
        },
    )!!

    protected fun household(token: String, householdId: String) =
        get("/api/v1/households/$householdId", token).json()

    protected fun members(token: String, householdId: String) =
        get("/api/v1/households/$householdId/members", token).json()
}
