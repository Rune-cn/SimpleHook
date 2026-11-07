package me.simpleHook.feature.mcp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import me.simpleHook.BuildConfig
import me.simpleHook.core.constant.Constant
import me.simpleHook.core.constant.Constant.HOOK_FIELD
import me.simpleHook.core.constant.Constant.HOOK_RECORD_PARAMS
import me.simpleHook.core.utils.AppUtil
import me.simpleHook.core.utils.FlavorUtil
import me.simpleHook.core.utils.SPUtil
import me.simpleHook.core.utils.SmaliSignatureParser
import me.simpleHook.data.HookConfig
import me.simpleHook.data.MemberInfo
import me.simpleHook.data.config.ConfigSystemUtil
import me.simpleHook.data.config.ConfigRemoteSyncHelper
import me.simpleHook.data.local.db.AppDatabase
import me.simpleHook.data.local.db.AppRepository
import me.simpleHook.data.local.db.RecordDatabase
import me.simpleHook.data.local.db.entity.AppConfig
import me.simpleHook.data.local.db.entity.ExtensionConfigEntity
import me.simpleHook.data.local.db.entity.RecordEntity
import me.simpleHook.data.record.RecordType
import me.simpleHook.feature.mcp.protocol.McpProtocolVersion
import me.simpleHook.platform.lsposed.LSPosedHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Android implementation of [McpDataSource].
 *
 * Writes follow the same pipeline as the in-app editor (`AppConfigViewModel`): Room is
 * updated first, the configuration is mirrored to the location the hooked app reads
 * (root / Shizuku / SAF storage) through [ConfigRemoteSyncHelper], and the LSPosed scope is
 * refreshed. Skipping a step would leave a target app running with stale configuration.
 *
 * All methods are blocking; the tools call them from a coroutine on [Dispatchers.IO].
 */
