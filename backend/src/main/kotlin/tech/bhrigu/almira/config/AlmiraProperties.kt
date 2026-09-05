package tech.bhrigu.almira.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "almira")
data class AlmiraProperties(
    val db: Db,
    val jwt: Jwt,
    val otp: Otp,
) {
    /**
     * Two sets of credentials against the same database, on purpose.
     *
     * `owner` runs migrations and owns the schema. `app` is what serves
     * requests. PostgreSQL lets a table's owner bypass its own row-level
     * security policies, so serving traffic as the owner would quietly disable
     * every privacy policy in V4. Splitting the roles is what makes RLS real.
     */
    data class Db(
        val url: String,
        val ownerUser: String,
        val ownerPassword: String,
        val appUser: String,
        val appPassword: String,
        val maxPoolSize: Int = 10,
    )

    data class Jwt(
        val secret: String,
        val issuer: String = "almira",
        val accessTtl: Duration = Duration.ofMinutes(15),
        val refreshTtl: Duration = Duration.ofDays(30),
    )

    data class Otp(
        /** `log` prints the code to the application log — local development only. */
        val provider: String = "log",
        val length: Int = 6,
        val ttl: Duration = Duration.ofMinutes(5),
        val maxAttempts: Int = 5,
        val resendCooldown: Duration = Duration.ofSeconds(30),
        val maxPerHour: Int = 5,
        val maxPerIpPerHour: Int = 20,
    )
}
