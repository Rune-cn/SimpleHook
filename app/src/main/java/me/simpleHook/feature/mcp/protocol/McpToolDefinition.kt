package me.simpleHook.feature.mcp.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Declarative behaviour hints returned in a tool's `annotations`
 * (`docs/specification/2026-07-28/server/tools.mdx`, section Tool).
 *
 * Clients must treat annotations as untrusted, so the server never relies on them for
 * access control; they only let a well-behaved client ask the user for confirmation.
 */
data class ToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean = false
) {
    fun toJson(): JsonObject = buildJsonObject {
        if (title != null) put("title", title)
        if (readOnlyHint != null) put("readOnlyHint", readOnlyHint)
        if (destructiveHint != null) put("destructiveHint", destructiveHint)
        if (idempotentHint != null) put("idempotentHint", idempotentHint)
        put("openWorldHint", openWorldHint)
    }
}

/** A tool exposed through `tools/list` and executed through `tools/call`. */
interface McpTool {
    /**
     * Unique tool name. The specification recommends 1-128 characters from
     * `[A-Za-z0-9_.-]`; names are case-sensitive.
     */
    val name: String

    /** Human readable name used for `title`. */
    val title: String

    /** Description shown to the model. Must state side effects explicitly. */
    val description: String

    /** Whether the tool only reads state. Mutating tools are gated by the app setting. */
    val mutating: Boolean get() = false

    val annotations: ToolAnnotations
        get() = ToolAnnotations(
            title = title,
            readOnlyHint = !mutating,
            destructiveHint = mutating,
            idempotentHint = !mutating
        )

    /** JSON Schema object describing `arguments`. */
    fun inputSchema(): JsonObject

    /**
     * Executes the tool. Implementations may throw [ToolArgumentException] for correctable
     * input problems; any other exception is reported as an internal tool error.
     */
    suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult

    /** Serialises the definition exactly as `tools/list` returns it. */
    fun definition(): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("title", JsonPrimitive(title))
        put("description", JsonPrimitive(description))
        put("inputSchema", inputSchema())
        put("annotations", annotations.toJson())
    }
}

/** Ambient information for one `tools/call` invocation. */
data class McpCallContext(
    val meta: McpRequestMeta,
    val requestId: String,
    /** Transport that delivered the request, e.g. `http` or `stdio`. */
    val transport: String
)

/**
 * Result of a tool call.
 *
 * Success results carry `structuredContent` plus the serialised JSON in a text block,
 * which the specification recommends for backwards compatibility
 * (`server/tools.mdx`, section Structured Content).
 */
data class ToolResult(
    val structured: JsonElement,
    val isError: Boolean = false,
    val text: String? = null,
    val extraContent: List<JsonObject> = emptyList()
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("resultType", JsonPrimitive(McpResultType.COMPLETE))
        put("content", McpContent.of(structured, text, extraContent))
        put("structuredContent", structured)
        put("isError", JsonPrimitive(isError))
    }

    companion object {
        fun success(structured: JsonElement, text: String? = null) =
            ToolResult(structured = structured, isError = false, text = text)

        fun failure(message: String): ToolResult = ToolResult(
            structured = buildJsonObject { put("error", JsonPrimitive(message)) },
            isError = true,
            text = message
        )
    }
}

/** `resultType` values defined by MCP revision 2026-07-28. */
object McpResultType {
    const val COMPLETE: String = "complete"
    const val INPUT_REQUIRED: String = "input_required"
}

/** Helpers for the `content` array of a tool result. */
object McpContent {
    fun of(
        structured: JsonElement,
        text: String?,
        extra: List<JsonObject>
    ): kotlinx.serialization.json.JsonArray {
        val items = ArrayList<JsonObject>(extra.size + 1)
        items += textContent(text ?: structured.toString())
        items += extra
        return kotlinx.serialization.json.JsonArray(items)
    }

    fun textContent(text: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(text))
    }
}
