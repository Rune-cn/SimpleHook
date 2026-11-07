package me.simpleHook.feature.mcp.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pagination arguments shared by the list tools. */
object PaginationArgs {
    const val OFFSET = "offset"
    const val LIMIT = "limit"

    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    fun register(builder: me.simpleHook.feature.mcp.protocol.JsonSchemaBuilder) {
        builder.integer(
            OFFSET,
            "Number of items to skip (0-based). Use with a previous page's nextOffset.",
            minimum = 0,
            default = 0
        )
        builder.integer(
            LIMIT,
            "Maximum number of items to return ($MAX_LIMIT at most).",
            minimum = 1,
            maximum = MAX_LIMIT,
            default = DEFAULT_LIMIT
        )
    }

    data class Values(val offset: Int, val limit: Int)

    fun read(args: me.simpleHook.feature.mcp.protocol.ToolArguments): Values {
        val offset = (args.optionalInt(OFFSET) ?: 0).coerceAtLeast(0)
        val requested = args.optionalInt(LIMIT) ?: DEFAULT_LIMIT
        if (requested < 1) {
            throw me.simpleHook.feature.mcp.protocol.ToolArgumentException("$LIMIT must be >= 1")
        }
        return Values(offset, requested.coerceAtMost(MAX_LIMIT))
    }
}

/** Small helpers shared by the tool implementations. */
internal object ToolJson {

    fun array(items: Iterable<JsonElement>): JsonArray = JsonArray(items.toList())

    fun string(value: String?): JsonElement =
        if (value == null) kotlinx.serialization.json.JsonNull else JsonPrimitive(value)

    fun number(value: Number): JsonElement = JsonPrimitive(value)

    fun bool(value: Boolean): JsonElement = JsonPrimitive(value)

    fun page(
        itemsKey: String,
        items: Iterable<JsonElement>,
        offset: Int,
        limit: Int,
        total: Int
    ) = buildJsonObject {
        put(itemsKey, array(items))
        put("offset", JsonPrimitive(offset))
        put("limit", JsonPrimitive(limit))
        put("total", JsonPrimitive(total))
        put("returned", JsonPrimitive(items.count()))
        val nextOffset = offset + items.count()
        put("hasMore", JsonPrimitive(nextOffset < total))
        if (nextOffset < total) put("nextOffset", JsonPrimitive(nextOffset))
    }

    fun jsonArray(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    /**
     * Renders structured content as the text block that accompanies it. The specification
     * recommends returning the serialised JSON in a text content block for older clients
     * (`server/tools.mdx`, section Structured Content).
     */
    fun text(element: JsonElement): String = element.toString()
}
