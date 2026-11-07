package me.simpleHook.feature.mcp

import me.simpleHook.feature.mcp.protocol.McpServerCore
import me.simpleHook.feature.mcp.protocol.McpServerIdentity
import me.simpleHook.feature.mcp.protocol.McpServerOptions
import me.simpleHook.feature.mcp.protocol.McpToolRegistry
import me.simpleHook.feature.mcp.tools.AddHookRuleTool
import me.simpleHook.feature.mcp.tools.DeleteHookConfigTool
import me.simpleHook.feature.mcp.tools.DeleteHookRuleTool
import me.simpleHook.feature.mcp.tools.DeleteRecordsTool
import me.simpleHook.feature.mcp.tools.EnableHookConfigTool
import me.simpleHook.feature.mcp.tools.GetHookConfigTool
import me.simpleHook.feature.mcp.tools.GetRecordTool
import me.simpleHook.feature.mcp.tools.ListAppsTool
import me.simpleHook.feature.mcp.tools.ListHookConfigsTool
import me.simpleHook.feature.mcp.tools.ListRecordsTool
import me.simpleHook.feature.mcp.tools.ParseSignatureTool
import me.simpleHook.feature.mcp.tools.ServerInfoTool
import me.simpleHook.feature.mcp.tools.SetExtensionConfigTool
import me.simpleHook.feature.mcp.tools.SetHookConfigTool

/**
 * Builds the MCP server for one start of the foreground service.
 *
 * A fresh instance is created on every start so settings changed while the server was
 * stopped (mutation switch, ports) take effect, and so tests can build a server over a fake
 * [McpDataSource].
 */
object McpServerFactory {

    /** `tools/list` page size; clients cache the list, so pages stay small. */
    private const val LIST_PAGE_SIZE = 50

    private const val CACHE_TTL_MS = 60_000L

    val instructions: String = """
        SimpleHook is an Android Xposed/LSPosed companion app for authorized debugging of
        apps you own or are permitted to test.

        Typical workflow:
          1. list_apps to find the target package.
          2. list_hook_configs / get_hook_config to inspect existing rules.
          3. parse_hook_signature to turn a Smali member into a rule, then add_hook_rule or
             set_hook_config. Mutating tools only work when the user enabled them.
          4. Ask the user to restart the target app, then list_records / get_record.

        Records and configurations may contain credentials; do not repeat secrets.
    """.trimIndent()

    fun create(data: McpDataSource): McpServerCore {
        val registry = McpToolRegistry(
            listOf(
                // Read-only tools.
                ServerInfoTool(data),
                ListAppsTool(data),
                ListHookConfigsTool(data),
                GetHookConfigTool(data),
                ListRecordsTool(data),
                GetRecordTool(data),
                ParseSignatureTool(data),
                // Mutating tools, gated by the "allow mutations" switch.
                SetHookConfigTool(data),
                AddHookRuleTool(data),
                DeleteHookRuleTool(data),
                SetExtensionConfigTool(data),
                EnableHookConfigTool(data),
                DeleteHookConfigTool(data),
                DeleteRecordsTool(data)
            )
        )

        return McpServerCore(
            registry = registry,
            identity = McpServerIdentity(
                name = data.serverName + "-MCP",
                version = data.appVersionName
            ),
            options = McpServerOptions(
                allowMutations = data.allowMutations,
                listPageSize = LIST_PAGE_SIZE,
                instructions = instructions
            ),
            cacheTtlMs = CACHE_TTL_MS
        )
    }
}
