package me.simpleHook.feature.mcp.selfcheck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.simpleHook.feature.mcp.protocol.JsonRpc
import me.simpleHook.feature.mcp.protocol.McpHttpHeaders
import me.simpleHook.feature.mcp.protocol.McpMetaKeys
import me.simpleHook.feature.mcp.protocol.McpProtocolVersion
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * End-to-end self check of the loopback MCP endpoint.
 *
 * The value of this class is that it fails **in stages**, so a failure message says which
 * layer is broken instead of a generic "cannot connect":
 *
 * 1. [Stage.PORT]   – a TCP connection to host:port; catches "nothing is listening"
 *                     (`ECONNREFUSED`), "wrong address", and a firewall/routing rejection.
 * 2. [Stage.HTTP]   – a `server/discover` POST; catches wrong path, auth rejection, wrong
 *                     protocol version, and a socket that accepts but never answers.
 * 3. [Stage.MCP]    – the JSON-RPC response; catches a server that answers HTTP but rejects
 *                     the MCP handshake.
 *
 * It deliberately uses raw sockets and the same request bytes a real client sends, so it
 * exercises the same code path (transport validation included) rather than calling internal
 * methods that would bypass HTTP.
 *
 * No Android APIs: this runs under plain JVM unit tests with an in-process server.
 */
