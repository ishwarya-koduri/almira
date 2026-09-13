package tech.bhrigu.almira.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tech.bhrigu.almira.auth.AlphaAllowlistAccess

/**
 * Establishes who the caller is, for both Spring Security and the database.
 *
 * The second half is the one that matters: setting [RequestUserContext] is what
 * puts `app.user_id` on every connection this request opens, and therefore what
 * every row-level security policy will evaluate against.
 *
 * The `finally` block is not housekeeping. Request threads are pooled and
 * reused; leaving the identity behind would hand the next request whatever user
 * ran on that thread last.
 */
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val userContext: RequestUserContext,
    private val revocations: SessionRevocationCache,
    private val activity: SessionActivity,
    private val alpha: AlphaAllowlistAccess,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        try {
            request.getHeader("Authorization")
                ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
                ?.substring(BEARER.length)
                ?.trim()
                ?.let(jwtService::verifyAccessToken)
                ?.takeUnless { revocations.isRevoked(it.sessionId) }
                // A tester taken off the email allowlist: their session is
                // revoked here, on the first request that reaches a server
                // with the new list, not when the token expires.
                ?.takeIf { alpha.allowsRequest(it.userId, it.sessionId) }
                ?.let { claims ->
                    // The session id travels with the identity: some decisions
                    // are about this device, not the account (see StepUpService).
                    userContext.set(claims.userId, claims.sessionId)
                    // Throttled, and deliberately here rather than on refresh:
                    // "last seen" has to mean last seen, because the
                    // emergency-access window depends on it.
                    activity.seen(claims.sessionId)
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(
                            claims.userId,
                            null,
                            listOf(SimpleGrantedAuthority("ROLE_USER")),
                        ).apply { details = claims }
                }
            chain.doFilter(request, response)
        } finally {
            userContext.clear()
            SecurityContextHolder.clearContext()
        }
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}
