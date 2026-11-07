package me.simpleHook.feature.mcp.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-request protocol metadata carried in `params._meta`.
 *
 * Revision 2026-07-28 removed the `initialize` handshake, so every request must be
 * self-describing (`docs/specification/2026-07-28/basic/index.mdx`, section `_meta`
 * "Per-request protocol fields"):
 *
 * | key                                             | required |
 * | ----------------------------------------------- | -------- |
 * | `io.modelcontextprotocol/protocolVersion`       | yes      |
 * | `io.modelcontextprotocol/clientCapabilities`    | yes      |
 * | `io.modelcontextprotocol/clientInfo`            | no       |
 * | `io.modelcontextprotocol/logLevel`              | no       |
 *
 * A request missing a required field is malformed and must be rejected with `-32602`.
 */
data class McpRequestMeta(
    val protocolVersion: String,
    val clientInfo: Implementation?,
    val clientCapabilities: JsonObject,
    val logLevel: String?
) {
    data class Implementation(val name: String, val version: String?)

    companion object {
        /**
         * Parses `_meta`. Returns the parsed metadata, or the JSON-RPC error that must be
         * returned to the caller. Version support is *not* checked here so that
         * `server/discover`, which is how a client learns the supported versions, can still
         * answer with an [`McpErrors.unsupportedProtocolVersion`] error.
         */
        fun parse(params: JsonObject): McpMetaResult {
            val meta = params["_meta"] as? JsonObject ?: return McpMetaResult.Failure(
                McpErrors.malformedMeta(
                    "Missing required params._meta with ${McpMetaKeys.PROTOCOL_VERSION}"
                )
            )

            val versionPrimitive = meta[McpMetaKeys.PROTOCOL_VERSION] as? JsonPrimitive
            if (versionPrimitive == null || !versionPrimitive.isString) {
                return McpMetaResult.Failure(
                    McpErrors.malformedMeta(
                        "Missing required string field _meta.${McpMetaKeys.PROTOCOL_VERSION}"
                    )
                )
            }
            val version = versionPrimitive.content
            if (!McpProtocolVersion.looksLikeVersion(version)) {
                return McpMetaResult.Failure(
                    McpErrors.malformedMeta(
                        "Malformed _meta.${McpMetaKeys.PROTOCOL_VERSION}: $version"
                    )
                )
            }

            val capabilities = meta[McpMetaKeys.CLIENT_CAPABILITIES]
            if (capabilities !is JsonObject) {
                return McpMetaResult.Failure(
                    McpErrors.malformedMeta(
                        "Missing required object field _meta.${McpMetaKeys.CLIENT_CAPABILITIES}"
                    )
                )
            }

            val clientInfo = (meta[McpMetaKeys.CLIENT_INFO] as? JsonObject)?.let { info ->
                val name = (info["name"] as? JsonPrimitive)?.content ?: return@let null
                Implementation(name, (info["version"] as? JsonPrimitive)?.content)
            }

            val logLevel = (meta[McpMetaKeys.LOG_LEVEL] as? JsonPrimitive)?.content

            return McpMetaResult.Success(
                McpRequestMeta(
                    protocolVersion = version,
                    clientInfo = clientInfo,
                    clientCapabilities = capabilities,
                    logLevel = logLevel
                )
            )
        }

    }
}

/**
 * Outcome of parsing `params._meta`.
 *
 * Declared at file scope, not inside the companion object: a nested `Result` shadows
 * `kotlin.Result` inside the companion, which silently changes the meaning of ordinary
 * `Result`-returning expressions in this file.
 */
sealed interface McpMetaResult {
    data class Success(val meta: McpRequestMeta) : McpMetaResult
    data class Failure(val error: JsonRpcError) : McpMetaResult
}