class AndroidMcpDataSource(
    context: Context,
    private val sp: SPUtil = SPUtil(context.applicationContext)
) : McpDataSource {

    private val appContext: Context = context.applicationContext
    private val appRepository by lazy { AppRepository(appContext) }
    private val appConfigDao by lazy { AppDatabase.getDatabase(appContext).getAppConfigDao() }
    private val extensionDao by lazy { AppDatabase.getDatabase(appContext).getExtensionConfigDao() }
    private val recordDao by lazy { RecordDatabase.getDatabase(appContext).recordDao() }
    private val configSystem by lazy { ConfigSystemUtil.getConfigSystem() }

    /**
     * Configuration mirroring runs off the request path; this scope outlives individual
     * requests so a completed MCP response never cancels an in-flight sync.
     */
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val serverName: String = "SimpleHook"

    override val appVersionName: String get() = BuildConfig.VERSION_NAME

    override val appVersionCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    override val androidSdk: Int get() = Build.VERSION.SDK_INT

    override val protocolVersion: String get() = McpProtocolVersion.CURRENT

    override val httpEndpoint: String?
        get() = if (sp.mcpHttpEnabled) "http://127.0.0.1:${sp.mcpHttpPort}/mcp" else null

    override val stdioEndpoint: String?
        get() = if (sp.mcpStdioEnabled) "127.0.0.1:${sp.mcpStdioPort}" else null

    override val allowMutations: Boolean get() = sp.mcpAllowMutations

    // ---------------------------------------------------------------------------------
    // Apps
    // ---------------------------------------------------------------------------------

    override fun listApps(
        query: String?,
        includeSystem: Boolean,
        offset: Int,
        limit: Int
    ): Pair<List<McpDataSource.AppEntry>, Int> {
        val pm = appContext.packageManager
        val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        val entries = pm.getInstalledPackages(0).mapNotNull { info ->
            val appInfo = info.applicationInfo ?: return@mapNotNull null
            val system = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (system && !includeSystem) return@mapNotNull null
            val label = runCatching { appInfo.loadLabel(pm).toString() }
                .getOrDefault(info.packageName)
            if (normalizedQuery != null &&
                !info.packageName.lowercase().contains(normalizedQuery) &&
                !label.lowercase().contains(normalizedQuery)
            ) {
                return@mapNotNull null
            }
            McpDataSource.AppEntry(
                packageName = info.packageName,
                appName = label,
                versionName = info.versionName ?: "",
                versionCode = info.longVersionCode,
                system = system
            )
        }.sortedWith(compareBy({ it.system }, { it.appName.lowercase() }))

        return entries.drop(offset).take(limit) to entries.size
    }

    // ---------------------------------------------------------------------------------
    // Hook configurations
    // ---------------------------------------------------------------------------------

    override fun listConfigs(
        query: String?,
        offset: Int,
        limit: Int
    ): Pair<List<McpDataSource.ConfigSummary>, Int> {
        val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val latestPerPackage = appRepository.getAllConfigList()
            .groupBy { it.packageName }
            .mapNotNull { (_, configs) -> configs.maxByOrNull { it.id } }
            .filter { config ->
                normalizedQuery == null ||
                    config.packageName.lowercase().contains(normalizedQuery) ||
                    config.appName.lowercase().contains(normalizedQuery)
            }
            .sortedBy { it.appName.lowercase() }

        val page = latestPerPackage.drop(offset).take(limit).map { config ->
            val rules = decodeRules(config.configs)
            val extension = appRepository.getExtConfigByPackage(config.packageName)
                ?.takeIf { it.packageName != Constant.MODEL_EXTENSION_CONFIG }
            McpDataSource.ConfigSummary(
                packageName = config.packageName,
                appName = config.appName,
                versionName = config.versionName,
                enabled = config.enable,
                ruleCount = rules.size,
                enabledRuleCount = rules.count { it.enable },
                extensionConfigured = extension != null,
                extensionEnabled = extension?.enable == true,
                configId = config.id
            )
        }
        return page to latestPerPackage.size
    }

    override fun getConfig(packageName: String): McpDataSource.ConfigDetail? {
        val config = appRepository.getLatestConfigByPackage(packageName) ?: return null
        val extension = appRepository.getExtConfigByPackage(packageName)
            ?.takeIf { it.packageName != Constant.MODEL_EXTENSION_CONFIG }
        return McpDataSource.ConfigDetail(
            packageName = config.packageName,
            appName = config.appName,
            versionName = config.versionName,
            description = config.description,
            enabled = config.enable,
            configId = config.id,
            rules = decodeRules(config.configs),
            extensionJson = extension?.config?.takeIf { it.isNotBlank() }
        )
    }

    override fun setRules(
        packageName: String,
        rules: List<McpDataSource.HookRule>,
        enabled: Boolean?
    ): McpDataSource.ConfigDetail {
        val config = requireConfig(packageName)
        persistRules(config, rules, enabled)
        return requireConfigAfterWrite(packageName)
    }

    override fun addRule(
        packageName: String,
        rule: McpDataSource.HookRule
    ): McpDataSource.ConfigDetail {
        val config = requireConfig(packageName)
        persistRules(config, decodeRules(config.configs) + rule, null)
        return requireConfigAfterWrite(packageName)
    }

    override fun deleteRule(packageName: String, index: Int): McpDataSource.ConfigDetail {
        val config = requireConfig(packageName)
        val rules = decodeRules(config.configs).toMutableList()
        if (index !in rules.indices) {
            throw IllegalArgumentException(
                "Rule index $index is out of range; the app has ${rules.size} rule(s)."
            )
        }
        rules.removeAt(index)
        persistRules(config, rules, null)
        return requireConfigAfterWrite(packageName)
    }

    override fun setConfigEnabled(
        packageName: String,
        enabled: Boolean
    ): McpDataSource.ConfigDetail {
        val config = requireConfig(packageName)
        val updated = config.copy(enable = enabled)
        appRepository.updateConfigsSync(updated)
        syncCustom(updated)
        syncScope(packageName)
        return requireConfigAfterWrite(packageName)
    }

    override fun deleteConfig(packageName: String): Boolean {
        val config = appRepository.getLatestConfigByPackage(packageName) ?: return false
        appRepository.deleteConfigsSync(config)
        ConfigRemoteSyncHelper.deleteCustomConfigSync(configSystem, packageName)
        syncScope(packageName)
        return true
    }

    override fun patchExtension(packageName: String, patchJson: String): String {
        val patch = parseJsonObject(patchJson, "extension patch")
        val existing = appRepository.getExtConfigByPackage(packageName)
            ?.takeIf { it.packageName != Constant.MODEL_EXTENSION_CONFIG }
        val appName = existing?.appName ?: AppUtil.getAppName(packageName)
        val base = if (existing != null && existing.config.isNotBlank()) {
            parseJsonObject(existing.config, "stored extension config")
        } else {
            buildJsonObject { }
        }
        val merged = deepMerge(base, patch)

        val entity = ExtensionConfigEntity(
            id = existing?.id ?: 0,
            appName = appName,
            packageName = packageName,
            config = merged.toString(),
            enable = existing?.enable ?: true
        )
        if (existing == null) {
            extensionDao.insertExtConfigsSync(entity)
        } else {
            extensionDao.updateExtConfigsSync(entity)
        }
        syncExtension(entity)
        syncScope(packageName)
        return merged.toString()
    }

    // ---------------------------------------------------------------------------------
    // Records
    // ---------------------------------------------------------------------------------

    override fun queryRecords(
        packageName: String?,
        type: String?,
        keyword: String?,
        markedOnly: Boolean,
        offset: Int,
        limit: Int
    ): McpDataSource.RecordPage {
        val canonicalType = type?.trim()?.takeIf { it.isNotEmpty() }?.let { candidate ->
            RecordType.entries.firstOrNull { it.name.equals(candidate, ignoreCase = true) }
                ?.name
                ?: throw IllegalArgumentException(
                    "Unknown record type '$type'. Known types: " +
                        recordTypeNames().joinToString(", ")
                )
        }
        val keywordPattern = keyword?.trim()?.takeIf { it.isNotEmpty() }?.let { "%$it%" }

        val total = recordDao.countRecords(packageName, canonicalType, markedOnly, keywordPattern)
        val rows = recordDao.queryRecords(
            packageName, canonicalType, markedOnly, keywordPattern, limit, offset
        )
        return McpDataSource.RecordPage(
            records = rows.map { it.toRecordEntry() },
            offset = offset,
            limit = limit,
            total = total
        )
    }

    override fun getRecord(id: Int): McpDataSource.RecordDetail? {
        val entity = recordDao.getRecordById(id) ?: return null
        return McpDataSource.RecordDetail(
            meta = entity.toRecordEntry(),
            recordJson = entity.record,
            sourceKey = entity.sourceKey
        )
    }

    override fun deleteRecords(ids: List<Int>): Int {
        if (ids.isEmpty()) return 0
        // Room binds one argument per id; delete in chunks to stay well below SQLite limits.
        return ids.chunked(500).sumOf { chunk -> recordDao.deleteRecordsByIds(chunk) }
    }

    override fun deleteRecordsOfPackage(packageName: String): Int {
        val total = recordDao.countRecords(packageName, null, false, null)
        recordDao.deleteRecordByPack(packageName)
        return total
    }

    override fun recordTypeNames(): List<String> = RecordType.entries.map { it.name }

    // ---------------------------------------------------------------------------------
    // Signature parsing
    // ---------------------------------------------------------------------------------

    override fun parseSignature(signature: String): McpDataSource.HookRule {
        val member = SmaliSignatureParser.parse(signature)
            ?: throw IllegalArgumentException(
                "Cannot parse signature '$signature'. Expected a Smali member such as " +
                    "Lcom/example/Foo;->bar(Ljava/lang/String;I)Z for a method or " +
                    "Lcom/example/Foo;->flag:Z for a field."
            )
        return when (member) {
            is MemberInfo.MethodInfo -> McpDataSource.HookRule(
                mode = HOOK_RECORD_PARAMS,
                className = member.className,
                methodName = member.methodName,
                params = member.parameters.joinToString(","),
                fieldName = "",
                fieldClassName = "",
                resultValues = "",
                hookPoint = "",
                returnClassName = "",
                fieldType = "",
                desc = "",
                enable = true
            )

            is MemberInfo.FieldInfo -> McpDataSource.HookRule(
                mode = HOOK_FIELD,
                className = "",
                methodName = "",
                params = "",
                fieldName = member.fieldName,
                fieldClassName = member.className,
                resultValues = "",
                hookPoint = "",
                returnClassName = "",
                fieldType = member.fieldType,
                desc = "",
                enable = true
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------

    private fun requireConfig(packageName: String): AppConfig =
        appRepository.getLatestConfigByPackage(packageName)
            ?: throw IllegalArgumentException(
                "No SimpleHook configuration exists for '$packageName'. " +
                    "Create it in the app first; MCP does not create configurations for new apps."
            )

    private fun requireConfigAfterWrite(packageName: String): McpDataSource.ConfigDetail =
        getConfig(packageName)
            ?: throw IllegalStateException("Configuration for '$packageName' disappeared")

    private fun decodeRules(raw: String): List<McpDataSource.HookRule> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<HookConfig>>(raw).map { it.toRule() }
        }.getOrDefault(emptyList())
    }

    private fun persistRules(
        config: AppConfig,
        rules: List<McpDataSource.HookRule>,
        enabled: Boolean?
    ) {
        val encoded = json.encodeToString(rules.map { it.toHookConfig() })
        val updated = config.copy(configs = encoded, enable = enabled ?: config.enable)
        appRepository.updateConfigsSync(updated)
        syncCustom(updated)
        syncScope(config.packageName)
    }

    private fun syncCustom(config: AppConfig) {
        syncScope.launch {
            runCatching {
                val encoded = json.encodeToString(config)
                ConfigRemoteSyncHelper.saveCustomConfigSync(
                    configSystem,
                    config.packageName,
                    encoded
                )
            }
        }
    }

    private fun syncExtension(entity: ExtensionConfigEntity) {
        syncScope.launch {
            runCatching {
                ConfigRemoteSyncHelper.saveExtensionConfigSync(
                    configSystem,
                    entity.packageName,
                    entity.config
                )
            }
        }
    }

    /**
     * Mirrors `AppConfigViewModel.syncScopeForPackages`: a package stays in the LSPosed scope
     * while it has at least one enabled rule or an enabled extension configuration.
     */
    private fun syncScope(packageName: String) {
        if (!FlavorUtil.rootVersion) return
        if (packageName == Constant.MODEL_EXTENSION_CONFIG) return
        syncScope.launch {
            runCatching {
                val custom = appConfigDao.getEnabledCountByPackageName(packageName)
                val extension = extensionDao.getEnabledExtCountByPackageName(packageName)
                if (custom > 0 || extension > 0) {
                    LSPosedHelper.requestScopes(listOf(packageName))
                } else {
                    LSPosedHelper.removeScopes(listOf(packageName))
                }
            }
        }
    }

    private fun RecordEntity.toRecordEntry() = McpDataSource.RecordEntry(
        id = id,
        type = type.name,
        subType = subType,
        packageName = packageName,
        processName = processName,
        time = time,
        isMark = isMark,
        isRead = isRead,
        summaryTitle = summaryTitle,
        summarySubtitle = summarySubtitle,
        summaryMeta = summaryMeta
    )

    private fun HookConfig.toRule() = McpDataSource.HookRule(
        mode = mode,
        className = className,
        methodName = methodName,
        params = params,
        fieldName = fieldName,
        fieldClassName = fieldClassName,
        resultValues = resultValues,
        hookPoint = hookPoint,
        returnClassName = returnClassName,
        fieldType = fieldType,
        desc = desc,
        enable = enable
    )

    private fun McpDataSource.HookRule.toHookConfig() = HookConfig(
        mode = mode,
        className = className,
        methodName = methodName,
        params = params,
        fieldName = fieldName,
        fieldClassName = fieldClassName,
        resultValues = resultValues,
        hookPoint = hookPoint,
        returnClassName = returnClassName,
        fieldType = fieldType,
        desc = desc,
        enable = enable
    )

    private fun parseJsonObject(raw: String, what: String): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse {
                throw IllegalArgumentException("$what is not a JSON object: ${it.message}")
            }

    /** Recursively merges [patch] into [base]; objects merge, every other value replaces. */
    private fun deepMerge(base: JsonObject, patch: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(base)
        patch.forEach { (key, value) ->
            val current = result[key]
            result[key] = if (current is JsonObject && value is JsonObject) {
                deepMerge(current, value)
            } else {
                value
            }
        }
        return JsonObject(result)
    }
}
