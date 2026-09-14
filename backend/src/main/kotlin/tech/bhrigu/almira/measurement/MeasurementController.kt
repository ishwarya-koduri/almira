package tech.bhrigu.almira.measurement

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.security.RequestUserContext

data class MeasurementPreference(
    /** True when this person has turned product measurement off. */
    val optedOut: Boolean,
    /** Every event Almira counts, by code. The same list as docs/what-we-measure.md. */
    val events: List<String> = ProductEvent.entries.map { it.code },
)

data class MeasurementPreferenceBody(@field:NotNull val optedOut: Boolean?)

data class AbandonedBody(
    /** Only `capture` today. */
    @field:NotNull @field:Pattern(regexp = "capture") val form: String?,
    /** 1 how to add, 2 pick a type, 3 the form. */
    @field:NotNull @field:Min(1) @field:Max(3) val step: Int?,
)

@Service
class MeasurementPreferences(
    private val jdbc: NamedParameterJdbcTemplate,
    private val userContext: RequestUserContext,
) {
    /** Row-level security limits every statement here to the caller's own row. */
    @Transactional(readOnly = true)
    fun mine(): MeasurementPreference {
        userContext.require()
        val optedOut = jdbc.queryForObject(
            "select exists (select 1 from measurement_opt_outs)", emptyMap<String, Any>(), Boolean::class.java,
        ) == true
        return MeasurementPreference(optedOut)
    }

    @Transactional
    fun set(optedOut: Boolean): MeasurementPreference {
        val userId = userContext.require()
        if (optedOut) {
            jdbc.update(
                "insert into measurement_opt_outs (user_id) values (:u) on conflict (user_id) do nothing",
                mapOf("u" to userId),
            )
        } else {
            jdbc.update("delete from measurement_opt_outs where user_id = :u", mapOf("u" to userId))
        }
        return MeasurementPreference(optedOut)
    }
}

/**
 * The opt-out, and the one event a client reports.
 *
 * Abandonment is the only thing the server cannot see for itself: a form
 * closed without saving sends nothing. The client says which step it was on,
 * and nothing else; the body has no field that could carry what was typed.
 */
// Handler names are the OpenAPI operationIds, and v1 already has `mine` and
// `update`; reusing either would rename a frozen operation (known-issues 16).
@RestController
@RequestMapping("/api/v1")
class MeasurementController(
    private val preferences: MeasurementPreferences,
    private val measurement: ProductMeasurement,
    private val userContext: RequestUserContext,
) {
    @GetMapping("/me/measurement")
    fun measurementPreference(): MeasurementPreference = preferences.mine()

    @PutMapping("/me/measurement")
    fun updateMeasurementPreference(@Valid @RequestBody body: MeasurementPreferenceBody): MeasurementPreference =
        preferences.set(body.optedOut!!)

    @PostMapping("/measurement/abandoned")
    fun reportCaptureAbandoned(@Valid @RequestBody body: AbandonedBody): ResponseEntity<Void> {
        userContext.require()
        measurement.record(ProductEvent.CAPTURE_ABANDONED, step = body.step!!)
        return ResponseEntity.noContent().build()
    }
}
