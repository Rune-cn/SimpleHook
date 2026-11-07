package me.simpleHook.feature.mcp.protocol

/**
 * MCP protocol revisions understood by this server.
 *
 * The server implements revision [CURRENT] only. According to the specification
 * (`docs/specification/2026-07-28/basic/versioning.mdx`) there is no `initialize`
 * handshake: every request declares the version it speaks in
 * `params._meta["io.modelcontextprotocol/protocolVersion"]`, and a server that does not
 * implement the requested version must answer with an
 * [McpErrorCodes.UNSUPPORTED_PROTOCOL_VERSION] error listing the versions it supports.
 *
 * [LEGACY_KNOWN] is reported in that list so that a client can tell "this server speaks
 * MCP, but an older revision" apart from "this is not an MCP endpoint at all". Requests
 * carrying one of those versions are still rejected: this server does not implement the
 * legacy `initialize` semantics.
 */
object McpProtocolVersion {
    /** Revision implemented by this server. */
    const val CURRENT: String = "2026-07-28"

    /** Revisions this server can serve. */
    val SUPPORTED: List<String> = listOf(CURRENT)

    /**
     * Older revisions a client may legitimately request. They are reported as known so a
     * client can surface an actionable message, but they are never served.
     */
    val LEGACY_KNOWN: List<String> = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    /** All versions advertised by `server/discover`. */
    val ADVERTISED: List<String> = SUPPORTED + LEGACY_KNOWN

    fun isSupported(version: String?): Boolean = version != null && version in SUPPORTED

    /** Syntax check before a client-supplied value is echoed back in a structured error. */
    fun looksLikeVersion(version: String): Boolean =
        Regex("""^\d{4}-\d{2}-\d{2}$""").matches(version)
}

/** Reserved `_meta` keys defined by the 2026-07-28 revision. */
object McpMetaKeys {
    const val PREFIX: String = "io.modelcontextprotocol/"

    const val PROTOCOL_VERSION: String = PREFIX + "protocolVersion"
    const val CLIENT_INFO: String = PREFIX + "clientInfo"
    const val CLIENT_CAPABILITIES: String = PREFIX + "clientCapabilities"
    const val SERVER_INFO: String = PREFIX + "serverInfo"
    const val LOG_LEVEL: String = PREFIX + "logLevel"
    const val SUBSCRIPTION_ID: String = PREFIX + "subscriptionId"
    const val PROGRESS_TOKEN: String = "progressToken"
}

/** HTTP header names used by the Streamable HTTP transport. */
object McpHttpHeaders {
    const val PROTOCOL_VERSION: String = "MCP-Protocol-Version"
    const val METHOD: String = "Mcp-Method"
    const val NAME: String = "Mcp-Name"
    const val SESSION_ID: String = "Mcp-Session-Id"
    const val LAST_EVENT_ID: String = "Last-Event-ID"
    const val AUTHORIZATION: String = "Authorization"
    const val ORIGIN: String = "Origin"
    const val ACCEPT: String = "Accept"
    const val CONTENT_TYPE: String = "Content-Type"

    /**
     * Base64 sentinel used for header values that cannot be represented as plain ASCII,
     * as described in `basic/transports/streamable-http.mdx` section Value Encoding.
     */
    const val BASE64_PREFIX: String = "=?base64?"
    const val BASE64_SUFFIX: String = "?="
}
