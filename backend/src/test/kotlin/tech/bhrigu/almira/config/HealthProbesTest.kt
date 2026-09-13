package tech.bhrigu.almira.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tech.bhrigu.almira.support.ApiTestBase
import tech.bhrigu.almira.support.TestInfra
import java.time.Duration

/**
 * Liveness and readiness, the two probes a deployment actually waits on.
 *
 * The not-ready cases use real unreachable services and a real owner
 * connection rather than mocks: the claim is about what a probe reports when
 * the world is wrong, and a mock would only test what the mock was told.
 */
@DisplayName("Health probes: ready means safe to serve, not merely running")
class HealthProbesTest : ApiTestBase() {

    @Autowired private lateinit var properties: AlmiraProperties
    @Autowired private lateinit var redis: StringRedisTemplate

    @Test
    fun `both probes answer without a session, and a healthy stack is ready`() {
        val live = get("/health/live")
        assertThat(live.status()).isEqualTo(HttpStatus.OK)

        val ready = get("/health/ready")
        assertThat(ready.status()).isEqualTo(HttpStatus.OK)
        assertThat(ready.json()["status"].asText()).isEqualTo("ready")
        assertThat(ready.json()["rlsEnforced"].asBoolean()).isTrue()
        assertThat(ready.json()["redis"].asBoolean()).isTrue()
    }

    @Test
    fun `readiness does not repeat the role name or the environment`() {
        val body = get("/health/ready").json()
        assertThat(body.has("dbRole")).isFalse()
        assertThat(body.has("environment")).isFalse()
    }

    private fun controller(
        jdbc: NamedParameterJdbcTemplate,
        redisTemplate: StringRedisTemplate = redis,
    ) = HealthController(jdbc, jdbc.jdbcTemplate.dataSource!!, properties, redisTemplate)

    private fun appJdbc() = NamedParameterJdbcTemplate(
        DriverManagerDataSource(TestInfra.dbUrl, TestInfra.dbAppUser, TestInfra.dbAppPassword),
    )

    /** The case that matters most: fast, reachable, and bypassing every policy. */
    @Test
    fun `connected as the schema owner is not ready`() {
        val ownerJdbc = NamedParameterJdbcTemplate(
            DriverManagerDataSource(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword),
        )
        val response = controller(ownerJdbc).ready()
        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(response.body!!["database"]).isEqualTo(true)
        assertThat(response.body!!["rlsEnforced"]).isEqualTo(false)
    }

    @Test
    fun `an unreachable database is not ready, and liveness does not care`() {
        val nowhere = NamedParameterJdbcTemplate(
            DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/nothing?connectTimeout=2", "x", "x"),
        )
        val probe = controller(nowhere)
        assertThat(probe.ready().statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(probe.ready().body!!["database"]).isEqualTo(false)
        assertThat(probe.live()["status"]).isEqualTo("alive")
    }

    @Test
    fun `an unreachable Redis is not ready`() {
        val factory = LettuceConnectionFactory(
            RedisStandaloneConfiguration("127.0.0.1", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build(),
        ).apply { afterPropertiesSet(); start() }
        try {
            val response = controller(appJdbc(), StringRedisTemplate(factory)).ready()
            assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(response.body!!["database"]).isEqualTo(true)
            assertThat(response.body!!["redis"]).isEqualTo(false)
        } finally {
            factory.destroy()
        }
    }
}
