package me.simpleHook.feature.mcp.transport

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.simpleHook.feature.mcp.protocol.JsonRpc
import me.simpleHook.feature.mcp.protocol.JsonRpcError
import me.simpleHook.feature.mcp.protocol.McpErrorCodes
import me.simpleHook.feature.mcp.protocol.McpErrors
import me.simpleHook.feature.mcp.protocol.McpProtocolVersion
import me.simpleHook.feature.mcp.protocol.McpServerCore
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Configuration for the line transport. */
data class LineTransportConfig(
    val port: Int,
    /** Shared secret the bridge must send as its first line; `null` disables the check. */
    val authToken: String?,
    /** Maximum accepted line length; longer lines are rejected without being buffered. */
    val maxLineChars: Int = 8 * 1024 * 1024
)

/**
 * Newline-delimited JSON-RPC transport for hosts that can spawn a process.
 *
 * Android apps cannot write to a host's stdin, so the desktop side runs a tiny bridge
 * (`scripts/simplehook_mcp_stdio.py`) that connects to this loopback socket and relays each
 * stdio line. Framing is one JSON document per line — the same rule as MCP's stdio
 * transport (`basic/transports/stdio.mdx`) — with the single difference that the first line
 * of a connection carries the bearer token instead of an `Authorization` header.
 */
class LineTransport(
    private val core: McpServerCore,
    private val config: LineTransportConfig,
    private val logger: (String) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val running = AtomicBoolean(false)

    private val acceptThread = AtomicReference<Thread?>()

    private val workers = Executors.newFixedThreadPool(MAX_WORKERS) { runnable ->
        Thread(runnable, "mcp-line-worker").apply { isDaemon = true }
    }

    private val connections = AtomicInteger(0)

    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean get() = running.get()

    /**
     * The loopback address to bind, pinned to IPv4 for the same reason as the HTTP transport:
     * `getLoopbackAddress()` may yield `::1`, which a client dialling `127.0.0.1` cannot reach.
     */
    private fun loopbackAddress(): InetAddress =
        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(config.port, BACKLOG, loopbackAddress())
        socket.soTimeout = ACCEPT_TIMEOUT_MS
        serverSocket = socket
        boundPort = socket.localPort
        val thread = Thread({ acceptLoop(socket) }, "mcp-line-accept").apply { isDaemon = true }
        acceptThread.set(thread)
        thread.start()
        logger("stdio bridge listening on 127.0.0.1:$boundPort")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread.getAndSet(null)?.interrupt()
        workers.shutdownNow()
        logger("stdio bridge stopped")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (!running.get()) break
                continue
            }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            runCatching {
                workers.execute {
                    try {
                        serve(client)
                    } finally {
                        connections.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            }.onFailure {
                connections.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = SOCKET_TIMEOUT_MS

        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        try {
            val handshake = readLine(reader) ?: return
            if (config.authToken != null && handshake.trim() != config.authToken) {
                writeMessage(writer, jsonRpcError(null, JsonRpcError(McpErrorCodes.UNAUTHORIZED, "Unauthorized")))
                return
            }

            while (running.get()) {
                val line = readLine(reader) ?: break
                if (line.isBlank()) continue
                val response = handleLine(line) ?: continue
                writeMessage(writer, response)
            }
        } catch (_: SocketException) {
            // Peer went away; nothing to report.
        } catch (_: IOException) {
            // Same as above: connection-level failures end the session quietly.
        } finally {
            runCatching { writer.flush() }
        }
    }

    /**
     * Handles one line. Returns the response JSON to write, or `null` for notifications
     * (which must never be answered).
     */
    internal fun handleLine(line: String): String? {
        val element: JsonElement = try {
            json.parseToJsonElement(line)
        } catch (error: Exception) {
            return jsonRpcError(null, JsonRpcError.parseError("Invalid JSON: ${error.message}"))
        }

        if (element is kotlinx.serialization.json.JsonArray) {
            // Request batching was removed from MCP; answer with a single error.
            return jsonRpcError(
                null,
                JsonRpcError.invalidRequest("Batched requests are not supported")
            )
        }

        val decoded = JsonRpc.decode(element)
        if (decoded is JsonRpc.Decoded.Failure) {
            return jsonRpcError(decoded.id, decoded.error)
        }

        val message = (decoded as JsonRpc.Decoded.Success).message
        val body = element as JsonObject

        // The line transport has no HTTP headers, so the protocol version can only be read
        // from the body; the request is rejected for any other revision.
        val version = bodyMetaString(body, "io.modelcontextprotocol/protocolVersion")
        if (version != null && !McpProtocolVersion.isSupported(version)) {
            return jsonRpcError(
                idElementOf(body),
                McpErrors.unsupportedProtocolVersion(version)
            )
        }

        return when (message) {
            is JsonRpc.Notification -> {
                core.handleNotification(message)
                null
            }

            is JsonRpc.Request -> {
                if (message.method !in McpServerCore.SUPPORTED_METHODS &&
                    message.method != McpServerCore.LEGACY_INITIALIZE
                ) {
                    return jsonRpcError(
                        message.id.toJson(),
                        JsonRpcError.methodNotFound(message.method)
                    )
                }
                val response = runBlocking { core.handle(message, "stdio") }
                response.toString()
            }
        }
    }

    /**
     * Reads one line, refusing lines longer than [LineTransportConfig.maxLineChars].
     *
     * `BufferedReader.readLine()` is not used directly because it buffers an unbounded line
     * in memory before the length can be checked; this version reads in chunks and fails as
     * soon as the limit is exceeded.
     */
    private fun readLine(reader: BufferedReader): String? {
        val builder = StringBuilder(256)
        val chunk = CharArray(4096)
        while (true) {
            var count = 0
            while (count < chunk.size) {
                val c = reader.read()
                if (c < 0) break
                if (c == '\n'.code) {
                    chunk[count++] = '\n'
                    break
                }
                chunk[count++] = c.toChar()
            }
            if (count == 0) return if (builder.isEmpty()) null else builder.toString()
            builder.appendRange(chunk, 0, count)
            if (builder.length > config.maxLineChars) {
                throw IOException("Line exceeds ${config.maxLineChars} characters")
            }
            if (builder.isNotEmpty() && builder[builder.length - 1] == '\n') {
                builder.setLength(builder.length - 1)
                return builder.toString().trimEnd('\r')
            }
        }
    }

    private fun writeMessage(writer: BufferedWriter, message: String) {
        writer.write(message)
        writer.write("\n")
        writer.flush()
    }

    private fun bodyMetaString(body: JsonObject, key: String): String? {
        val params = body["params"] as? JsonObject ?: return null
        val meta = params["_meta"] as? JsonObject ?: return null
        return (meta[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun idElementOf(body: JsonObject): JsonElement? = body["id"]

    private fun jsonRpcError(id: JsonElement?, error: JsonRpcError): String =
        JsonRpc.errorResponse(id, error).toString()

    private companion object {
        const val BACKLOG = 8
        const val MAX_WORKERS = 2
        const val MAX_CONNECTIONS = 4
        const val ACCEPT_TIMEOUT_MS = 500
        const val SOCKET_TIMEOUT_MS = 0 // blocking read; idle clients are allowed
    }
}
