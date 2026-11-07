package me.simpleHook.feature.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.simpleHook.feature.mcp.protocol.JsonRpc
import me.simpleHook.feature.mcp.protocol.McpErrorCodes
import me.simpleHook.feature.mcp.protocol.McpMetaKeys
import me.simpleHook.feature.mcp.protocol.McpProtocolVersion
import me.simpleHook.feature.mcp.protocol.McpServerCore
import me.simpleHook.feature.mcp.protocol.McpServerIdentity
import me.simpleHook.feature.mcp.protocol.McpServerOptions
import me.simpleHook.feature.mcp.protocol.McpToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for the parts of MCP revision 2026-07-28 that a client depends on.
 *
 * Each test names the specification section it enforces.
 */
class McpServerCoreTest {

    private fun core(
        allowMutations: Boolean = false,
        pageSize: Int = 50
    ): McpServerCore = McpServerCore(
        registry = McpToolRegistry(
            listOf(
                StubTool("alpha"),
                StubTool("beta", mutating = true)
            )
        ),
        identity = McpServerIdentity("SimpleHook-MCP", "1.0"),
        options = McpServerOptions(allowMutations = allowMutations, listPageSize = pageSize),
        cacheTtlMs = 1000
    )

    private fun meta(version: String = McpProtocolVersion.CURRENT): JsonObject = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", version)
        put("io.modelcontextprotocol/clientCapabilities", buildJsonObject { })
        put(
            "io.modelcontextprotocol/clientInfo",
            buildJsonObject {
                put("name", "JUnit")
                put("version", "1")
            }
        )
    }

    private fun request(
        method: String,
        params: JsonObject = buildJsonObject { },
        id: String = "1"
    ) = JsonRpc.Request(method, JsonRpc.Id.StringId(id), params)

    private fun withMeta(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, value) }
            put("_meta", meta())
        }

    private fun JsonObject.errorCode(): Int =
        this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()

    private fun JsonObject.result(): JsonObject = this["result"]!!.jsonObject

    @Test
    fun `discover advertises the implemented revision and tools capability`() = runBlocking {
        val response = core().handle(
            request(
                McpServerCore.METHOD_DISCOVER,
                buildJsonObject { put("_meta", meta()) }
            ),
            "http"
        )
        val result = response.result()
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        val versions = result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(McpProtocolVersion.CURRENT in versions)
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
        val serverInfo = result["_meta"]!!
            .jsonObject[McpMetaKeys.SERVER_INFO]!!.jsonObject
        assertEquals("SimpleHook-MCP", serverInfo["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request without _meta is rejected with invalid params`() = runBlocking {
        val response = core().handle(
            request("tools/list", buildJsonObject { }),
            "http"
        )
        assertEquals(McpErrorCodes.INVALID_PARAMS, response.errorCode())
    }

    @Test
    fun `request without client capabilities is rejected with invalid params`() = runBlocking {
        val params = buildJsonObject {
            put(
                "_meta",
                buildJsonObject {
                    put("io.modelcontextprotocol/protocolVersion", McpProtocolVersion.CURRENT)
                }
            )
        }
        val response = core().handle(request("tools/list", params), "http")
        assertEquals(McpErrorCodes.INVALID_PARAMS, response.errorCode())
    }

    @Test
    fun `unsupported protocol version reports supported list`() = runBlocking {
        val params = buildJsonObject {
            put(
                "_meta",
                buildJsonObject {
                    put("io.modelcontextprotocol/protocolVersion", "2025-06-18")
                    put("io.modelcontextprotocol/clientCapabilities", buildJsonObject { })
                }
            )
        }
        val response = core().handle(request("tools/list", params), "http")
        assertEquals(McpErrorCodes.UNSUPPORTED_PROTOCOL_VERSION, response.errorCode())
        val data = response["error"]!!.jsonObject["data"]!!.jsonObject
        val supported = data["supported"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(McpProtocolVersion.CURRENT in supported)
        assertEquals("2025-06-18", data["requested"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy initialize is rejected with the supported versions`() = runBlocking {
        val response = core().handle(
            request(
                "initialize",
                buildJsonObject { put("_meta", meta(McpProtocolVersion.CURRENT)) }
            ),
            "http"
        )
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, response.errorCode())
        val data = response["error"]!!.jsonObject["data"]!!.jsonObject
        assertTrue(McpProtocolVersion.CURRENT in
            data["supported"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `unknown method yields method not found`() = runBlocking {
        val response = core().handle(
            request("prompts/list", buildJsonObject { put("_meta", meta()) }),
            "http"
        )
        assertEquals(McpErrorCodes.METHOD_NOT_FOUND, response.errorCode())
    }

    @Test
    fun `tools list hides mutating tools when mutations are disabled`() = runBlocking {
        val response = core(allowMutations = false).handle(
            request("tools/list", buildJsonObject { put("_meta", meta()) }),
            "http"
        )
        val names = response.result()["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("alpha"), names)
    }

    @Test
    fun `tools list is deterministic and paginated`() = runBlocking {
        val server = core(allowMutations = true, pageSize = 1)

        val first = server.handle(
            request("tools/list", buildJsonObject { put("_meta", meta()) }),
            "http"
        ).result()
        val firstNames = first["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("alpha"), firstNames)
        val cursor = first["nextCursor"]!!.jsonPrimitive.content
        assertEquals("alpha", cursor)

        val second = server.handle(
            request(
                "tools/list",
                buildJsonObject {
                    put("_meta", meta())
                    put("cursor", JsonPrimitive(cursor))
                }
            ),
            "http"
        ).result()
        val secondNames = second["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("beta"), secondNames)
        assertNull(second["nextCursor"])
    }

    @Test
    fun `tool definitions carry a valid schema and annotations`() = runBlocking {
        val response = core(allowMutations = true).handle(
            request("tools/list", buildJsonObject { put("_meta", meta()) }),
            "http"
        )
        val tool = response.result()["tools"]!!.jsonArray.first().jsonObject
        assertEquals("object", tool["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val annotations = tool["annotations"]!!.jsonObject
        assertTrue(annotations["readOnlyHint"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("alpha", tool["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mutating tool call is refused when mutations are disabled`() = runBlocking {
        val params = withMeta(
            "name" to JsonPrimitive("beta"),
            "arguments" to buildJsonObject { }
        )
        val response = core(allowMutations = false)
            .handle(request("tools/call", params), "http")
        assertEquals(McpErrorCodes.INVALID_PARAMS, response.errorCode())
    }

    @Test
    fun `unknown tool yields invalid params`() = runBlocking {
        val params = withMeta(
            "name" to JsonPrimitive("nope"),
            "arguments" to buildJsonObject { }
        )
        val response = core().handle(request("tools/call", params), "http")
        assertEquals(McpErrorCodes.INVALID_PARAMS, response.errorCode())
        assertTrue(response.toString().contains("Unknown tool"))
    }

    @Test
    fun `tool call result includes structured content and text block`() = runBlocking {
        val params = withMeta(
            "name" to JsonPrimitive("alpha"),
            "arguments" to buildJsonObject { }
        )
        val result = core().handle(request("tools/call", params), "http").result()
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertFalse(result["isError"]!!.jsonPrimitive.content.toBoolean())
        val content = result["content"]!!.jsonArray
        assertEquals("text", content.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertNotNull(result["structuredContent"])
    }
}
