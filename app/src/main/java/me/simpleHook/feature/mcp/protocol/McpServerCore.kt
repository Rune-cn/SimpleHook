package me.simpleHook.feature.mcp.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Identity reported in `_meta["io.modelcontextprotocol/serverInfo"]`. */
data class McpServerIdentity(val name: String, val version: String)

/** Immutable server configuration snapshot for one call. */
data class McpServerOptions(
    /** Whether tools with side effects may be executed. */
    val allowMutations: Boolean,
    /** Max tools returned per `tools/list` page. */
    val listPageSize: Int = 50,
    /** Optional guidance returned by `server/discover`. */
    val instructions: String? = null
)

/**
 * Transport-independent MCP server logic.
 *
 * Responsibilities:
 *  * validate per-request `_meta` and negotiate the protocol version (`server/discover`,
 *    `basic/versioning.mdx`);
 *  * answer `tools/list` with pagination (`server/utilities/pagination.mdx`);
 *  * dispatch `tools/call` (`server/tools.mdx`);
 *  * map every failure onto the JSON-RPC errors the specification prescribes.
 *
 * The class has no Android dependency so it is exercised directly by JVM unit tests.
 */
class McpServerCore(
    private val registry: McpToolRegistry,
    private val identity: McpServerIdentity,
    private val options: McpServerOptions,
    private val cacheTtlMs: Long? = null
) {

    /** Server capabilities advertised by `server/discover`. */
    private val capabilities: JsonObject = buildJsonObject {
        put("tools", buildJsonObject { put("listChanged", JsonPrimitive(false)) })
    }

    /**
     * Handles one already-decoded request and returns the JSON-RPC response object.
     *
     * [transport] is reported by `server_info` and used for logging only.
     */
    suspend fun handle(message: JsonRpc.Request, transport: String): JsonObject {
        val meta = when (val parsed = McpRequestMeta.parse(message.params)) {
            is McpMetaResult.Failure ->
                return JsonRpc.errorResponse(message.id, parsed.error)
            is McpMetaResult.Success -> parsed.meta
        }

        if (!McpProtocolVersion.isSupported(meta.protocolVersion)) {
            return JsonRpc.errorResponse(
                message.id,
                McpErrors.unsupportedProtocolVersion(meta.protocolVersion)
            )
        }

        val ctx = McpCallContext(
            meta = meta,
            requestId = message.id.asString(),
            transport = transport
        )

        return try {
            when (message.method) {
                METHOD_DISCOVER -> JsonRpc.resultResponse(message.id, discoverResult())
                METHOD_TOOLS_LIST -> JsonRpc.resultResponse(
                    message.id,
                    listResult(message.params)
                )
                METHOD_TOOLS_CALL -> toolsCall(message, ctx)
                METHOD_PING -> JsonRpc.resultResponse(message.id, resultEnvelope())
                // A legacy client opens with `initialize`; name the supported revisions so the
                // client can report something actionable (basic/versioning.mdx).
                LEGACY_INITIALIZE -> JsonRpc.errorResponse(
                    message.id,
                    legacyHandshakeRejected(meta)
                )
                else -> JsonRpc.errorResponse(message.id, McpErrors.methodNotFound(message.method))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            JsonRpc.errorResponse(
                message.id,
                McpErrors.internal("${throwable.javaClass.simpleName}: ${throwable.message}")
            )
        }
    }

    /**
     * Handles a client notification.
     *
     * Revision 2026-07-28 defines no client-to-server notification for Streamable HTTP:
     * `notifications/cancelled` is stdio-only, and on HTTP a closed response stream is the
     * cancellation signal (`basic/transports/streamable-http.mdx`). Notifications therefore
     * never produce a response; they are accepted and ignored.
     */
    fun handleNotification(@Suppress("UNUSED_PARAMETER") message: JsonRpc.Notification) = Unit

    /** `server/discover` — `server/discover.mdx`. */
    private fun discoverResult(): JsonObject = buildJsonObject {
        put("resultType", McpResultType.COMPLETE)
        put(
            "supportedVersions",
            JsonArray(
                McpProtocolVersion.ADVERTISED.map { JsonPrimitive(it) }
            )
        )
        put("capabilities", capabilities)
        put("_meta", resultMeta())
        if (options.instructions != null) {
            put("instructions", JsonPrimitive(options.instructions))
        }
        cacheHints(cacheTtlMs)
    }

    /**
     * `tools/list` with opaque cursor pagination. The cursor is the name of the last tool
     * of the previous page, which keeps it stable across restarts and reordering-free.
     */
    private fun listResult(params: JsonObject): JsonObject {
        val all = registry.tools(options.allowMutations)
        val pageSize = options.listPageSize.coerceAtLeast(1)

        val cursorName = (params["cursor"] as? JsonPrimitive)?.content
        val startIndex = if (cursorName.isNullOrEmpty()) {
            0
        } else {
            val index = all.indexOfFirst { it.name == cursorName }
            if (index < 0) {
                // Unknown cursor: start over rather than failing, as cursors are opaque.
                0
            } else {
                index + 1
            }
        }

        val page = all.drop(startIndex).take(pageSize)
        val hasMore = startIndex + page.size < all.size

        return buildJsonObject {
            put("resultType", McpResultType.COMPLETE)
            put(
                "tools",
                JsonArray(page.map { it.definition() })
            )
            if (hasMore && page.isNotEmpty()) {
                put("nextCursor", JsonPrimitive(page.last().name))
            }
            put("_meta", resultMeta())
            cacheHints(cacheTtlMs)
        }
    }

    /** `tools/call` — `server/tools.mdx` section Calling Tools. */
    private suspend fun toolsCall(message: JsonRpc.Request, ctx: McpCallContext): JsonObject {
        val params = message.params
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return JsonRpc.errorResponse(
                message.id,
                McpErrors.invalidToolCall("Missing required string parameter 'name'")
            )

        val argumentsElement = params["arguments"]
        val arguments: JsonObject = when (argumentsElement) {
            null -> JsonObject(emptyMap())
            is JsonObject -> argumentsElement
            else -> return JsonRpc.errorResponse(
                message.id,
                McpErrors.invalidToolCall("Parameter 'arguments' must be an object")
            )
        }

        return when (val outcome =
            registry.call(name, arguments, ctx, options.allowMutations)) {
            is McpToolRegistry.Outcome.ProtocolError ->
                JsonRpc.errorResponse(message.id, outcome.error)
            is McpToolRegistry.Outcome.Result ->
                JsonRpc.resultResponse(message.id, withMeta(outcome.result.toJson()))
        }
    }

    private fun withMeta(result: JsonObject): JsonObject = buildJsonObject {
        result.forEach { (key, value) -> put(key, value) }
        put("_meta", resultMeta())
    }

    private fun resultEnvelope(): JsonObject = buildJsonObject {
        put("resultType", McpResultType.COMPLETE)
        put("_meta", resultMeta())
    }

    /** `_meta["io.modelcontextprotocol/serverInfo"]` returned with every result. */
    private fun resultMeta(): JsonObject = buildJsonObject {
        put(
            McpMetaKeys.SERVER_INFO,
            buildJsonObject {
                put("name", JsonPrimitive(identity.name))
                put("version", JsonPrimitive(identity.version))
            }
        )
    }

    private fun JsonObjectBuilder.cacheHints(ttlMs: Long?) {
        if (ttlMs != null) {
            put("ttlMs", JsonPrimitive(ttlMs))
            put("cacheScope", JsonPrimitive("private"))
        }
    }

    /**
     * A server that supports only modern revisions should name the versions it supports in
     * any error returned to an `initialize` request, because legacy clients have no
     * fall-forward mechanism and may surface this message to the user
     * (`basic/versioning.mdx`, section Backward Compatibility).
     */
    private fun legacyHandshakeRejected(meta: McpRequestMeta): JsonRpcError {
        val data = buildJsonObject {
            put(
                "supported",
                JsonArray(
                    McpProtocolVersion.ADVERTISED.map { JsonPrimitive(it) }
                )
            )
            meta.clientInfo?.name?.let { put("client", JsonPrimitive(it)) }
        }
        return JsonRpcError(
            McpErrorCodes.METHOD_NOT_FOUND,
            "This server implements MCP ${McpProtocolVersion.CURRENT}, which has no " +
                "initialize handshake; send requests with _meta." +
                McpMetaKeys.PROTOCOL_VERSION + " = ${McpProtocolVersion.CURRENT}",
            data
        )
    }

    companion object {
        const val METHOD_DISCOVER: String = "server/discover"
        const val METHOD_TOOLS_LIST: String = "tools/list"
        const val METHOD_TOOLS_CALL: String = "tools/call"
        const val METHOD_PING: String = "ping"

        /** Removed in 2026-07-28; recognised only to produce an actionable error. */
        const val LEGACY_INITIALIZE: String = "initialize"

        /** Every request method this server understands, used by transports for validation. */
        val SUPPORTED_METHODS: Set<String> = setOf(
            METHOD_DISCOVER,
            METHOD_TOOLS_LIST,
            METHOD_TOOLS_CALL,
            METHOD_PING
        )
    }
}
