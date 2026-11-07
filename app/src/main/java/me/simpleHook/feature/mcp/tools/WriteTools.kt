package me.simpleHook.feature.mcp.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.simpleHook.feature.mcp.McpDataSource
import me.simpleHook.feature.mcp.protocol.JsonSchemaBuilder
import me.simpleHook.feature.mcp.protocol.McpCallContext
import me.simpleHook.feature.mcp.protocol.McpTool
import me.simpleHook.feature.mcp.protocol.ToolArgumentException
import me.simpleHook.feature.mcp.protocol.ToolArguments
import me.simpleHook.feature.mcp.protocol.ToolResult

/** Shared schema fragment describing one hook rule. */
internal object HookRuleSchema {

    private val MODE_NAMES = listOf(
        "HOOK_RETURN" to "return a fixed value from a method",
        "HOOK_PARAM" to "replace argument values before the method runs",
        "HOOK_BREAK" to "skip the method entirely",
        "HOOK_STATIC_FIELD" to "overwrite a static field",
        "HOOK_FIELD" to "write a field when the hooked method runs",
        "HOOK_RECORD_PARAMS" to "record argument values",
        "HOOK_RECORD_RETURN" to "record the return value",
        "HOOK_RECORD_PARAMS_RETURN" to "record arguments and the return value",
        "HOOK_RECORD_STATIC_FIELD" to "record a static field value",
        "HOOK_RECORD_INSTANCE_FIELD" to "record an instance field value",
        "HOOK_RETURN2" to "return an object built from JSON"
    )

    fun schema(): JsonObject = JsonSchemaBuilder.nested {
        string(
            "mode",
            "Hook mode name: " + MODE_NAMES.joinToString("; ") { "${it.first} = ${it.second}" },
            required = true,
            default = "HOOK_RETURN"
        )
        string("className", "Target class in Java notation, e.g. com.example.Foo.")
        string("methodName", "Method name; '*' hooks every method. Use <init> for constructors.")
        string(
            "params",
            "Comma separated Java parameter types, e.g. java.lang.String,int. " +
                "Empty for no-argument methods; '*' for every overload."
        )
        string("fieldName", "Field name for field modes.")
        string("fieldClassName", "Class that declares the field, for field modes.")
        string("fieldType", "Java type of the field, e.g. java.lang.String.")
        string("resultValues", "Replacement value (or JSON for HOOK_RETURN2).")
        string("hookPoint", "'before' or 'after'; empty means after for field modes.")
        string("returnClassName", "Return value class name for HOOK_RETURN2.")
        string("desc", "Free-form note shown in the app.")
        boolean("enabled", "Whether the rule is active.", default = true)
    }

    /** Allowed argument names for one rule object. */
    val KEYS = setOf(
        "mode", "className", "methodName", "params", "fieldName", "fieldClassName",
        "fieldType", "resultValues", "hookPoint", "returnClassName", "desc", "enabled"
    )

    fun read(raw: JsonObject): McpDataSource.HookRule = read(ToolArguments(raw))

    fun read(args: ToolArguments): McpDataSource.HookRule {
        args.rejectUnknown(KEYS)
        val modeName = args.requireString("mode")
        val mode = HookModes.valueOf(modeName)
            ?: throw ToolArgumentException(
                "Unknown mode '$modeName'. Allowed: ${HookModes.names().joinToString(", ")}"
            )

        val className = args.optionalString("className")?.trim().orEmpty()
        val fieldName = args.optionalString("fieldName")?.trim().orEmpty()
        val methodName = args.optionalString("methodName")?.trim().orEmpty()

        val fieldMode = mode in setOf(
            HookModes.STATIC_FIELD,
            HookModes.FIELD,
            HookModes.RECORD_STATIC_FIELD,
            HookModes.RECORD_INSTANCE_FIELD
        )
        if (fieldMode) {
            if (fieldName.isEmpty()) {
                throw ToolArgumentException("Argument 'fieldName' is required for field modes")
            }
        } else if (className.isEmpty()) {
            throw ToolArgumentException("Argument 'className' is required for method modes")
        }

        if (mode == HookModes.RETURN_VALUE_OBJECT && args.requireStringOrNull("returnClassName") == null) {
            throw ToolArgumentException("Argument 'returnClassName' is required for HOOK_RETURN2")
        }

        val hookPoint = args.optionalString("hookPoint")?.trim()?.lowercase().orEmpty()
        if (hookPoint.isNotEmpty() && hookPoint != "before" && hookPoint != "after") {
            throw ToolArgumentException("Argument 'hookPoint' must be 'before' or 'after'")
        }

        return McpDataSource.HookRule(
            mode = mode,
            className = className,
            methodName = methodName,
            params = args.optionalString("params")?.trim().orEmpty(),
            fieldName = fieldName,
            fieldClassName = args.optionalString("fieldClassName")?.trim().orEmpty(),
            resultValues = args.optionalString("resultValues").orEmpty(),
            hookPoint = hookPoint,
            returnClassName = args.optionalString("returnClassName")?.trim().orEmpty(),
            fieldType = args.optionalString("fieldType")?.trim().orEmpty(),
            desc = args.optionalString("desc").orEmpty(),
            enable = args.optionalBoolean("enabled") ?: true
        )
    }

