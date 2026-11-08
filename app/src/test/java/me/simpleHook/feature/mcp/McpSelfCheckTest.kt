package me.simpleHook.feature.mcp

import me.simpleHook.feature.mcp.protocol.McpServerCore
import me.simpleHook.feature.mcp.protocol.McpServerIdentity
import me.simpleHook.feature.mcp.protocol.McpServerOptions
import me.simpleHook.feature.mcp.protocol.McpToolRegistry
import me.simpleHook.feature.mcp.selfcheck.McpSelfCheck
import me.simpleHook.feature.mcp.transport.HttpTransportConfig
import me.simpleHook.feature.mcp.transport.StreamableHttpTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * Tests for [McpSelfCheck].
 *
 * The passing case runs against a real [StreamableHttpTransport] on a loopback port, so the
 * check is verified against the actual server implementation rather than a mock. The failing
 * cases cover each diagnostic branch a user can hit.
 */
class McpSelfCheckTest {

    private val token = "shm_selfcheck_token"
    private lateinit var transport: StreamableHttpTransport
    private var serverPort: Int = 0

    @Before
    fun setUp() {
        val core = McpServerCore(
            registry = McpToolRegistry(listOf(StubTool("alpha"))),
            identity = McpServerIdentity("SimpleHook-MCP", "test"),
            options = McpServerOptions(allowMutations = false)
        )
        transport = StreamableHttpTransport(
            core = core,
            config = HttpTransportConfig(port = 0, authToken = token)
        )
        transport.start()
        serverPort = transport.boundPort
    }

    @After
    fun tearDown() {
        transport.stop()
    }

    /** Runs the checker against [port] (defaults to the bound server port). */
    private fun check(
        port: Int = serverPort,
        token: String? = this.token,
        path: String = "/mcp",
        connectTimeoutMs: Int = 2_000,
        readTimeoutMs: Int = 2_000
    ): McpSelfCheck.Result = McpSelfCheck(
        port = port,
        authToken = token,
        path = path,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs
    ).run()

    @Test
    fun `diagnostic - server port is bound and reachable`() {
        println("[diag] boundPort=$serverPort isRunning=${transport.isRunning}")
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress("127.0.0.1", serverPort), 2000)
        println("[diag] connected local=\${socket.localPort}")
        socket.close()
    }

    @Test
    fun `passes against a running server`() {
        println("[diag] precheck isRunning=${transport.isRunning} boundPort=${transport.boundPort} serverPort=$serverPort")
        assertTrue("server must be running", transport.isRunning)
        assertTrue("boundPort must be > 0", transport.boundPort > 0)
        assertEquals("field must match", transport.boundPort, serverPort)
        val result = check()
        assertTrue(result.message, result.ok)
        assertEquals(McpSelfCheck.Stage.DONE, result.stage)
        assertEquals(200, result.httpStatus)
        assertNull(result.failedAt)
        assertNotNull(result.detail)
        assertTrue(result.detail!!.contains("2026-07-28"))
    }

    @Test
    fun `reports PORT stage when nothing is listening`() {
        // Bind then immediately release a port so the number is almost certainly free.
        val freePort = ServerSocket(0).use { it.localPort }
        val result = check(port = freePort)
        assertEquals(McpSelfCheck.Stage.PORT, result.stage)
        assertTrue(result.message, result.message.contains("没有进程在监听"))
        // The message must be actionable and must not leak the token.
        assertTrue(!result.message.contains(token))
    }

    @Test
    fun `reports HTTP stage with 401 when the token is wrong`() {
        println("[diag-401] serverPort=$serverPort isRunning=${transport.isRunning} bound=${transport.boundPort}")
        val result = check(token = "wrong-token")
        println("[diag-401] stage=${result.stage} status=${result.httpStatus} msg=${result.message}")
        assertEquals(McpSelfCheck.Stage.HTTP, result.stage)
        assertEquals(401, result.httpStatus)
        assertTrue(result.message, result.message.contains("令牌"))
    }

    @Test
    fun `reports HTTP stage with 404 when the path is wrong`() {
        val result = check(path = "/nope")
        assertEquals(McpSelfCheck.Stage.HTTP, result.stage)
        assertEquals(404, result.httpStatus)
        assertTrue(result.message, result.message.contains("404"))
    }

    @Test
    fun `reports HTTP stage when the token is missing entirely`() {
        val result = check(token = null)
        assertEquals(McpSelfCheck.Stage.HTTP, result.stage)
        assertEquals(401, result.httpStatus)
    }

    @Test
    fun `success message never contains the bearer token`() {
        val result = check()
        // Guard against a false positive: the check must actually have succeeded, otherwise
        // "no token in the message" would pass for the trivial reason that there is no message.
        assertTrue(result.message, result.ok)
        val combined = result.message + (result.detail ?: "")
        assertTrue("token must not be echoed", !combined.contains(token))
    }
}
