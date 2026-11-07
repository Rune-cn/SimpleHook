package me.simpleHook.feature.mcp

/**
 * Narrow, Android-free description of everything the MCP tools are allowed to touch.
 *
 * The tools only depend on this interface, which keeps them testable on the JVM with fakes
 * and gives the Android implementation ([AndroidMcpDataSource]) a single place to enforce
 * threading, permission checks and external config synchronisation.
 */
interface McpDataSource {

    data class AppEntry(
        val packageName: String,
        val appName: String,
        val versionName: String,
        val versionCode: Long,
        val system: Boolean
    )

    /** A hook rule as stored inside an `AppConfig`. Mirrors [me.simpleHook.data.HookConfig]. */
    data class HookRule(
        val mode: Int,
        val className: String,
        val methodName: String,
        val params: String,
        val fieldName: String,
        val fieldClassName: String,
        val resultValues: String,
        val hookPoint: String,
        val returnClassName: String,
        val fieldType: String,
        val desc: String,
        val enable: Boolean
    )

    /** Summary of one configured app. */
    data class ConfigSummary(
        val packageName: String,
        val appName: String,
        val versionName: String,
        val enabled: Boolean,
        val ruleCount: Int,
        val enabledRuleCount: Int,
        val extensionConfigured: Boolean,
        val extensionEnabled: Boolean,
        val configId: Int
    )

    /** Full configuration of one app. */
    data class ConfigDetail(
        val packageName: String,
        val appName: String,
        val versionName: String,
        val description: String,
        val enabled: Boolean,
        val configId: Int,
        val rules: List<HookRule>,
        /** Raw `ExtensionConfig` JSON, or `null` when the app has no extension entry. */
        val extensionJson: String?
    )

    data class RecordEntry(
        val id: Int,
        val type: String,
        val subType: String,
        val packageName: String,
        val processName: String,
        val time: String,
        val isMark: Boolean,
        val isRead: Boolean,
        val summaryTitle: String?,
        val summarySubtitle: String?,
        val summaryMeta: String?
    )

    data class RecordDetail(
        val meta: RecordEntry,
        val recordJson: String,
        val sourceKey: String?
    )

    data class RecordPage(
        val records: List<RecordEntry>,
        val offset: Int,
        val limit: Int,
        val total: Int
    ) {
        val hasMore: Boolean get() = offset + records.size < total
        val nextOffset: Int? get() = if (hasMore) offset + records.size else null
    }

    val serverName: String
    val appVersionName: String
    val appVersionCode: Long
    val androidSdk: Int
    val protocolVersion: String
    val httpEndpoint: String?
    val stdioEndpoint: String?
    val allowMutations: Boolean

    fun listApps(query: String?, includeSystem: Boolean, offset: Int, limit: Int): Pair<List<AppEntry>, Int>

    fun listConfigs(query: String?, offset: Int, limit: Int): Pair<List<ConfigSummary>, Int>

    fun getConfig(packageName: String): ConfigDetail?

    /** Replaces the rules of an existing configuration. */
    fun setRules(packageName: String, rules: List<HookRule>, enabled: Boolean?): ConfigDetail

    fun addRule(packageName: String, rule: HookRule): ConfigDetail

    fun deleteRule(packageName: String, index: Int): ConfigDetail

    /** Applies a JSON patch to the extension configuration of an app, creating it if needed. */
    fun patchExtension(packageName: String, patchJson: String): String

    fun setConfigEnabled(packageName: String, enabled: Boolean): ConfigDetail

    fun deleteConfig(packageName: String) : Boolean

    fun queryRecords(
        packageName: String?,
        type: String?,
        keyword: String?,
        markedOnly: Boolean,
        offset: Int,
        limit: Int
    ): RecordPage

    fun getRecord(id: Int): RecordDetail?

    fun deleteRecords(ids: List<Int>): Int

    fun deleteRecordsOfPackage(packageName: String): Int

    /** Converts a Smali member signature into a hook rule. */
    fun parseSignature(signature: String): HookRule

    /** Known record type names, used by tool schemas and validation. */
    fun recordTypeNames(): List<String>
}