    private fun ToolArguments.requireStringOrNull(name: String): String? =
        optionalString(name)?.takeIf { it.isNotBlank() }
}

/** Replaces every hook rule of an existing configuration. Mutating. */
class SetHookConfigTool(private val data: McpDataSource) : McpTool {

    override val name: String = "set_hook_config"
    override val title: String = "Replace hook rules"
    override val description: String =
        "Replaces the complete hook rule list of an existing configuration. The configuration " +
            "must already exist (create it in the app first). The app that is hooked picks up " +
            "the change when it is restarted."

    override val mutating: Boolean = true

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("packageName", "Target package name.", required = true)
        objectArray(
            "rules",
            "Complete new rule list. An empty array clears every rule.",
            HookRuleSchema.schema(),
            required = true
        )
        boolean(
            "enabled",
            "Also set the configuration enabled state. Omit to keep the current state."
        )
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("packageName", "rules", "enabled"))
            val packageName = arguments.requireString("packageName")
            val rules = readRules(arguments)
            val detail = data.setRules(
                packageName = packageName,
                rules = rules,
                enabled = arguments.optionalBoolean("enabled")
            )
            ToolResult.success(
                ConfigJson.detail(detail),
                "Replaced the hook configuration of $packageName: ${detail.rules.size} rule(s)."
            )
        }

    private fun readRules(arguments: ToolArguments): List<McpDataSource.HookRule> {
        val array = arguments.raw()["rules"]
            ?: throw ToolArgumentException("Argument 'rules' is required (use [] to clear all rules)")
        val items = array as? kotlinx.serialization.json.JsonArray
            ?: throw ToolArgumentException("Argument 'rules' must be an array of rule objects")
        return items.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw ToolArgumentException("rules[$index] must be an object")
            try {
                HookRuleSchema.read(obj)
            } catch (error: ToolArgumentException) {
                throw ToolArgumentException("rules[$index]: ${error.message}")
            }
        }
    }
}

/** Appends a single hook rule. Mutating. */
class AddHookRuleTool(private val data: McpDataSource) : McpTool {

    override val name: String = "add_hook_rule"
    override val title: String = "Add a hook rule"
    override val description: String =
        "Appends one hook rule to an existing configuration. Use parse_hook_signature to build " +
            "a rule from a Smali member reference."

    override val mutating: Boolean = true

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("packageName", "Target package name.", required = true)
        schema("rule", HookRuleSchema.schema(), required = true)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("packageName", "rule"))
            val packageName = arguments.requireString("packageName")
            val rule = HookRuleSchema.read(arguments.requireObject("rule"))
            val detail = data.addRule(packageName, rule)
            ToolResult.success(
                ConfigJson.detail(detail),
                "Added rule #${detail.rules.size - 1} to $packageName."
            )
        }
}

/** Deletes one hook rule by index. Mutating. */
class DeleteHookRuleTool(private val data: McpDataSource) : McpTool {

    override val name: String = "delete_hook_rule"
    override val title: String = "Delete a hook rule"
    override val description: String =
        "Deletes one hook rule of an existing configuration, addressed by its index as " +
            "returned by get_hook_config or list_hook_configs."

    override val mutating: Boolean = true

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("packageName", "Target package name.", required = true)
        integer("index", "Rule index (0-based).", required = true, minimum = 0)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("packageName", "index"))
            val packageName = arguments.requireString("packageName")
            val index = arguments.requireInt("index")
            val detail = data.deleteRule(packageName, index)
            ToolResult.success(
                ConfigJson.detail(detail),
                "Deleted rule #$index of $packageName; ${detail.rules.size} rule(s) remain."
            )
        }
}

/**
 * Patches the extension ("扩展功能") configuration of a package. The patch is merged
 * recursively, so a client only sends the switches it wants to change. Mutating.
 */
class SetExtensionConfigTool(private val data: McpDataSource) : McpTool {

    override val name: String = "set_extension_config"
    override val title: String = "Change extension switches"
    override val description: String =
        "Merges a patch into the extension configuration of a package, creating the " +
            "extension entry if the app has none. Only the given keys change. Unknown keys are " +
            "rejected. Valid keys follow the app's extension model, e.g. " +
            "'popupConfig.recordDialog', 'jsonConfig.recordObject', 'filterClipboard.enable'."

