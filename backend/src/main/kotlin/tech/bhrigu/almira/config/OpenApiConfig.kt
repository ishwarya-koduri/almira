package tech.bhrigu.almira.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The v1 contract.
 *
 * This is the handoff artefact the mobile app is built against, so its stability
 * is a promise rather than an aspiration. The rule for the life of v1 is
 * ADDITIVE ONLY: new endpoints, new optional request fields and new response
 * fields are fine; removing anything, renaming anything, changing a type, or
 * making an existing request field required are not. Those need /api/v2.
 *
 * `OpenApiContractTest` enforces that against the frozen spec in
 * docs/api/openapi-v1.json, so drift fails the build rather than surfacing as a
 * crash on someone's phone weeks later.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun almiraOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Almira API")
                .version(API_VERSION)
                .description(
                    """
                    Every rupee a family has invested or owes, in one private place.

                    **Authentication.** Phone OTP issues a short-lived access token and a
                    rotating refresh token. Send `Authorization: Bearer <accessToken>`.
                    Refresh tokens are single-use: presenting one twice is treated as theft
                    and ends the session.

                    **Privacy.** Every read passes a per-record visibility check enforced by
                    PostgreSQL row-level security. A record you may not see returns 404, not
                    403 — a 403 would confirm it exists. Totals are computed through the same
                    filter, so a private record contributes nothing, not even its amount, to
                    another member's figures.

                    **Money** is decimal, never floating point. Amounts also arrive formatted
                    (`₹1,76,875`) so every surface renders them identically.

                    **Concurrency.** Writes carry the record's `version`; a stale write is
                    rejected with 409 and the current version. Creates accept a client-supplied
                    `id`, so an offline capture keeps its identity and a retry is idempotent.

                    **Stability.** v1 is frozen and additive-only. Breaking changes go to v2.
                    """.trimIndent(),
                )
                .contact(Contact().name("Almira").email("padmini.koduri@bhrigu.tech")),
        )
        .components(
            Components().addSecuritySchemes(
                "bearer",
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Access token from POST /api/v1/auth/otp/verify."),
            ),
        )
        .addSecurityItem(SecurityRequirement().addList("bearer"))

    companion object {
        /**
         * The contract version, not the build version. It changes only when the
         * shape of v1 changes compatibly — never for a breaking change, which
         * would be a new major path.
         */
        const val API_VERSION = "1.0.0"
    }
}