class McpSelfCheck(
    private val port: Int,
    private val authToken: String?,
    private val host: String = "127.0.0.1",
    private val path: String = "/mcp",
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000
) {
    private val json = Json { ignoreUnknownKeys = true }

    enum class Stage { PORT, HTTP, MCP, DONE }

    data class Result(
        val stage: Stage,
        val ok: Boolean,
        /** Short, user-facing explanation; never contains the token. */
        val message: String,
        val detail: String? = null,
        val httpStatus: Int? = null,
        val protocolVersion: String? = null
    ) {
        val failedAt: Stage? get() = if (ok) null else stage
    }

    /**
     * Runs the check. Blocking; call it off the main thread.
     *
     * [authToken] is sent as the bearer token when non-null; it is never echoed back in
     * [Result.message] or [Result.detail].
     */
    fun run(): Result {
        // Stage 1: can we open a TCP connection at all?
        val socket = try {
            Socket().apply {
                connect(InetSocketAddress(host, port), connectTimeoutMs)
                soTimeout = readTimeoutMs
            }
        } catch (refused: ConnectException) {
            return Result(
                stage = Stage.PORT,
                ok = false,
                message = "端口 $port 没有进程在监听（Connection refused）",
                detail = "${refused.javaClass.simpleName}: ${refused.message}",
            )
        } catch (timeout: SocketTimeoutException) {
            return Result(
                stage = Stage.PORT,
                ok = false,
                message = "连接 $host:$port 超时（${connectTimeoutMs}ms 内无响应）",
                detail = timeout.javaClass.simpleName,
            )
        } catch (unknown: UnknownHostException) {
            return Result(
                stage = Stage.PORT,
                ok = false,
                message = "无法解析地址 $host",
                detail = unknown.message,
            )
        } catch (route: NoRouteToHostException) {
            return Result(
                stage = Stage.PORT,
                ok = false,
                message = "网络不可达：$host",
                detail = route.message,
            )
        } catch (error: Exception) {
            return Result(
                stage = Stage.PORT,
                ok = false,
                message = "无法连接 $host:$port",
                detail = "${error.javaClass.simpleName}: ${error.message}",
            )
        }

        socket.use { open ->
            // Stage 2: does the endpoint answer HTTP at all?
            val request = buildRequest()
            val rawResponse = try {
                val out = open.getOutputStream()
                out.write(request.toByteArray(Charsets.UTF_8))
                out.flush()
                readResponse(BufferedInputStream(open.getInputStream()))
            } catch (timeout: SocketTimeoutException) {
                return Result(
                    stage = Stage.HTTP,
                    ok = false,
                    message = "端口已打开，但 ${readTimeoutMs}ms 内没有 HTTP 响应",
                    detail = "该端口上监听的进程不是 SimpleHook MCP 服务，或被别的程序占用",
                )
            } catch (error: Exception) {
                return Result(
                    stage = Stage.HTTP,
                    ok = false,
                    message = "HTTP 请求失败",
                    detail = "${error.javaClass.simpleName}: ${error.message}",
                )
            }

            val status = rawResponse.status
            val body = rawResponse.body

            // The transport answers 401 before parsing, 404 for a wrong path/unknown method,
            // 400 for header or version problems. Each means a different fix.
            when (status) {
                401 -> return Result(
                    stage = Stage.HTTP,
                    ok = false,
                    message = "服务已响应，但访问令牌不正确（HTTP 401）",
                    detail = "在设置页复制最新令牌后重试",
                    httpStatus = status,
                )

                403 -> return Result(
                    stage = Stage.HTTP,
                    ok = false,
                    message = "服务拒绝了请求来源（HTTP 403 / Origin 校验）",
                    httpStatus = status,
                    detail = body.take(300),
                )

                404 -> return Result(
                    stage = Stage.HTTP,
                    ok = false,
                    message = "路径 $path 不存在（HTTP 404）",
                    detail = body.take(300),
                    httpStatus = status,
                )
            }

            if (status != 200) {
                return Result(
                    stage = Stage.HTTP,
                    ok = false,
                    message = "服务返回了 HTTP $status",
                    detail = body.take(300),
                    httpStatus = status,
                )
            }

            // Stage 3: is the payload a valid MCP result?
            val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
                ?: return Result(
                    stage = Stage.MCP,
                    ok = false,
                    message = "响应不是 JSON（HTTP 200 但内容无法解析）",
                    detail = body.take(300),
                    httpStatus = status,
                )

            val obj = element as? JsonObject
                ?: return Result(
                    stage = Stage.MCP,
                    ok = false,
                    message = "响应不是 JSON 对象",
                    detail = body.take(300),
                    httpStatus = status,
                )

            val error = obj["error"] as? JsonObject
            if (error != null) {
                val code = (error["code"] as? JsonPrimitive)?.content ?: "?"
                val message = (error["message"] as? JsonPrimitive)?.content ?: ""
                return Result(
                    stage = Stage.MCP,
                    ok = false,
                    message = "MCP 返回错误 $code：$message",
                    detail = body.take(300),
                    httpStatus = status,
                )
            }

            val result = obj["result"] as? JsonObject
                ?: return Result(
                    stage = Stage.MCP,
                    ok = false,
                    message = "响应缺少 result 字段",
                    detail = body.take(300),
                    httpStatus = status,
                )

            val versions = (result["supportedVersions"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()

            if (McpProtocolVersion.CURRENT !in versions) {
                return Result(
                    stage = Stage.MCP,
                    ok = false,
                    message = "服务未声明支持协议 ${McpProtocolVersion.CURRENT}",
                    detail = "supportedVersions=$versions",
                    httpStatus = status,
                )
            }

            val capabilities = (result["capabilities"] as? JsonObject)?.keys.orEmpty()

            return Result(
                stage = Stage.DONE,
                ok = true,
                message = "自检通过：端口、HTTP、MCP 握手均正常",
                detail = "supportedVersions=$versions, capabilities=$capabilities",
                httpStatus = status,
                protocolVersion = McpProtocolVersion.CURRENT,
            )
        }
    }

    /**
     * Builds the exact bytes a client sends for `server/discover`, including the headers the
     * transport validates (`MCP-Protocol-Version`, `Mcp-Method`).
     */
    private fun buildRequest(): String {
        val body = buildJsonObject {
            put("jsonrpc", JsonRpc.VERSION)
            put("id", JsonPrimitive(1))
            put("method", JsonPrimitive("server/discover"))
            put(
                "params",
                buildJsonObject {
                    put(
                        "_meta",
                        buildJsonObject {
                            put(McpMetaKeys.PROTOCOL_VERSION, McpProtocolVersion.CURRENT)
                            put(McpMetaKeys.CLIENT_CAPABILITIES, buildJsonObject { })
                            put(
                                McpMetaKeys.CLIENT_INFO,
                                buildJsonObject {
                                    put("name", "SimpleHook-SelfCheck")
                                    put("version", "1")
                                }
                            )
                        }
                    )
                }
            )
        }.toString()

        return buildString {
            append("POST ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append(McpHttpHeaders.CONTENT_TYPE).append(": application/json\r\n")
            append(McpHttpHeaders.ACCEPT).append(": application/json, text/event-stream\r\n")
            append(McpHttpHeaders.PROTOCOL_VERSION).append(": ")
            append(McpProtocolVersion.CURRENT).append("\r\n")
            append(McpHttpHeaders.METHOD).append(": server/discover\r\n")
            if (authToken != null) {
                append(McpHttpHeaders.AUTHORIZATION).append(": Bearer ").append(authToken).append("\r\n")
            }
            append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
    }

    private data class HttpResponse(val status: Int, val body: String)

    /** Minimal HTTP/1.1 response reader: status line, headers, `Content-Length` body. */
    private fun readResponse(input: BufferedInputStream): HttpResponse {
        val statusLine = readLine(input)
            ?: throw IllegalStateException("连接被关闭，未收到任何响应")
        val parts = statusLine.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
            throw IllegalStateException("不是 HTTP 响应：${statusLine.take(80)}")
        }
        val status = parts[1].toIntOrNull()
            ?: throw IllegalStateException("无法解析状态码：$statusLine")

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
        }

        if (contentLength <= 0) return HttpResponse(status, "")

        val buffer = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = input.read(buffer, read, contentLength - read)
            if (count < 0) break
            read += count
        }
        return HttpResponse(status, String(buffer, 0, read, Charsets.UTF_8))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream(96)
        while (true) {
            val c = input.read()
            if (c < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (c == '\n'.code) return out.toString("ISO-8859-1").trimEnd('\r')
            if (out.size() > 8 * 1024) throw IllegalStateException("响应头过长")
            out.write(c)
        }
    }
}