    override val mutating: Boolean = true

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("packageName", "Target package name.", required = true)
        // A free-form object: the patch keys are validated by the data source, not here.
        schema(
            "patch",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("additionalProperties", JsonPrimitive(true))
            },
            required = true
        )
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("packageName", "patch"))
            val packageName = arguments.requireString("packageName")
            val patch = arguments.requireObject("patch")
            if (patch.isEmpty()) {
                throw ToolArgumentException("Argument 'patch' must contain at least one key")
            }
            val merged = data.patchExtension(packageName, patch.toString())
            val payload = buildJsonObject {
                put("packageName", JsonPrimitive(packageName))
                put("extensionConfig", JsonPrimitive(merged))
            }
            ToolResult.success(payload, "Updated the extension configuration of $packageName.")
        }
}

/** Enables or disables a configuration. Mutating. */
class EnableHookConfigTool(private val data: McpDataSource) : McpTool {

    override val name: String = "enable_hook_config"
    override val title: String = "Enable or disable a configuration"
    override val description: String =
        "Turns the SimpleHook configuration of a package on or off. A disabled configuration " +
            "also removes the package from the LSPosed scope for its rules."

    override val mutating: Boolean = true

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("packageName", "Target package name.", required = true)
        boolean("enabled", "true to enable, false to disable.", required = true)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("packageName", "enabled"))
            val packageName = arguments.requireString("packageName")
            val enabled = arguments.requireBoolean("enabled")
            val detail = data.setConfigEnabled(packageName, enabled)
            ToolResult.success(
                ConfigJson.detail(detail),
                "Configuration of $packageName is now ${if (enabled) "enabled" else "disabled"}."
            )
        }
}

/** Deletes the configuration of a package. Mutating and destructive. */
class DeleteHookConfigTool(private val data: McpDataSource) : McpTool {

    override val name: String = "delete_hook_config"
    override val title: String = "Delete a configuration"
    override val description: String =
        "Permanently deletes the SimpleHook configuration of a package, including the copy " +
            "written for the hooked app. This cannot be undone."

    override val mutating: Boolean = true

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("packageName", "Target package name.", required = true)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("packageName"))
            val packageName = arguments.requireString("packageName")
            val deleted = data.deleteConfig(packageName)
            val payload = buildJsonObject {
                put("packageName", JsonPrimitive(packageName))
                put("deleted", JsonPrimitive(deleted))
            }
            if (!deleted) {
                ToolResult.failure("No configuration exists for '$packageName'.")
            } else {
                ToolResult.success(payload, "Deleted the configuration of $packageName.")
            }
        }
}

/** Deletes captured records. Mutating and destructive. */
class DeleteRecordsTool(private val data: McpDataSource) : McpTool {

    override val name: String = "delete_records"
    override val title: String = "Delete captured records"
    override val description: String =
        "Deletes captured records, either by explicit ids or every record of one package. " +
            "Exactly one of 'ids' and 'packageName' must be given."

    override val mutating: Boolean = true

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        integerArray("ids", "Record ids to delete (from list_records).", minimum = 1)
        string(
            "packageName",
            "Delete every record captured for this package instead of specific ids."
        )
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("ids", "packageName"))
            val ids = arguments.optionalIntList("ids")
            val packageName = arguments.optionalString("packageName")

            if (ids != null && packageName != null) {
                throw ToolArgumentException("Give either 'ids' or 'packageName', not both")
            }
            if (ids == null && packageName == null) {
                throw ToolArgumentException("Either 'ids' or 'packageName' is required")
            }

            if (ids != null) {
                val deleted = data.deleteRecords(ids)
                val payload = buildJsonObject {
                    put("deleted", JsonPrimitive(deleted))
                    put("requested", JsonPrimitive(ids.size))
                }
                ToolResult.success(payload, "Deleted $deleted record(s).")
            } else {
                val deleted = data.deleteRecordsOfPackage(packageName!!)
                val payload = buildJsonObject {
                    put("packageName", JsonPrimitive(packageName))
                    put("deleted", JsonPrimitive(deleted))
                }
                ToolResult.success(
                    payload,
                    "Deleted $deleted record(s) of $packageName."
                )
            }
        }
}

/** Serialises configuration details for the write tools' responses. */
internal object ConfigJson {

    fun detail(config: McpDataSource.ConfigDetail): JsonObject = buildJsonObject {
        put("packageName", JsonPrimitive(config.packageName))
        put("appName", JsonPrimitive(config.appName))
        put("versionName", JsonPrimitive(config.versionName))
        put("description", JsonPrimitive(config.description))
        put("enabled", JsonPrimitive(config.enabled))
        put("configId", JsonPrimitive(config.configId))
        put("rules", HookRuleJson.toJson(config.rules))
        put("extensionConfig", ToolJson.string(config.extensionJson))
        // The target app reads its configuration when a process starts, so a change only
        // takes effect after the user restarts that app.
        put("restartRequired", JsonPrimitive(true))
    }
}
