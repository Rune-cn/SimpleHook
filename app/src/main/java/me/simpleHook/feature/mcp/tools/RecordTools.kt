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
import me.simpleHook.feature.mcp.protocol.ToolArguments
import me.simpleHook.feature.mcp.protocol.ToolResult

/** Queries captured records. Read-only. */
class ListRecordsTool(private val data: McpDataSource) : McpTool {

    override val name: String = "list_records"
    override val title: String = "List captured records"
    override val description: String =
        "Queries records captured by SimpleHook (hook parameters, return values, clipboard, " +
            "algorithm results, crashes, ...). Returns summaries; call get_record for the " +
            "full payload. Records may contain secrets, so only request what you need."

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string("packageName", "Only return records captured for this package.")
        string(
            "type",
            "Only return records of this type. The known types are listed by server_info, " +
                "e.g. RecordParam, RecordReturn, Clipboard, CrashCaught."
        )
        string(
            "keyword",
            "Substring filter applied to the record summary and payload."
        )
        boolean(
            "markedOnly",
            "Only return records that the user marked in the app.",
            default = false
        )
        PaginationArgs.register(this)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(
                setOf(
                    "packageName", "type", "keyword", "markedOnly",
                    PaginationArgs.OFFSET, PaginationArgs.LIMIT
                )
            )
            val page = PaginationArgs.read(arguments)
            val records = data.queryRecords(
                packageName = arguments.optionalString("packageName"),
                type = arguments.optionalString("type"),
                keyword = arguments.optionalString("keyword"),
                markedOnly = arguments.optionalBoolean("markedOnly") ?: false,
                offset = page.offset,
                limit = page.limit
            )
            val items = records.records.map { record ->
                buildJsonObject {
                    put("id", JsonPrimitive(record.id))
                    put("type", JsonPrimitive(record.type))
                    put("subType", JsonPrimitive(record.subType))
                    put("packageName", JsonPrimitive(record.packageName))
                    put("processName", JsonPrimitive(record.processName))
                    put("time", JsonPrimitive(record.time))
                    put("marked", JsonPrimitive(record.isMark))
                    put("read", JsonPrimitive(record.isRead))
                    put("summaryTitle", ToolJson.string(record.summaryTitle))
                    put("summarySubtitle", ToolJson.string(record.summarySubtitle))
                    put("summaryMeta", ToolJson.string(record.summaryMeta))
                }
            }
            val payload = ToolJson.page("records", items, page.offset, page.limit, records.total)
            ToolResult.success(payload, ToolJson.text(payload))
        }
}

/** Returns one full record payload. Read-only. */
class GetRecordTool(private val data: McpDataSource) : McpTool {

    override val name: String = "get_record"
    override val title: String = "Get record payload"
    override val description: String =
        "Returns the full JSON payload of one captured record, identified by the id from " +
            "list_records. The payload can contain credentials or user input."

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        integer("id", "Record id returned by list_records.", required = true, minimum = 1)
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("id"))
            val id = arguments.requireInt("id")
            val detail = data.getRecord(id)
                ?: return@withContext ToolResult.failure("No record with id $id.")
            val meta = detail.meta
            val payload = buildJsonObject {
                put("id", JsonPrimitive(meta.id))
                put("type", JsonPrimitive(meta.type))
                put("subType", JsonPrimitive(meta.subType))
                put("packageName", JsonPrimitive(meta.packageName))
                put("processName", JsonPrimitive(meta.processName))
                put("time", JsonPrimitive(meta.time))
                put("marked", JsonPrimitive(meta.isMark))
                put("read", JsonPrimitive(meta.isRead))
                put("summaryTitle", ToolJson.string(meta.summaryTitle))
                put("summarySubtitle", ToolJson.string(meta.summarySubtitle))
                put("sourceKey", ToolJson.string(detail.sourceKey))
                put("recordJson", JsonPrimitive(detail.recordJson))
            }
            ToolResult.success(payload, ToolJson.text(payload))
        }
}

/**
 * Converts a Smali member signature into a hook rule. Read-only: the rule is returned but not
 * stored, so a model can compose a configuration step by step.
 */
class ParseSignatureTool(private val data: McpDataSource) : McpTool {

    override val name: String = "parse_hook_signature"
    override val title: String = "Parse a Smali signature"
    override val description: String =
        "Converts a Smali member signature (method or field) into a hook rule that can be " +
            "used with add_hook_rule or set_hook_config. The rule is only returned, not saved."

    override fun inputSchema(): JsonObject = JsonSchemaBuilder().apply {
        string(
            "signature",
            "Smali member reference, e.g. 'Lcom/example/Foo;->bar(Ljava/lang/String;I)Z' " +
                "for a method, or 'Lcom/example/Foo;->flag:Z' for a field.",
            required = true,
            minLength = 3
        )
    }.build()

    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        withContext(Dispatchers.IO) {
            arguments.rejectUnknown(setOf("signature"))
            val rule = data.parseSignature(arguments.requireString("signature"))
            val payload = buildJsonObject {
                put("rule", HookRuleJson.toJson(listOf(rule)).first())
            }
            ToolResult.success(payload, ToolJson.text(payload))
        }
}
