package me.simpleHook.feature.mcp.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.simpleHook.feature.mcp.McpDataSource
import me.simpleHook.feature.mcp.protocol.JsonSchemaBuilder
import me.simpleHook.feature.mcp.protocol.McpCallContext
import me.simpleHook.feature.mcp.protocol.McpTool
import me.simpleHook.feature.mcp.protocol.ToolArguments
import me.simpleHook.feature.mcp.protocol.ToolResult

/**
 * Reports the identity and capabilities of this server so a client can show them before
 * touching any user data. Read-only.
 */
class ServerInfoTool(private val data: McpDataSource) : McpTool {

    override val name: String = "server_info"
    override val title: String = "Server information"
    override val description: String =
        "Describes the SimpleHook MCP server: app version, MCP protocol revision, enabled " +
            "transports, endpoints and whether configuration-changing tools are allowed."

    override fun inputSchema(): JsonObject = JsonSchemaBuilder.empty()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("server", JsonPrimitive(data.serverName))
                put("appVersionName", JsonPrimitive(data.appVersionName))
                put("appVersionCode", JsonPrimitive(data.appVersionCode))
                put("protocolVersion", JsonPrimitive(data.protocolVersion))
                put("androidSdk", JsonPrimitive(data.androidSdk))
                put("httpEndpoint", ToolJson.string(data.httpEndpoint))
                put("stdioEndpoint", ToolJson.string(data.stdioEndpoint))
                put("mutationsAllowed", JsonPrimitive(data.allowMutations))
                put("transport", JsonPrimitive(ctx.transport))
                put(
                    "recordTypes",
                    buildJsonArray { data.recordTypeNames().forEach { add(JsonPrimitive(it)) } }
                )
            }
            ToolResult.success(payload, ToolJson.text(payload))
        }
}

/** Lists installed packages, optionally filtered by a query string. Read-only. */
class ListAppsTool(private val data: McpDataSource) : McpTool {

    override val name: String = "list_apps"
    override val title: String = "List installed apps"
    override val description: String =
        "Lists installed packages with their display name and version. Useful to find the " +
            "package name of a target app before reading or editing its hook configuration."

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string(
            "query",
            "Case-insensitive filter matched against the package name and the app label."
        )
        boolean(
            "includeSystem",
            "Include system packages. Defaults to false because hooking system apps is unusual.",
            default = false
        )
        PaginationArgs.register(this)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("query", "includeSystem", PaginationArgs.OFFSET, PaginationArgs.LIMIT))
            val page = PaginationArgs.read(arguments)
            val (apps, total) = data.listApps(
                query = arguments.optionalString("query"),
                includeSystem = arguments.optionalBoolean("includeSystem") ?: false,
                offset = page.offset,
                limit = page.limit
            )
            val items = apps.map { app ->
                buildJsonObject {
                    put("packageName", JsonPrimitive(app.packageName))
                    put("appName", JsonPrimitive(app.appName))
                    put("versionName", JsonPrimitive(app.versionName))
                    put("versionCode", JsonPrimitive(app.versionCode))
                    put("system", JsonPrimitive(app.system))
                }
            }
            val payload = ToolJson.page("apps", items, page.offset, page.limit, total)
            ToolResult.success(payload, ToolJson.text(payload))
        }
}

/** Lists packages that already have a SimpleHook configuration. Read-only. */
class ListHookConfigsTool(private val data: McpDataSource) : McpTool {

    override val name: String = "list_hook_configs"
    override val title: String = "List hook configurations"
    override val description: String =
        "Lists the apps that have a SimpleHook configuration, with the number of hook rules, " +
            "whether the configuration is enabled and whether extension switches are set."

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("query", "Case-insensitive filter on package name or app name.")
        PaginationArgs.register(this)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("query", PaginationArgs.OFFSET, PaginationArgs.LIMIT))
            val page = PaginationArgs.read(arguments)
            val (configs, total) = data.listConfigs(
                query = arguments.optionalString("query"),
                offset = page.offset,
                limit = page.limit
            )
            val items = configs.map { config ->
                buildJsonObject {
                    put("packageName", JsonPrimitive(config.packageName))
                    put("appName", JsonPrimitive(config.appName))
                    put("versionName", JsonPrimitive(config.versionName))
                    put("enabled", JsonPrimitive(config.enabled))
                    put("ruleCount", JsonPrimitive(config.ruleCount))
                    put("enabledRuleCount", JsonPrimitive(config.enabledRuleCount))
                    put("extensionConfigured", JsonPrimitive(config.extensionConfigured))
                    put("extensionEnabled", JsonPrimitive(config.extensionEnabled))
                    put("configId", JsonPrimitive(config.configId))
                }
            }
            val payload = ToolJson.page("configs", items, page.offset, page.limit, total)
            ToolResult.success(payload, ToolJson.text(payload))
        }
}

