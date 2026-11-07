package me.simpleHook.feature.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.simpleHook.feature.mcp.protocol.McpErrorCodes
import me.simpleHook.feature.mcp.protocol.McpProtocolVersion
import me.simpleHook.feature.mcp.protocol.McpServerCore
import me.simpleHook.feature.mcp.protocol.McpServerIdentity
import me.simpleHook.feature.mcp.protocol.McpServerOptions
import me.simpleHook.feature.mcp.protocol.McpToolRegistry
import me.simpleHook.feature.mcp.transport.HttpTransportConfig
import me.simpleHook.feature.mcp.transport.LineTransport
import me.simpleHook.feature.mcp.transport.LineTransportConfig
import me.simpleHook.feature.mcp.transport.StreamableHttpTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Socket

/**
 * End-to-end tests of the two transports over real loopback sockets.
 *
 * The HTTP cases pin the behaviours a desktop MCP client relies on when it talks to a
 * server it did not write itself: status codes, required headers and error codes from
 * `basic/transports/streamable-http.mdx`.
 */
class TransportTest {

    private val token = "shm_test_token"

    private lateinit var core: McpServerCore
    private lateinit var http: StreamableHttpTransport
    private lateinit var line: LineTransport

    @Before
    fun setUp() {
        core = McpServerCore(
            registry = McpToolRegistry(listOf(StubTool("alpha"), StubTool("beta", mutating = true))),
            identity = McpServerIdentity("SimpleHook-MCP", "test"),
            options = McpServerOptions(allowMutations = true)
        )
        http = StreamableHttpTransport(
            core = core,
            config = HttpTransportConfig(port = 0, authToken = token)
        )
        http.start()
        line = LineTransport(
            core = core,
            config = LineTransportConfig(port = 0, authToken = token)
        )
        line.start()
    }

    @After
    fun tearDown() {
        http.stop()
        line.stop()
    }

    // --- helpers -------------------------------------------------------------------

    private data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

