package me.simpleHook.feature.mcp.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Small builder for JSON Schema 2020-12 objects used as `inputSchema`.
 *
 * The specification requires `inputSchema` to be a valid JSON Schema object and recommends
 * declaring completely empty parameter lists as
 * `{"type":"object","additionalProperties":false}`
 * (`docs/specification/2026-07-28/server/tools.mdx`, section Data Types → Tool).
 */
class JsonSchemaBuilder {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()
    private var additionalProperties: Boolean = false

    fun string(
        name: String,
        description: String,
        required: Boolean = false,
        enum: List<String>? = null,
        default: String? = null,
        minLength: Int? = null
    ) = property(
        name,
        buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
            if (default != null) put("default", JsonPrimitive(default))
            if (minLength != null) put("minLength", JsonPrimitive(minLength))
        },
        required
    )

    fun integer(
        name: String,
        description: String,
        required: Boolean = false,
        minimum: Int? = null,
        maximum: Int? = null,
        enum: List<Int>? = null,
        default: Int? = null
    ) = property(
        name,
        buildJsonObject {
            put("type", "integer")
            put("description", description)
            if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
            if (minimum != null) put("minimum", JsonPrimitive(minimum))
            if (maximum != null) put("maximum", JsonPrimitive(maximum))
            if (default != null) put("default", JsonPrimitive(default))
        },
        required
    )

    fun boolean(
        name: String,
        description: String,
        required: Boolean = false,
        default: Boolean? = null
    ) = property(
        name,
        buildJsonObject {
            put("type", "boolean")
            put("description", description)
            if (default != null) put("default", JsonPrimitive(default))
        },
        required
    )

    fun stringArray(
        name: String,
        description: String,
        required: Boolean = false,
        itemDescription: String? = null
    ) = property(
        name,
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put(
                "items",
                buildJsonObject {
                    put("type", "string")
                    if (itemDescription != null) put("description", itemDescription)
                }
            )
        },
        required
    )

    fun integerArray(
        name: String,
        description: String,
        required: Boolean = false,
        minimum: Int? = null
    ) = property(
        name,
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put(
                "items",
                buildJsonObject {
                    put("type", "integer")
                    if (minimum != null) put("minimum", JsonPrimitive(minimum))
                }
            )
        },
        required
    )

    fun objectArray(
        name: String,
        description: String,
        items: JsonObject,
        required: Boolean = false
    ) = property(
        name,
        buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", items)
        },
        required
    )

    /** Arbitrary raw schema, e.g. a nested object built by [nested]. */
    fun schema(name: String, schema: JsonObject, required: Boolean = false) =
        property(name, schema, required)

    private fun property(name: String, schema: JsonObject, isRequired: Boolean): JsonSchemaBuilder {
        require(name.isNotBlank()) { "property name must not be blank" }
        require(!properties.containsKey(name)) { "duplicate property: $name" }
        properties[name] = schema
        if (isRequired) required += name
        return this
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        if (properties.isNotEmpty()) {
            put("properties", JsonObject(properties))
        }
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
        put("additionalProperties", JsonPrimitive(additionalProperties))
    }

    companion object {
        /** Builds a nested object schema with the same DSL. */
        fun nested(block: JsonSchemaBuilder.() -> Unit): JsonObject =
            JsonSchemaBuilder().apply(block).build()

        /** Schema for a tool that takes no arguments. */
        fun empty(): JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
        }
    }
}

/**
 * Read-only view over `arguments` with strict, typed accessors.
 *
 * Invalid or missing arguments raise [ToolArgumentException], which the server turns into a
 * tool execution error (`isError: true`) so the model can self-correct
 * (`docs/specification/2026-07-28/server/tools.mdx`, section Error Handling).
 */
class ToolArguments(private val raw: JsonObject) {

    fun raw(): JsonObject = raw

    fun has(name: String): Boolean = raw[name] != null && raw[name] !is JsonNull

    fun optionalString(name: String): String? {
        val element = raw[name] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ToolArgumentException("Argument '$name' must be a string")
    }

