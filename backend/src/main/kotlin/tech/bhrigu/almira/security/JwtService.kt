package tech.bhrigu.almira.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import org.springframework.stereotype.Service
import tech.bhrigu.almira.config.AlmiraProperties
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class AccessTokenClaims(val userId: UUID, val sessionId: UUID, val expiresAt: Instant)

@Service
class JwtService(props: AlmiraProperties) {

    private val jwt = props.jwt

    init {
        // The same refusal the key-encryption key already makes, for the same
        // reason. A deployment that starts with the development signing secret
        // looks entirely healthy and will mint tokens anybody who has read this
        // repository can forge — which is worse than not starting, because
        // nothing about it looks wrong.
        //
        // "Development" means chosen, not defaulted. Until this was fixed a
        // missing ALMIRA_ENV resolved to development and both checks below were
        // skipped — a real hole, reproduced: a token signed with the published
        // secret for a session the server never issued was accepted as a real
        // user. docs/17 §3.
        val isDevelopment = props.isDevelopment
        val hint = if (props.environment.isBlank()) AlmiraProperties.MISSING_ENVIRONMENT_HINT else ""
        require(isDevelopment || jwt.secret != AlmiraProperties.DEVELOPMENT_JWT_SECRET) {
            "ALMIRA_JWT_SECRET is still the development default. Outside development " +
                "a signing secret must be supplied deliberately — refusing to start " +
                "rather than sign sessions with a public value." + hint
        }
        require(isDevelopment || jwt.secret.length >= 32) {
            "ALMIRA_JWT_SECRET is too short to sign anything with: use at least 32 " +
                "characters (openssl rand -base64 48)." + hint
        }
    }

    private val algorithm: Algorithm = Algorithm.HMAC256(jwt.secret)
    private val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(jwt.issuer).build()
    private val random = SecureRandom()

    val accessTtlSeconds: Long = jwt.accessTtl.seconds
    val refreshTtl = jwt.refreshTtl

    fun issueAccessToken(userId: UUID, sessionId: UUID): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(jwt.issuer)
            .withSubject(userId.toString())
            .withClaim("sid", sessionId.toString())
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(now)
            .withExpiresAt(now.plus(jwt.accessTtl))
            .sign(algorithm)
    }

    fun verifyAccessToken(token: String): AccessTokenClaims? = try {
        val decoded = verifier.verify(token)
        AccessTokenClaims(
            userId = UUID.fromString(decoded.subject),
            sessionId = UUID.fromString(decoded.getClaim("sid").asString()),
            expiresAt = decoded.expiresAtAsInstant,
        )
    } catch (_: JWTVerificationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Refresh tokens are opaque random bytes, not JWTs. A JWT refresh token is
     * self-validating, which means it cannot be revoked before it expires;
     * an opaque token is only as valid as its row in the database, so
     * "revoke this device" takes effect immediately (docs/05 §2).
     */
    fun newRefreshToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Only the hash is stored, so a database dump yields no usable session. */
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
