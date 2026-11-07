package me.simpleHook.feature.mcp.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 message model used by MCP.
 *
 * Specification: `docs/specification/2026-07-28/basic/index.mdx` sections Messages,
 * Result Responses, Error Responses and Error Codes.
 *
 * Messages are parsed from raw text into [IncomingMessage] so that the transport stays
 * unaware of protocol semantics, and every class here is free of Android dependencies.
 */
object JsonRpc {
    const val VERSION: String = "2.0"

    /** Request id: a string or an integer, never `null` (unlike base JSON-RPC). */
    sealed interface Id {
        fun toJson(): JsonPrimitive
        fun asString(): String = when (this) {
            is StringId -> value
            is NumberId -> value
        }

        data class StringId(val value: String) : Id {
            override fun toJson(): JsonPrimitive = JsonPrimitive(value)
        }

        data class NumberId(val value: Long) : Id {
            override fun toJson(): JsonPrimitive = JsonPrimitive(value)
        }
    }

    /** A request or a notification decoded from the wire. */
    sealed interface IncomingMessage {
        val method: String
        val params: JsonObject
    }

    data class Request(override val method: String, val id: Id, override val params: JsonObject) :
        IncomingMessage

    data class Notification(override val method: String, override val params: JsonObject) :
        IncomingMessage

    /** Outcome of decoding one message: valid message or a protocol error. */
    sealed interface Decoded {
        data class Success(val message: IncomingMessage) : Decoded
        data class Failure(val error: JsonRpcError, val id: JsonElement?) : Decoded
    }

    /** Parses a single JSON-RPC message. Never throws. */
    fun decode(raw: JsonElement): Decoded {
        val obj = raw as? JsonObject
            ?: return Decoded.Failure(JsonRpcError.parseError("Message must be a JSON object"), null)

        val version = (obj["jsonrpc"] as? JsonPrimitive)?.contentOrNullSafe()
        if (version != VERSION) {
            return Decoded.Failure(
                JsonRpcError.invalidRequest("Unsupported jsonrpc version: ${version ?: "missing"}"),
                obj["id"]
            )
        }

        val method = (obj["method"] as? JsonPrimitive)?.contentOrNullSafe()
        if (method.isNullOrEmpty()) {
            return Decoded.Failure(JsonRpcError.invalidRequest("Missing method"), obj["id"])
        }

        val paramsElement = obj["params"]
        val params = when (paramsElement) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> paramsElement
            else -> return Decoded.Failure(
                JsonRpcError.invalidParams("params must be an object"),
                obj["id"]
            )
        }

        val idElement = obj["id"]
        if (idElement == null || idElement is JsonNull) {
            // Notifications MUST NOT carry an id; an explicit null id is treated the same way.
            return Decoded.Success(Notification(method, params))
        }

        val id = parseId(idElement)
            ?: return Decoded.Failure(JsonRpcError.invalidRequest("Invalid request id"), idElement)

        return Decoded.Success(Request(method, id, params))
    }

    fun parseId(element: JsonElement): Id? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        if (primitive.isString) return Id.StringId(primitive.content)
        val number = primitive.content.toLongOrNull() ?: return null
        return Id.NumberId(number)
    }

    /** Builds a `{"jsonrpc":"2.0","id":…,"result":…}` envelope. */
    fun resultResponse(id: Id, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id.toJson())
        put("result", result)
    }

    /** Builds a `{"jsonrpc":"2.0","id":…,"error":…}` envelope. */
    fun errorResponse(id: Id?, error: JsonRpcError): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        if (id != null) {
            put("id", id.toJson())
        } else {
            put("id", JsonNull)
        }
        put("error", error.toJson())
    }

    fun errorResponse(id: JsonElement?, error: JsonRpcError): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id ?: JsonNull)
        put("error", error.toJson())
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content
}

/** A JSON-RPC error object (`code`, `message`, optional `data`). */
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", code)
        put("message", message)
        if (data != null) put("data", data)
    }

    companion object {
        fun parseError(message: String) = JsonRpcError(McpErrorCodes.PARSE_ERROR, message)

        fun invalidRequest(message: String) =
            JsonRpcError(McpErrorCodes.INVALID_REQUEST, message)

        fun invalidParams(message: String, data: JsonElement? = null) =
            JsonRpcError(McpErrorCodes.INVALID_PARAMS, message, data)

        fun methodNotFound(method: String) =
            JsonRpcError(McpErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")

        fun internalError(message: String) =
            JsonRpcError(McpErrorCodes.INTERNAL_ERROR, message)
    }
}
