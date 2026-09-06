package tech.bhrigu.almira.contract

import com.fasterxml.jackson.databind.JsonNode

/**
 * Compares two OpenAPI documents and reports what would break a client built
 * against the older one.
 *
 * The rules follow from who sends what, which is why request and response
 * schemas are judged differently:
 *
 *   RESPONSE schemas — the client READS these.
 *     · removing a property breaks a client that reads it
 *     · un-requiring a property breaks a client that assumed it was always there
 *     · adding a property is fine; nobody reads a field they do not know about
 *
 *   REQUEST schemas — the client SENDS these.
 *     · adding a required property breaks every client that does not send it
 *     · removing a property means data the client still sends is now discarded
 *       silently, which is worse than an error
 *     · un-requiring a property is fine; the client still sends it
 *
 * Both:
 *     · removing a path, an operation or a schema
 *     · changing a property's type or format
 *     · removing an enum value the client may still send or receive
 *     · adding a required query parameter
 */
object OpenApiCompatibility {

    data class Breakage(val where: String, val what: String) {
        override fun toString() = "$where — $what"
    }

    fun check(baseline: JsonNode, current: JsonNode): List<Breakage> {
        val breakages = mutableListOf<Breakage>()
        checkPaths(baseline, current, breakages)
        checkSchemas(baseline, current, breakages)
        return breakages
    }

    // --- paths and operations -------------------------------------------------

    private fun checkPaths(baseline: JsonNode, current: JsonNode, out: MutableList<Breakage>) {
        val basePaths = baseline.path("paths")
        val currentPaths = current.path("paths")

        basePaths.fieldNames().forEach { path ->
            val here = currentPaths.path(path)
            if (here.isMissingNode) {
                out += Breakage(path, "the path is gone")
                return@forEach
            }
            basePaths.path(path).fieldNames().forEach { method ->
                if (method !in HTTP_METHODS) return@forEach
                val operation = here.path(method)
                if (operation.isMissingNode) {
                    out += Breakage("${method.uppercase()} $path", "the operation is gone")
                    return@forEach
                }
                checkParameters(
                    "${method.uppercase()} $path",
                    basePaths.path(path).path(method), operation, out,
                )
            }
        }
    }

    private fun checkParameters(
        where: String,
        baseline: JsonNode,
        current: JsonNode,
        out: MutableList<Breakage>,
    ) {
        val baseParams = baseline.path("parameters").associateBy { it.path("name").asText() }
        val currentParams = current.path("parameters").associateBy { it.path("name").asText() }

        baseParams.forEach { (name, param) ->
            val here = currentParams[name]
            if (here == null) {
                out += Breakage(where, "parameter '$name' is gone")
                return@forEach
            }
            val wasType = param.path("schema").path("type").asText("")
            val nowType = here.path("schema").path("type").asText("")
            if (wasType.isNotEmpty() && wasType != nowType) {
                out += Breakage(where, "parameter '$name' changed type: $wasType -> $nowType")
            }
        }

        // A newly required parameter breaks every existing caller.
        currentParams.forEach { (name, param) ->
            if (param.path("required").asBoolean(false) && name !in baseParams) {
                out += Breakage(where, "new REQUIRED parameter '$name'")
            }
        }
    }

    // --- schemas --------------------------------------------------------------

    private fun checkSchemas(baseline: JsonNode, current: JsonNode, out: MutableList<Breakage>) {
        val baseSchemas = baseline.path("components").path("schemas")
        val currentSchemas = current.path("components").path("schemas")

        val readByClient = schemasReachableFrom(baseline, "responses")
        val sentByClient = schemasReachableFrom(baseline, "requestBody")

        baseSchemas.fieldNames().forEach { name ->
            val was = baseSchemas.path(name)
            val now = currentSchemas.path(name)
            if (now.isMissingNode) {
                out += Breakage(name, "the schema is gone")
                return@forEach
            }
            compareSchema(
                name = name,
                was = was,
                now = now,
                isResponse = name in readByClient,
                isRequest = name in sentByClient,
                out = out,
            )
        }
    }

