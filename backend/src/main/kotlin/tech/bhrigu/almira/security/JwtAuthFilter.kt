package tech.bhrigu.almira.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

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
                ?.let { claims ->
                    userContext.set(claims.userId)
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