    private fun send(
        method: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
        path: String = "/mcp"
    ): HttpResponse {
        Socket("127.0.0.1", http.boundPort).use { socket ->
            socket.soTimeout = 10_000
            val payload = body?.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                if (payload != null) append("Content-Length: ").append(payload.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out: OutputStream = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.ISO_8859_1))
            payload?.let { out.write(it) }
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readHeadLine(input)
            val status = statusLine.split(' ')[1].toInt()
            val responseHeaders = LinkedHashMap<String, String>()
            while (true) {
                val line = readHeadLine(input)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                responseHeaders[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val length = responseHeaders["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(bodyBytes, read, length - read)
                if (count < 0) break
                read += count
            }
            return HttpResponse(status, responseHeaders, String(bodyBytes, Charsets.UTF_8))
        }
    }

    private fun readHeadLine(input: BufferedInputStream): String {
        val builder = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) break
            if (c != '\r'.code) builder.append(c.toChar())
        }
        return builder.toString()
    }

    private fun requestBody(
        method: String = "tools/list",
        params: String = "{}",
        id: String = "1"
    ): String = """
        {
          "jsonrpc": "2.0",
          "id": "$id",
          "method": "$method",
          "params": $params
        }
    """.trimIndent()

    private fun metaObject(): String =
        """{"$META_VERSION":"${McpProtocolVersion.CURRENT}","$META_CAPS":{}}"""

    private fun standardHeaders(
        method: String,
        name: String? = null,
        version: String = McpProtocolVersion.CURRENT,
        accept: String = "application/json, text/event-stream",
        origin: String? = null
    ): MutableMap<String, String> = mutableMapOf(
        "Content-Type" to "application/json",
        "Accept" to accept,
        "Authorization" to "Bearer $token",
        "MCP-Protocol-Version" to version,
        "Mcp-Method" to method
    ).apply {
        if (name != null) put("Mcp-Name", name)
        if (origin != null) put("Origin", origin)
    }

    private fun toolsListBody(): String =
        requestBody(params = """{"_meta":${metaObject()}}""")

    private fun toolsCallBody(name: String, arguments: String = "{}"): String =
        requestBody(
            method = "tools/call",
            params = """{"name":"$name","arguments":$arguments,"_meta":${metaObject()}}"""
        )

    // --- HTTP cases ----------------------------------------------------------------

    @Test
    fun `successful tools list returns 200 json with a result`() {
        val response = send(
            "POST",
            toolsListBody(),
            standardHeaders("tools/list")
        )
        assertEquals(200, response.status)
        assertTrue(response.headers["content-type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(response.body).jsonObject
        val names = body["result"]!!.jsonObject["tools"]!!.let {
            (it as JsonArray).map { tool ->
                tool.jsonObject["name"]!!.jsonPrimitive.content
            }
        }
        assertTrue(names.contains("alpha"))
    }

    @Test
    fun `tools call requires Mcp-Name and returns HeaderMismatch when absent`() {
        val headers = standardHeaders("tools/call").apply { remove("Mcp-Name") }
        val response = send("POST", toolsCallBody("alpha"), headers)
        assertEquals(400, response.status)
        assertEquals(McpErrorCodes.HEADER_MISMATCH, errorCode(response.body))
    }

    @Test
    fun `Mcp-Name must match the body`() {
        val response = send("POST", toolsCallBody("alpha"), standardHeaders("tools/call", "beta"))
        assertEquals(400, response.status)
        assertEquals(McpErrorCodes.HEADER_MISMATCH, errorCode(response.body))
    }

    @Test
    fun `protocol version header must match the body`() {
        val response = send(
            "POST",
            toolsListBody(),
            standardHeaders("tools/list", version = "2025-06-18")
        )
        assertEquals(400, response.status)
        assertEquals(McpErrorCodes.HEADER_MISMATCH, errorCode(response.body))
    }

    @Test
    fun `missing protocol version header is a header mismatch`() {
        val headers = standardHeaders("tools/list").apply { remove("MCP-Protocol-Version") }
        val response = send("POST", toolsListBody(), headers)
        assertEquals(400, response.status)
        assertEquals(McpErrorCodes.HEADER_MISMATCH, errorCode(response.body))
    }

    @Test
    fun `unknown method answers 404 with method not found`() {
        val response = send(
            "POST",
            requestBody("prompts/list", """{"_meta":${metaObject()}}"""),
            standardHeaders("prompts/list")
        )
        assertEquals(404, response.status)
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, errorCode(response.body))
    }

    @Test
    fun `notification answers 202 without body`() {
        val body = """
            {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"_meta":${metaObject()}}}
        """.trimIndent()
        val response = send("POST", body, standardHeaders("notifications/cancelled"))
        assertEquals(202, response.status)
        assertEquals("", response.body)
    }

    @Test
    fun `GET on the endpoint is 405 because the revision has no SSE stream`() {
        val response = send("GET", null, standardHeaders("tools/list"))
        assertEquals(405, response.status)
        assertEquals("POST", response.headers["allow"])
    }

    @Test
    fun `missing bearer token is 401`() {
        val headers = standardHeaders("tools/list").apply { remove("Authorization") }
        val response = send("POST", toolsListBody(), headers)
        assertEquals(401, response.status)
        assertTrue(response.headers["www-authenticate"]!!.startsWith("Bearer"))
    }

    @Test
    fun `non loopback origin is 403`() {
        val response = send(
            "POST",
            toolsListBody(),
            standardHeaders("tools/list", origin = "https://evil.example")
        )
        assertEquals(403, response.status)
    }

    @Test
    fun `loopback origin with a custom port is accepted`() {
        val response = send(
            "POST",
            toolsListBody(),
            standardHeaders("tools/list", origin = "http://127.0.0.1:51234")
        )
        assertEquals(200, response.status)
    }

    @Test
    fun `Accept must advertise both media types`() {
        val response = send(
            "POST",
            toolsListBody(),
            standardHeaders("tools/list", accept = "application/json")
        )
        assertEquals(400, response.status)
        assertEquals(McpErrorCodes.HEADER_MISMATCH, errorCode(response.body))
    }

    @Test
    fun `malformed json is a parse error with 400`() {
        val response = send("POST", "{not json", standardHeaders("tools/list"))
        assertEquals(400, response.status)
        assertEquals(McpErrorCodes.PARSE_ERROR, errorCode(response.body))
    }

    @Test
    fun `unknown path is 404`() {
        val response = send("POST", toolsListBody(), standardHeaders("tools/list"), path = "/other")
        assertEquals(404, response.status)
    }

    // --- line transport ------------------------------------------------------------

    @Test
    fun `line transport round trip with token handshake`() {
        Socket("127.0.0.1", line.boundPort).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream().bufferedReader()

            out.write("$token\n".toByteArray())
            out.write(
                (toolsListBody().replace("\n", "") + "\n").toByteArray()
            )
            out.flush()

            val responseLine = input.readLine()
            val response = Json.parseToJsonElement(responseLine).jsonObject
            assertTrue(response.containsKey("result"))
        }
    }

    @Test
    fun `line transport rejects a wrong token`() {
        Socket("127.0.0.1", line.boundPort).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            val input = socket.getInputStream().bufferedReader()
            out.write("wrong-token\n".toByteArray())
            out.flush()
            val response = Json.parseToJsonElement(input.readLine()).jsonObject
            val code = response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()
            assertEquals(4010, code)
        }
    }

    private fun errorCode(body: String): Int {
        val json = Json.parseToJsonElement(body).jsonObject
        return json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()
    }

    private companion object {
        const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val META_CAPS = "io.modelcontextprotocol/clientCapabilities"
    }
}
