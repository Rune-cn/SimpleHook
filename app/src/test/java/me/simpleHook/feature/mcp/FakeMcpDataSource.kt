package me.simpleHook.feature.mcp

import me.simpleHook.feature.mcp.protocol.JsonSchemaBuilder
import me.simpleHook.feature.mcp.protocol.McpCallContext
import me.simpleHook.feature.mcp.protocol.McpTool
import me.simpleHook.feature.mcp.protocol.ToolArguments
import me.simpleHook.feature.mcp.protocol.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * In-memory [McpDataSource] used by the protocol tests.
 *
 * Only the members that the tools under test exercise need real behaviour; the rest throw so
 * that an accidentally reachable code path fails loudly instead of silently succeeding.
 */
class FakeMcpDataSource(
    override var allowMutations: Boolean = false,
    private val apps: List<McpDataSource.AppEntry> = DEFAULT_APPS,
    private val configs: MutableMap<String, McpDataSource.ConfigDetail> = mutableMapOf(
        "com.example.login" to McpDataSource.ConfigDetail(
            packageName = "com.example.login",
            appName = "Login Demo",
            versionName = "1.0",
            description = "",
            enabled = true,
            configId = 7,
            rules = listOf(
                McpDataSource.HookRule(
                    mode = 5,
                    className = "com.example.Login",
                    methodName = "check",
                    params = "java.lang.String",
                    fieldName = "",
                    fieldClassName = "",
                    resultValues = "",
                    hookPoint = "",
                    returnClassName = "",
                    fieldType = "",
                    desc = "",
                    enable = true
                )
            ),
            extensionJson = """{"all":false,"popupConfig":{"recordDialog":false}}"""
        )
    ),
    private val records: MutableList<McpDataSource.RecordDetail> = mutableListOf(
        McpDataSource.RecordDetail(
            meta = McpDataSource.RecordEntry(
                id = 1,
                type = "RecordParam",
                subType = "param",
                packageName = "com.example.login",
                processName = "com.example.login",
                time = "2026-11-08 00:00:00",
                isMark = false,
                isRead = false,
                summaryTitle = "Login.check",
                summarySubtitle = "com.example.Login#check(java.lang.String)",
                summaryMeta = "param"
            ),
            recordJson = """{"type":"RecordParam","className":"com.example.Login"}""",
            sourceKey = "k1"
        )
    )
) : McpDataSource {

    var lastExtensionPatch: String? = null
        private set

    override val serverName: String = "SimpleHook"
    override val appVersionName: String = "test"
    override val appVersionCode: Long = 1L
    override val androidSdk: Int = 34
    override val protocolVersion: String = "2026-07-28"
    override val httpEndpoint: String? = "http://127.0.0.1:57321/mcp"
    override val stdioEndpoint: String? = null

    override fun listApps(
        query: String?,
        includeSystem: Boolean,
        offset: Int,
        limit: Int
    ): Pair<List<McpDataSource.AppEntry>, Int> {
        val filtered = apps.filter { app ->
            (includeSystem || !app.system) &&
                (query == null || app.packageName.contains(query, true) ||
                    app.appName.contains(query, true))
        }
        return filtered.drop(offset).take(limit) to filtered.size
    }

    override fun listConfigs(
        query: String?,
        offset: Int,
        limit: Int
    ): Pair<List<McpDataSource.ConfigSummary>, Int> {
        val summaries = configs.values.map { detail ->
            McpDataSource.ConfigSummary(
                packageName = detail.packageName,
                appName = detail.appName,
                versionName = detail.versionName,
                enabled = detail.enabled,
                ruleCount = detail.rules.size,
                enabledRuleCount = detail.rules.count { it.enable },
                extensionConfigured = detail.extensionJson != null,
                extensionEnabled = detail.extensionJson != null,
                configId = detail.configId
            )
        }.filter { query == null || it.packageName.contains(query, true) }
        return summaries.drop(offset).take(limit) to summaries.size
    }

    override fun getConfig(packageName: String): McpDataSource.ConfigDetail? = configs[packageName]

    override fun setRules(
        packageName: String,
        rules: List<McpDataSource.HookRule>,
        enabled: Boolean?
    ): McpDataSource.ConfigDetail {
        val current = configs[packageName] ?: error("missing config")
        val updated = current.copy(rules = rules, enabled = enabled ?: current.enabled)
        configs[packageName] = updated
        return updated
    }

    override fun addRule(
        packageName: String,
        rule: McpDataSource.HookRule
    ): McpDataSource.ConfigDetail {
        val current = configs[packageName] ?: error("missing config")
        val updated = current.copy(rules = current.rules + rule)
        configs[packageName] = updated
        return updated
    }

    override fun deleteRule(packageName: String, index: Int): McpDataSource.ConfigDetail {
        val current = configs[packageName] ?: error("missing config")
        val rules = current.rules.toMutableList()
        if (index !in rules.indices) throw IllegalArgumentException("index out of range")
        rules.removeAt(index)
        val updated = current.copy(rules = rules)
        configs[packageName] = updated
        return updated
    }

    override fun patchExtension(packageName: String, patchJson: String): String {
        lastExtensionPatch = patchJson
        return """{"all":true,"patch":$patchJson}"""
    }

    override fun setConfigEnabled(
        packageName: String,
        enabled: Boolean
    ): McpDataSource.ConfigDetail {
        val current = configs[packageName] ?: error("missing config")
        val updated = current.copy(enabled = enabled)
        configs[packageName] = updated
        return updated
    }

    override fun deleteConfig(packageName: String): Boolean = configs.remove(packageName) != null

    override fun queryRecords(
        packageName: String?,
        type: String?,
        keyword: String?,
        markedOnly: Boolean,
        offset: Int,
        limit: Int
    ): McpDataSource.RecordPage {
        val filtered = records.filter { record ->
            (packageName == null || record.meta.packageName == packageName) &&
                (type == null || record.meta.type.equals(type, true)) &&
                (!markedOnly || record.meta.isMark) &&
                (keyword == null || record.recordJson.contains(keyword))
        }
        return McpDataSource.RecordPage(
            records = filtered.drop(offset).take(limit).map { it.meta },
            offset = offset,
            limit = limit,
            total = filtered.size
        )
    }

    override fun getRecord(id: Int): McpDataSource.RecordDetail? =
        records.firstOrNull { it.meta.id == id }

    override fun deleteRecords(ids: List<Int>): Int {
        val before = records.size
        records.removeAll { it.meta.id in ids }
        return before - records.size
    }

    override fun deleteRecordsOfPackage(packageName: String): Int {
        val before = records.size
        records.removeAll { it.meta.packageName == packageName }
        return before - records.size
    }

    override fun parseSignature(signature: String): McpDataSource.HookRule =
        McpDataSource.HookRule(
            mode = 5,
            className = "com.example.Parsed",
            methodName = "m",
            params = "int",
            fieldName = "",
            fieldClassName = "",
            resultValues = "",
            hookPoint = "",
            returnClassName = "",
            fieldType = "",
            desc = "",
            enable = true
        )

    override fun recordTypeNames(): List<String> = listOf("RecordParam", "RecordReturn")

    companion object {
        val DEFAULT_APPS = listOf(
            McpDataSource.AppEntry("com.example.login", "Login Demo", "1.0", 1, false),
            McpDataSource.AppEntry("com.android.settings", "Settings", "14", 2, true)
        )
    }
}

/** A trivial tool used to test registry behaviour (ordering, gating, failures). */
class StubTool(
    override val name: String,
    override val title: String = name,
    override val description: String = "stub",
    override val mutating: Boolean = false,
    private val body: suspend (ToolArguments) -> ToolResult = { ToolResult.success(echo()) }
) : McpTool {
    override fun inputSchema(): JsonObject = JsonSchemaBuilder.empty()
    override suspend fun call(arguments: ToolArguments, ctx: McpCallContext): ToolResult =
        body(arguments)

    companion object {
        fun echo(): kotlinx.serialization.json.JsonElement =
            buildJsonObject { put("ok", JsonPrimitive(true)) }
    }
}
