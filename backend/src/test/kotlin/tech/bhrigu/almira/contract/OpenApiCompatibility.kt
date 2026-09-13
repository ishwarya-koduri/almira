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
 *     · changing an operation's operationId — "generate a typed client from
 *       it" makes that id a method name, so a rename is a compile error in
 *       every client regenerated against the new file. springdoc derives it
 *       from the Kotlin function name and suffixes collisions (`snooze_1`), so
 *       a new endpoint whose handler shares a name can rename an old one.
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

    /**
     * The other direction: what the live API serves that the frozen file does
     * not describe. None of it breaks a v1 client — [check] is right to let it
     * through — but a client built from the JSON alone cannot know it exists,
     * and a later change to it would be judged against nothing. That is how the
     * file drifted by eleven paths before (known issue #16).
     *
     * Reported: paths, operations, their parameters and response status codes,
     * schemas, schema properties, enum values, and security schemes. Prose
     * (summaries, descriptions) and the generated `servers` URL are not.
     */
    fun undeclared(frozen: JsonNode, live: JsonNode): List<Breakage> {
        val out = mutableListOf<Breakage>()

        val frozenPaths = frozen.path("paths")
        live.path("paths").fields().forEach { (path, item) ->
            val known = frozenPaths.path(path)
            if (known.isMissingNode) {
                out += Breakage(path, "the path is served but not in the frozen contract")
                return@forEach
            }
            item.fields().forEach { (method, operation) ->
                if (method !in HTTP_METHODS) return@forEach
                val where = "${method.uppercase()} $path"
                val knownOp = known.path(method)
                if (knownOp.isMissingNode) {
                    out += Breakage(where, "the operation is served but not in the frozen contract")
                    return@forEach
                }
                val knownParams = knownOp.path("parameters").map { it.path("name").asText() }.toSet()
                operation.path("parameters").forEach {
                    val name = it.path("name").asText()
                    if (name !in knownParams) out += Breakage(where, "parameter '$name' is not in the frozen contract")
                }
                val knownCodes = knownOp.path("responses").fieldNames().asSequence().toSet()
                operation.path("responses").fieldNames().forEach {
                    if (it !in knownCodes) out += Breakage(where, "response $it is not in the frozen contract")
                }
            }
        }

        val frozenSchemas = frozen.path("components").path("schemas")
        live.path("components").path("schemas").fields().forEach { (name, schema) ->
            val known = frozenSchemas.path(name)
            if (known.isMissingNode) {
                out += Breakage(name, "the schema is served but not in the frozen contract")
                return@forEach
            }
            schema.path("properties").fields().forEach { (property, definition) ->
                val knownProperty = known.path("properties").path(property)
                if (knownProperty.isMissingNode) {
                    out += Breakage("$name.$property", "the field is served but not in the frozen contract")
                    return@forEach
                }
                // An enum sits on the property, or on its items for a list.
                listOf(definition to knownProperty, definition.path("items") to knownProperty.path("items"))
                    .forEach { (served, declared) ->
                        val knownValues = declared.path("enum").map { it.asText() }.toSet()
                        served.path("enum").map { it.asText() }.forEach {
                            if (it !in knownValues) {
                                out += Breakage("$name.$property", "enum value '$it' is not in the frozen contract")
                            }
                        }
                    }
            }
        }

        val frozenSecurity = frozen.path("components").path("securitySchemes")
        live.path("components").path("securitySchemes").fieldNames().forEach {
            if (frozenSecurity.path(it).isMissingNode) {
                out += Breakage(it, "the security scheme is served but not in the frozen contract")
            }
        }

        return out
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
                val wasId = basePaths.path(path).path(method).path("operationId").asText("")
                val nowId = operation.path("operationId").asText("")
                if (wasId.isNotEmpty() && wasId != nowId) {
                    out += Breakage(
                        "${method.uppercase()} $path",
                        "operationId changed: $wasId -> $nowId (a generated client's method name)",
                    )
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