    fun requireString(name: String): String =
        optionalString(name)?.takeIf { it.isNotEmpty() }
            ?: throw ToolArgumentException("Argument '$name' is required and must be a non-empty string")

    fun optionalInt(name: String): Int? {
        val element = raw[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw ToolArgumentException("Argument '$name' must be an integer")
        if (primitive.isString) {
            return primitive.content.toIntOrNull()
                ?: throw ToolArgumentException("Argument '$name' must be an integer")
        }
        return primitive.intOrNull
            ?: throw ToolArgumentException("Argument '$name' must be an integer")
    }

    fun requireInt(name: String): Int =
        optionalInt(name) ?: throw ToolArgumentException("Argument '$name' is required")

    fun optionalLong(name: String): Long? {
        val element = raw[name] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw ToolArgumentException("Argument '$name' must be an integer")
        if (primitive.isString) {
            return primitive.content.toLongOrNull()
                ?: throw ToolArgumentException("Argument '$name' must be an integer")
        }
        return primitive.longOrNull
            ?: throw ToolArgumentException("Argument '$name' must be an integer")
    }

    fun optionalBoolean(name: String, default: Boolean? = null): Boolean? {
        val element = raw[name] ?: return default
        if (element is JsonNull) return default
        val primitive = element as? JsonPrimitive
            ?: throw ToolArgumentException("Argument '$name' must be a boolean")
        if (primitive.isString) {
            return when (primitive.content.lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw ToolArgumentException("Argument '$name' must be a boolean")
            }
        }
        return primitive.booleanOrNull
            ?: throw ToolArgumentException("Argument '$name' must be a boolean")
    }

    fun requireBoolean(name: String): Boolean =
        optionalBoolean(name) ?: throw ToolArgumentException("Argument '$name' is required")

    fun optionalStringList(name: String): List<String>? {
        val element = raw[name] ?: return null
        if (element is JsonNull) return null
        val array = element as? JsonArray
            ?: throw ToolArgumentException("Argument '$name' must be an array of strings")
        return array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ToolArgumentException("Argument '$name' must be an array of strings")
        }
    }

    fun requireStringList(name: String): List<String> =
        optionalStringList(name) ?: throw ToolArgumentException("Argument '$name' is required")

    fun optionalIntList(name: String): List<Int>? {
        val element = raw[name] ?: return null
        if (element is JsonNull) return null
        val array = element as? JsonArray
            ?: throw ToolArgumentException("Argument '$name' must be an array of integers")
        return array.map { item ->
            val primitive = item as? JsonPrimitive
            if (primitive == null || primitive.isString) {
                throw ToolArgumentException("Argument '$name' must be an array of integers")
            }
            primitive.intOrNull
                ?: throw ToolArgumentException("Argument '$name' must be an array of integers")
        }
    }

    fun optionalObject(name: String): JsonObject? {
        val element = raw[name] ?: return null
        if (element is JsonNull) return null
        return element as? JsonObject
            ?: throw ToolArgumentException("Argument '$name' must be an object")
    }

    fun requireObject(name: String): JsonObject =
        optionalObject(name) ?: throw ToolArgumentException("Argument '$name' is required")

    /**
     * Rejects arguments that the tool does not declare, mirroring
     * `additionalProperties: false` in the published schema.
     */
    fun rejectUnknown(allowed: Set<String>) {
        val unknown = raw.keys - allowed
        if (unknown.isNotEmpty()) {
            throw ToolArgumentException(
                "Unknown argument(s): ${unknown.sorted().joinToString(", ")}. " +
                    "Allowed: ${allowed.sorted().joinToString(", ")}"
            )
        }
    }

    /** Reads an enum-like string argument and validates it case-insensitively. */
    fun requireEnum(name: String, allowed: List<String>): String {
        val value = requireString(name)
        return allowed.firstOrNull { it.equals(value, ignoreCase = true) }
            ?: throw ToolArgumentException(
                "Argument '$name' must be one of: ${allowed.joinToString(", ")}"
            )
    }
}

/** Raised for a correctable argument problem; becomes a tool execution error. */
class ToolArgumentException(message: String) : Exception(message)
