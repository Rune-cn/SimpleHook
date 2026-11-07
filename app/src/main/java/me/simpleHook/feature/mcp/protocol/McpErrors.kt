package me.simpleHook.feature.mcp.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Error codes and error payloads defined by MCP revision 2026-07-28.
 *
 * Source: `docs/specification/2026-07-28/basic/index.mdx` section Error Codes and
 * `basic/transports/streamable-http.mdx` section Server Validation.
 *
 * The specification partitions the JSON-RPC reserved range as follows:
 * `-32000…-32019` is legacy (must not be allocated), `-32020…-32099` is reserved for the
 * specification. Legacy codes such as `-32002` (resource not found) must not be emitted.
 */
object McpErrorCodes {
    // JSON-RPC 2.0
    const val PARSE_ERROR: Int = -32700
    const val INVALID_REQUEST: Int = -32600
    const val METHOD_NOT_FOUND: Int = -32601
    const val INVALID_PARAMS: Int = -32602
    const val INTERNAL_ERROR: Int = -32603

    // MCP 2026-07-28
    const val HEADER_MISMATCH: Int = -32020
    const val MISSING_REQUIRED_CLIENT_CAPABILITY: Int = -32021
    const val UNSUPPORTED_PROTOCOL_VERSION: Int = -32022

    /**
     * Application-defined code (outside the reserved range) reported as a tool execution
     * error for invalid arguments that a model can correct; the transport-level answer is
     * still a normal `tools/call` result with `isError: true`.
     */
    const val TOOL_ARGUMENT_ERROR: Int = -32000

    fun describe(code: Int): String = when (code) {
        PARSE_ERROR -> "Parse error"
        INVALID_REQUEST -> "Invalid request"
        METHOD_NOT_FOUND -> "Method not found"
        INVALID_PARAMS -> "Invalid params"
        INTERNAL_ERROR -> "Internal error"
        HEADER_MISMATCH -> "Header mismatch"
        MISSING_REQUIRED_CLIENT_CAPABILITY -> "Missing required client capability"
        UNSUPPORTED_PROTOCOL_VERSION -> "Unsupported protocol version"
        else -> "Error"
    }
}

/** Factory for the structured errors required by the specification. */
object McpErrors {

    /**
     * `-32022`: the request declared a protocol revision this server does not implement.
     * `data.supported` / `data.requested` let a client retry with a mutually supported
     * version (`basic/versioning.mdx`).
     */
    fun unsupportedProtocolVersion(requested: String?): JsonRpcError {
        val supported = JsonArray(McpProtocolVersion.ADVERTISED.map { JsonPrimitive(it) })
        val data = buildJsonObject {
            put("supported", supported)
            if (requested != null) put("requested", JsonPrimitive(requested))
        }
        return JsonRpcError(
            McpErrorCodes.UNSUPPORTED_PROTOCOL_VERSION,
            "Unsupported protocol version",
            data
        )
    }

    /**
     * `-32020`: HTTP headers disagree with the request body, or a required standard header
     * is missing or malformed (`basic/transports/streamable-http.mdx`).
     */
    fun headerMismatch(message: String): JsonRpcError =
        JsonRpcError(McpErrorCodes.HEADER_MISMATCH, message)

    /** `-32021`: the request needs a client capability that was not declared. */
    fun missingClientCapability(vararg capabilities: String): JsonRpcError {
        val data = buildJsonObject {
            put(
                "requiredCapabilities",
                JsonArray(capabilities.map { JsonPrimitive(it) })
            )
        }
        return JsonRpcError(
            McpErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY,
            "Missing required client capability",
            data
        )
    }

    /** `-32602`: a required `_meta` field is missing or malformed. */
    fun malformedMeta(message: String): JsonRpcError =
        JsonRpcError(McpErrorCodes.INVALID_PARAMS, message)

    /** `-32602` for an unknown tool name (`server/tools.mdx` section Error Handling). */
    fun unknownTool(name: String): JsonRpcError =
        JsonRpcError(McpErrorCodes.INVALID_PARAMS, "Unknown tool: $name")

    /** `-32602` for a tool call that is not shaped like a `CallToolRequest`. */
    fun invalidToolCall(message: String, data: JsonElement? = null): JsonRpcError =
        JsonRpcError(McpErrorCodes.INVALID_PARAMS, message, data)

    fun methodNotFound(method: String): JsonRpcError = JsonRpcError.methodNotFound(method)

    fun internal(message: String): JsonRpcError = JsonRpcError.internalError(message)

    fun invalidRequest(message: String): JsonRpcError = JsonRpcError.invalidRequest(message)

    fun invalidParams(message: String): JsonRpcError = JsonRpcError.invalidParams(message)

    fun asObject(error: JsonRpcError): JsonObject = error.toJson()
}