    private fun compareSchema(
        name: String,
        was: JsonNode,
        now: JsonNode,
        isResponse: Boolean,
        isRequest: Boolean,
        out: MutableList<Breakage>,
    ) {
        val wasProps = was.path("properties")
        val nowProps = now.path("properties")
        val wasRequired = was.path("required").map { it.asText() }.toSet()
        val nowRequired = now.path("required").map { it.asText() }.toSet()

        wasProps.fieldNames().forEach { property ->
            val here = nowProps.path(property)
            if (here.isMissingNode) {
                out += Breakage("$name.$property", "the field is gone")
                return@forEach
            }
            compareTypes(name, property, wasProps.path(property), here, out)
            compareEnums(name, property, wasProps.path(property), here, out)
        }

        if (isResponse) {
            (wasRequired - nowRequired).forEach {
                out += Breakage(
                    "$name.$it",
                    "no longer always present in a response a client assumed it in",
                )
            }
        }
        if (isRequest) {
            (nowRequired - wasRequired).forEach {
                if (wasProps.has(it) || !nowProps.path(it).isMissingNode) {
                    out += Breakage("$name.$it", "newly REQUIRED — existing clients do not send it")
                }
            }
        }
    }

    private fun compareTypes(
        schema: String,
        property: String,
        was: JsonNode,
        now: JsonNode,
        out: MutableList<Breakage>,
    ) {
        val wasType = typeOf(was)
        val nowType = typeOf(now)
        if (wasType != null && nowType != null && wasType != nowType) {
            out += Breakage("$schema.$property", "changed type: $wasType -> $nowType")
        }
        val wasRef = was.path("\$ref").asText("")
        val nowRef = now.path("\$ref").asText("")
        if (wasRef.isNotEmpty() && wasRef != nowRef) {
            out += Breakage("$schema.$property", "now refers to a different schema: $wasRef -> $nowRef")
        }
    }

    private fun compareEnums(
        schema: String,
        property: String,
        was: JsonNode,
        now: JsonNode,
        out: MutableList<Breakage>,
    ) {
        val wasValues = was.path("enum").map { it.asText() }.toSet()
        if (wasValues.isEmpty()) return
        val nowValues = now.path("enum").map { it.asText() }.toSet()
        (wasValues - nowValues).forEach {
            out += Breakage("$schema.$property", "enum value '$it' is gone")
        }
    }

    /**
     * `type` may be a string or, in OpenAPI 3.1, an array like ["string","null"].
     * Nullability is not what this comparison is about, so it is normalised away.
     */
    private fun typeOf(schema: JsonNode): String? {
        val type = schema.path("type")
        return when {
            type.isTextual -> type.asText()
            type.isArray -> type.map { it.asText() }.filter { it != "null" }.sorted()
                .joinToString(",").ifEmpty { null }
            else -> null
        }
    }

    /**
     * Which component schemas a client reads, versus which it sends.
     *
     * Walks every operation's [section] — "responses" or "requestBody" — and
     * follows $ref transitively, so a schema nested three levels inside a
     * response is still known to be something the client reads.
     */
    private fun schemasReachableFrom(document: JsonNode, section: String): Set<String> {
        val schemas = document.path("components").path("schemas")
        val found = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        fun collectRefs(node: JsonNode) {
            when {
                node.isObject -> {
                    node.path("\$ref").asText("").takeIf { it.startsWith(REF_PREFIX) }
                        ?.removePrefix(REF_PREFIX)
                        ?.let { if (found.add(it)) queue += it }
                    node.fields().forEach { collectRefs(it.value) }
                }
                node.isArray -> node.forEach { collectRefs(it) }
            }
        }

        document.path("paths").forEach { path ->
            path.fields().forEach { (method, operation) ->
                if (method in HTTP_METHODS) collectRefs(operation.path(section))
            }
        }
        // Transitive: a response schema's own $refs are read by the client too.
        while (queue.isNotEmpty()) collectRefs(schemas.path(queue.removeFirst()))

        return found
    }

    private const val REF_PREFIX = "#/components/schemas/"
    private val HTTP_METHODS = setOf("get", "put", "post", "delete", "patch", "options", "head")
}
