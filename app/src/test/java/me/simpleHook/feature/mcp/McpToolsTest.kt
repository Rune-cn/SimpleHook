package me.simpleHook.feature.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.simpleHook.feature.mcp.protocol.McpCallContext
import me.simpleHook.feature.mcp.protocol.McpRequestMeta
import me.simpleHook.feature.mcp.protocol.McpTool
import me.simpleHook.feature.mcp.protocol.McpToolRegistry
import me.simpleHook.feature.mcp.protocol.ToolResult
import me.simpleHook.feature.mcp.tools.AddHookRuleTool
import me.simpleHook.feature.mcp.tools.DeleteRecordsTool
import me.simpleHook.feature.mcp.tools.GetHookConfigTool
import me.simpleHook.feature.mcp.tools.ListRecordsTool
import me.simpleHook.feature.mcp.tools.SetHookConfigTool
import me.simpleHook.feature.mcp.tools.SetExtensionConfigTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the tool surface, run against [FakeMcpDataSource].
 *
 * These cover the contract the MCP client relies on: deterministic schemas, strict argument
 * validation, and the fact that a mutating tool is never reachable unless the user enabled
 * mutations.
 */
class McpToolsTest {

    private val ctx = McpCallContext(
        meta = McpRequestMeta(
            protocolVersion = "2026-07-28",
            clientInfo = null,
            clientCapabilities = JsonObject(emptyMap()),
            logLevel = null
        ),
        requestId = "1",
        transport = "junit"
    )

    private fun data(allowMutations: Boolean = true) = FakeMcpDataSource(allowMutations = allowMutations)

    private fun call(
        tool: McpTool,
        arguments: JsonObject,
        data: McpDataSource
    ): ToolResult = runBlocking { tool.call(arguments.toArguments(), ctx) }

    private fun JsonObject.toArguments() = me.simpleHook.feature.mcp.protocol.ToolArguments(this)

    private fun ToolResult.structured(): JsonObject = structured as JsonObject

    @Test
    fun `every registered tool has a unique valid name and object schema`() {
        val core = McpServerFactory.create(data())
        val registry = McpToolRegistry(
            listOf(
                me.simpleHook.feature.mcp.tools.ServerInfoTool(data()),
                ListAppsTool(data()),
                ListRecordsTool(data()),
                GetHookConfigTool(data()),
                AddHookRuleTool(data()),
                SetHookConfigTool(data()),
                SetExtensionConfigTool(data()),
                DeleteRecordsTool(data())
            )
        )
        registry.names.forEach { name ->
            val tool = registry.find(name)!!
            val schema = tool.inputSchema()
            assertEquals("object", schema["type"]?.jsonPrimitive?.content)
            assertTrue("tool $name must declare a description", tool.description.length > 20)
        }
        assertNotNull(core)
    }

