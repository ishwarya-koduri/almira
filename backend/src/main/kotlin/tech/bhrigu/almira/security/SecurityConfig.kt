package tech.bhrigu.almira.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tech.bhrigu.almira.common.ApiErrorBody
import tech.bhrigu.almira.common.ApiErrorEnvelope

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val mapper: ObjectMapper,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        // No cookies, no server-side session: the API is stateless and the app
        // is a native client, so CSRF has no attack surface here.
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .headers { headers ->
            headers.httpStrictTransportSecurity {
                it.includeSubDomains(true).maxAgeInSeconds(63_072_000)
            }
            headers.frameOptions { it.deny() }
            headers.contentTypeOptions { }
        }
        .authorizeHttpRequests { auth ->
            auth.requestMatchers(
                "/api/auth/otp/**", "/api/auth/refresh",
                "/actuator/health", "/health",
                "/docs/**", "/swagger-ui/**", "/v3/api-docs/**",
            ).permitAll()
            // Redeeming a document ticket is unauthenticated ON PURPOSE: the
            // ticket itself is the authority, minted only after a visibility
            // check and a step-up, valid for two minutes and single-use. That
            // is what lets an <img> or <iframe> load a document directly.
            auth.requestMatchers("/api/documents/download").permitAll()
            // The web client is static and holds no secrets — everything it
            // shows is fetched from /api with a bearer token, which is where
            // the real gate is.
            auth.requestMatchers(
                "/", "/index.html", "/app/**", "/assets/**",
                "/favicon.ico", "/manifest.webmanifest",
            ).permitAll()
            auth.anyRequest().authenticated()
        }
        .exceptionHandling { handling ->
            // A JSON envelope, matching every other error the API returns, so a
            // client never has to parse an HTML error page.
            handling.authenticationEntryPoint { _, response, _ ->
                write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Please sign in to continue.")
            }
            handling.accessDeniedHandler { _, response, _ ->
                write(response, HttpStatus.FORBIDDEN, "forbidden", "You don't have access to do that.")
            }
        }
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        .build()

    private fun write(
        response: jakarta.servlet.http.HttpServletResponse,
        status: HttpStatus,
        code: String,
        message: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        mapper.writeValue(
            response.outputStream,
            ApiErrorEnvelope(ApiErrorBody(code, message)),
        )
    }
}
