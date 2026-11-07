package me.simpleHook.feature.mcp.transport

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.simpleHook.feature.mcp.protocol.JsonRpc
import me.simpleHook.feature.mcp.protocol.JsonRpcError
import me.simpleHook.feature.mcp.protocol.McpErrorCodes
import me.simpleHook.feature.mcp.protocol.McpErrors
import me.simpleHook.feature.mcp.protocol.McpHttpHeaders
import me.simpleHook.feature.mcp.protocol.McpProtocolVersion
import me.simpleHook.feature.mcp.protocol.McpServerCore
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Configuration for one run of the HTTP transport. */
data class HttpTransportConfig(
    /** Port to listen on; 0 picks a free port. */
    val port: Int,
    /** Bearer token required on every request; `null` disables authentication (tests only). */
    val authToken: String?,
    /**
     * Allowed `Origin` values. A request without `Origin` is accepted (it is not a browser
     * cross-origin request); any other value is rejected with `403`
     * (`basic/transports/streamable-http.mdx`, section Security & Endpoint).
     */
    val allowedOrigins: Set<String> = DEFAULT_ALLOWED_ORIGINS,
    /** Endpoint path, e.g. `/mcp`. */
    val path: String = "/mcp",
    val maxBodyBytes: Int = HttpCodec.DEFAULT_MAX_BODY_BYTES
) {
    companion object {
        /** Origins used by local MCP tooling that talks to loopback servers. */
        val DEFAULT_ALLOWED_ORIGINS: Set<String> = setOf(
            "http://localhost",
            "http://127.0.0.1",
            "https://localhost",
            "https://127.0.0.1"
        )
    }
}

/**
 * Streamable HTTP transport for MCP revision 2026-07-28.
 *
 * Implements the server side of `basic/transports/streamable-http.mdx`:
 *
 * * one endpoint ([HttpTransportConfig.path]) accepting `POST`; `GET`/`DELETE` answer `405`
 *   because this revision has no standalone SSE stream and no session to terminate;
 * * `Accept` must list both `application/json` and `text/event-stream` (clients negotiate
 *   both even though this server always answers with `application/json`);
 * * `MCP-Protocol-Version`, `Mcp-Method` and (for `tools/call`) `Mcp-Name` are required and
 *   must equal the body values, otherwise `400` + `-32020` `HeaderMismatch`;
 * * unknown methods answer `404` + `-32601`, which also lets a client distinguish this
 *   endpoint from a legacy HTTP+SSE server;
 * * notifications answer `202 Accepted` with no body;
 * * the server binds loopback only and validates `Origin`.
 *
 * The transport is stateless: no session id is issued or expected, which matches the
 * revision's statelessness requirement (`basic/index.mdx`, section Statelessness).
 */