    @Test
    fun `list_records returns a page and rejects unknown types`() = runBlocking {
        val tool = ListRecordsTool(data())
        val ok = tool.call(
            toolArgs("""{"packageName":"com.example.login","limit":10}"""),
            ctx
        )
        assertFalse(ok.isError)
        val payload = ok.structured()
        assertEquals(1, payload["returned"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, payload["total"]!!.jsonPrimitive.content.toInt())
        assertFalse(payload["hasMore"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `list_records rejects an unknown argument name`() = runBlocking {
        val tool = ListRecordsTool(data())
        val result = tool.call(toolArgs("""{"packageNam":"com.example.login"}"""), ctx)
        assertTrue(result.isError)
        assertTrue(result.text!!.contains("Unknown argument"))
    }

    @Test
    fun `get_hook_config exposes rules and extension json`() = runBlocking {
        val tool = GetHookConfigTool(data())
        val result = tool.call(toolArgs("""{"packageName":"com.example.login"}"""), ctx)
        assertFalse(result.isError)
        val payload = result.structured()
        val rules = payload["rules"]!!.jsonArray
        assertEquals(1, rules.size)
        assertEquals("HOOK_RECORD_PARAMS", rules.first().jsonObject["modeName"]!!.jsonPrimitive.content)
        assertNotNull(payload["extensionConfig"])
    }

    @Test
    fun `get_hook_config reports a missing configuration as a tool error`() = runBlocking {
        val tool = GetHookConfigTool(data())
        val result = tool.call(toolArgs("""{"packageName":"com.absent"}"""), ctx)
        assertTrue(result.isError)
    }

    @Test
    fun `set_hook_config replaces rules and reports restart requirement`() = runBlocking {
        val source = data()
        val tool = SetHookConfigTool(source)
        val arguments = toolArgs(
            """
            {
              "packageName": "com.example.login",
              "rules": [
                {
                  "mode": "HOOK_RETURN",
                  "className": "com.example.Vip",
                  "methodName": "isVip",
                  "params": "",
                  "resultValues": "true",
                  "enabled": true
                }
              ],
              "enabled": true
            }
            """.trimIndent()
        )
        val result = tool.call(arguments, ctx)
        assertFalse(result.text ?: "", result.isError)
        val payload = result.structured()
        assertEquals(1, payload["rules"]!!.jsonArray.size)
        val rule = payload["rules"]!!.jsonArray.first().jsonObject
        assertEquals("HOOK_RETURN", rule["modeName"]!!.jsonPrimitive.content)
        assertEquals(0, rule["mode"]!!.jsonPrimitive.content.toInt())
        assertTrue(payload["restartRequired"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `set_hook_config rejects an unknown mode name`() = runBlocking {
        val tool = SetHookConfigTool(data())
        val result = tool.call(
            toolArgs(
                """{"packageName":"com.example.login","rules":[{"mode":"NOPE","className":"A"}]}"""
            ),
            ctx
        )
        assertTrue(result.isError)
        assertTrue(result.text!!.contains("Unknown mode"))
    }

    @Test
    fun `add_hook_rule requires a class name for method modes`() = runBlocking {
        val tool = AddHookRuleTool(data())
        val result = tool.call(
            toolArgs("""{"packageName":"com.example.login","rule":{"mode":"HOOK_RETURN"}}"""),
            ctx
        )
        assertTrue(result.isError)
        assertTrue(result.text!!.contains("className"))
    }

    @Test
    fun `set_extension_config forwards the patch and rejects an empty patch`() = runBlocking {
        val source = data()
        val tool = SetExtensionConfigTool(source)
        val ok = tool.call(
            toolArgs(
                """{"packageName":"com.example.login","patch":{"popupConfig":{"recordDialog":true}}}"""
            ),
            ctx
        )
        assertFalse(ok.isError)
        assertEquals(
            """{"popupConfig":{"recordDialog":true}}""",
            source.lastExtensionPatch
        )

        val empty = tool.call(
            toolArgs("""{"packageName":"com.example.login","patch":{}}"""),
            ctx
        )
        assertTrue(empty.isError)
    }

    @Test
    fun `delete_records requires exactly one selector`() = runBlocking {
        val tool = DeleteRecordsTool(data())
        val none = tool.call(toolArgs("""{}"""), ctx)
        assertTrue(none.isError)

        val both = tool.call(
            toolArgs("""{"ids":[1],"packageName":"com.example.login"}"""),
            ctx
        )
        assertTrue(both.isError)

        val ok = tool.call(toolArgs("""{"ids":[1]}"""), ctx)
        assertFalse(ok.isError)
        assertEquals(1, ok.structured()["deleted"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `tool results never leak internal exception details`() = runBlocking {
        val failing = StubTool("boom", body = { throw IllegalStateException("secret-token-xyz") })
        val registry = McpToolRegistry(listOf(failing))
        val outcome = registry.call("boom", JsonObject(emptyMap()), ctx, allowMutations = true)
        val result = (outcome as McpToolRegistry.Outcome.Result).result
        assertTrue(result.isError)
        assertFalse(result.text!!.contains("secret-token-xyz"))
    }

    private fun toolArgs(json: String) =
        me.simpleHook.feature.mcp.protocol.ToolArguments(parse(json))

    private fun parse(json: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
}