/** Returns the full configuration of one package. Read-only. */
class GetHookConfigTool(private val data: McpDataSource) : McpTool {

    override val name: String = "get_hook_config"
    override val title: String = "Get hook configuration"
    override val description: String =
        "Returns the hook rules and the extension configuration of one package. " +
            "The returned rule shape is accepted as-is by set_hook_config."

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("packageName", "Target package name, e.g. com.example.app.", required = true)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("packageName"))
            val packageName = arguments.requireString("packageName")
            val config = data.getConfig(packageName)
                ?: return@withContext ToolResult.failure(
                    "No configuration exists for '$packageName'."
                )
            val payload = buildJsonObject {
                put("packageName", JsonPrimitive(config.packageName))
                put("appName", JsonPrimitive(config.appName))
                put("versionName", JsonPrimitive(config.versionName))
                put("description", JsonPrimitive(config.description))
                put("enabled", JsonPrimitive(config.enabled))
                put("configId", JsonPrimitive(config.configId))
                put("rules", HookRuleJson.toJson(config.rules))
                put("extensionConfig", ToolJson.string(config.extensionJson))
            }
            ToolResult.success(payload, ToolJson.text(payload))
        }
}

/** Renders hook rules to and from JSON. Shared by every tool that exposes rules. */
internal object HookRuleJson {

    fun toJson(rules: List<McpDataSource.HookRule>): JsonArray = buildJsonArray {
        rules.forEachIndexed { index, rule ->
            add(
                buildJsonObject {
                    put("index", JsonPrimitive(index))
                    put("mode", JsonPrimitive(rule.mode))
                    put("modeName", JsonPrimitive(HookModes.nameOf(rule.mode)))
                    put("className", JsonPrimitive(rule.className))
                    put("methodName", JsonPrimitive(rule.methodName))
                    put("params", JsonPrimitive(rule.params))
                    put("fieldName", JsonPrimitive(rule.fieldName))
                    put("fieldClassName", JsonPrimitive(rule.fieldClassName))
                    put("resultValues", JsonPrimitive(rule.resultValues))
                    put("hookPoint", JsonPrimitive(rule.hookPoint))
                    put("returnClassName", JsonPrimitive(rule.returnClassName))
                    put("fieldType", JsonPrimitive(rule.fieldType))
                    put("desc", JsonPrimitive(rule.desc))
                    put("enabled", JsonPrimitive(rule.enable))
                }
            )
        }
    }
}

/**
 * Hook modes as understood by `me.simpleHook.core.constant.Constant`.
 *
 * The enum names are used in tool schemas so a model never has to guess magic numbers; the
 * numeric values match the constants used by the hook engine.
 */
object HookModes {
    const val RETURN_VALUE = 0
    const val PARAM_VALUE = 1
    const val INTERRUPT = 2
    const val STATIC_FIELD = 3
    const val FIELD = 4
    const val RECORD_PARAMS = 5
    const val RECORD_RETURN = 6
    const val RECORD_PARAMS_RETURN = 7
    const val RECORD_STATIC_FIELD = 8
    const val RECORD_INSTANCE_FIELD = 9
    const val RETURN_VALUE_OBJECT = 10

    val all: List<Int> = listOf(
        RETURN_VALUE,
        PARAM_VALUE,
        INTERRUPT,
        STATIC_FIELD,
        FIELD,
        RECORD_PARAMS,
        RECORD_RETURN,
        RECORD_PARAMS_RETURN,
        RECORD_STATIC_FIELD,
        RECORD_INSTANCE_FIELD,
        RETURN_VALUE_OBJECT
    )

    fun nameOf(mode: Int): String = when (mode) {
        RETURN_VALUE -> "HOOK_RETURN"
        PARAM_VALUE -> "HOOK_PARAM"
        INTERRUPT -> "HOOK_BREAK"
        STATIC_FIELD -> "HOOK_STATIC_FIELD"
        FIELD -> "HOOK_FIELD"
        RECORD_PARAMS -> "HOOK_RECORD_PARAMS"
        RECORD_RETURN -> "HOOK_RECORD_RETURN"
        RECORD_PARAMS_RETURN -> "HOOK_RECORD_PARAMS_RETURN"
        RECORD_STATIC_FIELD -> "HOOK_RECORD_STATIC_FIELD"
        RECORD_INSTANCE_FIELD -> "HOOK_RECORD_INSTANCE_FIELD"
        RETURN_VALUE_OBJECT -> "HOOK_RETURN2"
        else -> "UNKNOWN_$mode"
    }

    fun names(): List<String> = all.map { nameOf(it) }

    fun valueOf(name: String): Int? =
        all.firstOrNull { nameOf(it).equals(name, ignoreCase = true) }
}