class StreamableHttpTransport(
    private val core: McpServerCore,
    private val config: HttpTransportConfig,
    private val logger: (String) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val running = AtomicBoolean(false)

    private val acceptThread = AtomicReference<Thread?>()

    private val workers = Executors.newFixedThreadPool(MAX_WORKERS) { runnable ->
        Thread(runnable, "mcp-http-worker").apply { isDaemon = true }
    }

    private val activeConnections = AtomicInteger(0)

    /** Port actually bound (useful when [HttpTransportConfig.port] is 0). */
    @Volatile
    var boundPort: Int = -1
        private set

    val endpointPath: String get() = config.path

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(config.port, BACKLOG, InetAddress.getLoopbackAddress())
        socket.soTimeout = ACCEPT_TIMEOUT_MS
        serverSocket = socket
        boundPort = socket.localPort
        val thread = Thread({ acceptLoop(socket) }, "mcp-http-accept").apply { isDaemon = true }
        acceptThread.set(thread)
        thread.start()
        logger("Streamable HTTP listening on 127.0.0.1:$boundPort${config.path}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread.getAndSet(null)?.interrupt()
        workers.shutdownNow()
        logger("Streamable HTTP stopped")
    }

    val isRunning: Boolean get() = running.get()

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break // stop() closed the socket, or the accept timeout elapsed.
            } catch (_: IOException) {
                if (!running.get()) break
                continue
            }
            if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
                activeConnections.decrementAndGet()
                runCatching {
                    HttpCodec.writeResponse(
                        client.getOutputStream(),
                        HttpCodec.Response.empty(503, "Service Unavailable")
                    )
                }
                runCatching { client.close() }
                continue
            }
            runCatching {
                workers.execute {
                    try {
                        handleClient(client)
                    } finally {
                        activeConnections.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            }.onFailure {
                activeConnections.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val response = try {
            val request = HttpCodec.readRequest(socket.getInputStream(), config.maxBodyBytes)
            if (request == null) {
                return
            }
            try {
                process(request)
            } catch (malformed: HttpCodec.MalformedRequest) {
                HttpCodec.Response.json(
                    malformed.status,
                    HttpCodec.reason(malformed.status),
                    jsonRpcError(null, JsonRpcError.invalidRequest(malformed.message ?: "Bad request"))
                )
            }
        } catch (_: SocketException) {
            return
        } catch (_: IOException) {
            return
        } catch (throwable: Throwable) {
            logger("HTTP handler failure: ${throwable.javaClass.simpleName}")
            HttpCodec.Response.json(
                500,
                "Internal Server Error",
                jsonRpcError(null, McpErrors.internal("Internal error"))
            )
        }
        runCatching { HttpCodec.writeResponse(socket.getOutputStream(), response) }
    }

    /**
     * Applies authentication, origin and method checks, then hands a well-formed JSON-RPC
     * request/notification to [McpServerCore].
     */
    internal fun process(request: HttpCodec.Request): HttpCodec.Response {
        // 1. Route: only the MCP endpoint exists.
        if (request.path != config.path) {
            return HttpCodec.Response.json(
                404,
                HttpCodec.reason(404),
                jsonRpcError(null, JsonRpcError.methodNotFound(request.path))
            )
        }

        // 2. Transport methods: GET/DELETE belong to earlier revisions.
        when (request.method) {
            "POST" -> Unit
            "GET", "DELETE" -> return methodNotAllowed()
            else -> return HttpCodec.Response(
                status = 405,
                reason = HttpCodec.reason(405),
                extraHeaders = mapOf("Allow" to "POST")
            )
        }

        // 3. Origin validation (DNS-rebinding protection).
        val origin = request.header(McpHttpHeaders.ORIGIN)
        if (origin != null && !isOriginAllowed(origin)) {
            return HttpCodec.Response.json(
                403,
                HttpCodec.reason(403),
                jsonRpcError(null, JsonRpcError(4030, "Origin not allowed: $origin"))
            )
        }

        // 4. Bearer token.
        if (config.authToken != null && !isAuthorized(request.header(McpHttpHeaders.AUTHORIZATION))) {
            return HttpCodec.Response(
                status = 401,
                reason = HttpCodec.reason(401),
                body = jsonRpcError(null, JsonRpcError(4010, "Invalid or missing bearer token"))
                    .toByteArray(Charsets.UTF_8),
                contentType = "application/json",
                extraHeaders = mapOf(
                    "WWW-Authenticate" to "Bearer realm=\"SimpleHook MCP\""
                )
            )
        }

        // 5. Content negotiation: clients must accept both media types.
        val accept = request.header(McpHttpHeaders.ACCEPT).orEmpty()
        if (!accept.contains("application/json", ignoreCase = true) &&
            !accept.contains("*/*")
        ) {
            return HttpCodec.Response.json(
                400,
                HttpCodec.reason(400),
                jsonRpcError(
                    null,
                    McpErrors.headerMismatch(
                        "Accept header must list application/json and text/event-stream"
                    )
                )
            )
        }
        if (!accept.contains("text/event-stream", ignoreCase = true) &&
            !accept.contains("*/*")
        ) {
            return HttpCodec.Response.json(
                400,
                HttpCodec.reason(400),
                jsonRpcError(
                    null,
                    McpErrors.headerMismatch(
                        "Accept header must list application/json and text/event-stream"
                    )
                )
            )
        }

        val contentType = request.header(McpHttpHeaders.CONTENT_TYPE).orEmpty()
        if (contentType.isNotEmpty() &&
            !contentType.contains("application/json", ignoreCase = true)
        ) {
            return HttpCodec.Response.json(
                415,
                HttpCodec.reason(415),
                jsonRpcError(null, JsonRpcError.invalidRequest("Content-Type must be application/json"))
            )
        }

        // 6. Parse the JSON-RPC message.
        val element: JsonElement = try {
            json.parseToJsonElement(request.bodyText())
        } catch (error: Exception) {
            return HttpCodec.Response.json(
                400,
                HttpCodec.reason(400),
                jsonRpcError(null, JsonRpcError.parseError("Invalid JSON: ${error.message}"))
            )
        }

        val decoded = JsonRpc.decode(element)
        if (decoded is JsonRpc.Decoded.Failure) {
            // A malformed message is a body/request error, not a header problem.
            return HttpCodec.Response.json(
                400,
                HttpCodec.reason(400),
                jsonRpcError(decoded.id, decoded.error)
            )
        }

        val bodyObject = element as JsonObject
        val message = (decoded as JsonRpc.Decoded.Success).message

        // 7. Header/body consistency and protocol version.
        val headerError = validateHeaders(request, bodyObject, message.method)
        if (headerError != null) {
            return HttpCodec.Response.json(
                400,
                HttpCodec.reason(400),
                jsonRpcError(idElementOf(bodyObject), headerError)
            )
        }

        val versionHeader = request.header(McpHttpHeaders.PROTOCOL_VERSION)!!
        if (!McpProtocolVersion.isSupported(versionHeader)) {
            return HttpCodec.Response.json(
                400,
                HttpCodec.reason(400),
                jsonRpcError(
                    idElementOf(bodyObject),
                    McpErrors.unsupportedProtocolVersion(versionHeader)
                )
            )
        }

        // 8. Dispatch.
        return when (message) {
            is JsonRpc.Notification -> {
                core.handleNotification(message)
                HttpCodec.Response.empty(202, HttpCodec.reason(202))
            }

            is JsonRpc.Request -> {
                if (message.method !in McpServerCore.SUPPORTED_METHODS &&
                    message.method != McpServerCore.LEGACY_INITIALIZE
                ) {
                    // Unknown method: 404 lets a client tell this apart from a legacy SSE server.
                    return HttpCodec.Response.json(
                        404,
                        HttpCodec.reason(404),
                        jsonRpcError(
                            message.id.toJson(),
                            JsonRpcError.methodNotFound(message.method)
                        )
                    )
                }
                val response = runBlocking { core.handle(message, "http") }
                HttpCodec.Response.json(200, HttpCodec.reason(200), response.toString())
            }
        }
    }

    /**
     * Validates `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name`
     * (`basic/transports/streamable-http.mdx`, section Server Validation).
     */
    private fun validateHeaders(
        request: HttpCodec.Request,
        body: JsonObject,
        method: String
    ): JsonRpcError? {
        val versionHeader = request.header(McpHttpHeaders.PROTOCOL_VERSION)
            ?: return McpErrors.headerMismatch(
                "Missing required header ${McpHttpHeaders.PROTOCOL_VERSION}"
            )
        val metaVersion = bodyMetaString(body, "io.modelcontextprotocol/protocolVersion")
        if (metaVersion == null) {
            return McpErrors.malformedMeta(
                "Missing required params._meta.io.modelcontextprotocol/protocolVersion"
            )
        }
        if (metaVersion != versionHeader) {
            return McpErrors.headerMismatch(
                "${McpHttpHeaders.PROTOCOL_VERSION} header '$versionHeader' does not match " +
                    "body value '$metaVersion'"
            )
        }

        val methodHeader = request.header(McpHttpHeaders.METHOD)
            ?: return McpErrors.headerMismatch("Missing required header ${McpHttpHeaders.METHOD}")
        if (methodHeader != method) {
            return McpErrors.headerMismatch(
                "${McpHttpHeaders.METHOD} header '$methodHeader' does not match body value '$method'"
            )
        }

        if (method == McpServerCore.METHOD_TOOLS_CALL) {
            val nameHeader = request.header(McpHttpHeaders.NAME)
                ?: return McpErrors.headerMismatch(
                    "Missing required header ${McpHttpHeaders.NAME} for tools/call"
                )
            val bodyName = bodyParamsString(body, "name")
                ?: return McpErrors.invalidToolCall("Missing required string parameter 'name'")
            if (decodeHeaderValue(nameHeader) != bodyName) {
                return McpErrors.headerMismatch(
                    "${McpHttpHeaders.NAME} header value '$nameHeader' does not match " +
                        "body value '$bodyName'"
                )
            }
        }

        return null
    }

    /** Decodes the Base64 sentinel form described in section Value Encoding. */
    private fun decodeHeaderValue(value: String): String {
        if (!value.startsWith(McpHttpHeaders.BASE64_PREFIX) ||
            !value.endsWith(McpHttpHeaders.BASE64_SUFFIX)
        ) {
            return value
        }
        val encoded = value
            .removePrefix(McpHttpHeaders.BASE64_PREFIX)
            .removeSuffix(McpHttpHeaders.BASE64_SUFFIX)
        return runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrDefault(value)
    }

    private fun bodyMetaString(body: JsonObject, key: String): String? {
        val params = body["params"] as? JsonObject ?: return null
        val meta = params["_meta"] as? JsonObject ?: return null
        return (meta[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun bodyParamsString(body: JsonObject, key: String): String? {
        val params = body["params"] as? JsonObject ?: return null
        return (params[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun idElementOf(body: JsonObject): JsonElement? = body["id"]

    /**
     * Accepts an exact match against the configured list, plus any port on a configured
     * loopback origin (`http://127.0.0.1:51234`), because a local client binds an arbitrary
     * port. Anything else — including `null` origins such as `file://` and `chrome-extension://`
     * — is rejected.
     */
    private fun isOriginAllowed(origin: String): Boolean {
        if (origin.isEmpty()) return false
        if (config.allowedOrigins.contains(origin)) return true
        val parsed = parseOrigin(origin) ?: return false
        return config.allowedOrigins.any { allowed ->
            val allowedParsed = parseOrigin(allowed) ?: return@any false
            allowedParsed.first == parsed.first && allowedParsed.second == parsed.second
        }
    }

    /** Splits an origin into (scheme, host) or returns null when it is not a loopback origin. */
    private fun parseOrigin(origin: String): Pair<String, String>? {
        val schemeSeparator = origin.indexOf("://")
        if (schemeSeparator <= 0) return null
        val scheme = origin.substring(0, schemeSeparator).lowercase()
        val hostPort = origin.substring(schemeSeparator + 3)
        if (hostPort.isEmpty() || hostPort.contains('/')) return null
        // Strip an optional ":port"; a bare host has no colon and is used verbatim.
        val host = if (hostPort.contains(':')) hostPort.substringBeforeLast(':') else hostPort
        if (host.isEmpty()) return null
        return scheme to host.lowercase()
    }

    private fun isAuthorized(header: String?): Boolean {
        val expected = config.authToken ?: return true
        if (header == null) return false
        val parts = header.split(' ')
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return false
        // Constant-time comparison to avoid leaking the token through timing.
        return MessageDigest.isEqual(
            parts[1].toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun methodNotAllowed(): HttpCodec.Response = HttpCodec.Response(
        status = 405,
        reason = HttpCodec.reason(405),
        body = jsonRpcError(
            null,
            JsonRpcError.invalidRequest(
                "Only POST is supported on this endpoint in protocol " +
                    McpProtocolVersion.CURRENT
            )
        ).toByteArray(Charsets.UTF_8),
        contentType = "application/json",
        extraHeaders = mapOf("Allow" to "POST")
    )

    private fun jsonRpcError(id: JsonElement?, error: JsonRpcError): String =
        JsonRpc.errorResponse(id, error).toString()

    private companion object {
        const val BACKLOG = 16
        const val MAX_WORKERS = 4
        const val MAX_CONNECTIONS = 16
        const val ACCEPT_TIMEOUT_MS = 500
        const val SOCKET_TIMEOUT_MS = 30_000
    }
}
