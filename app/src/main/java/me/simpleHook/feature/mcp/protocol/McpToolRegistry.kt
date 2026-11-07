package me.simpleHook.feature.mcp.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Holds the registered tools and dispatches `tools/call` requests.
 *
 * The specification requires the tool list to be deterministic so clients can cache it and
 * prompt caches keep hitting (`docs/specification/2026-07-28/server/tools.mdx`, section
 * Capabilities), therefore tools are always returned sorted by name.
 */
class McpToolRegistry(
    tools: Collection<McpTool>,
    /**
     * When true, unexpected exception messages are copied into the tool result.
     *
     * Off by default: exception messages routinely contain file paths, URLs with embedded
     * credentials and fragments of the data being processed, and tool results are handed to
     * a model that may echo them back to the user.
     */
    private val includeFailureDetails: Boolean = false
) {

    private val byName: Map<String, McpTool>

    init {
        val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool names: ${duplicates.joinToString()}" }
        tools.forEach { tool ->
            require(TOOL_NAME.matches(tool.name)) {
                "Invalid tool name '${tool.name}'; expected ${TOOL_NAME.pattern}"
            }
        }
        byName = tools.sortedBy { it.name }.associateBy { it.name }
    }

    val names: List<String> get() = byName.keys.toList()

    fun find(name: String): McpTool? = byName[name]

    /** Tools visible for the given policy, sorted by name. */
    fun tools(allowMutations: Boolean): List<McpTool> =
        byName.values.filter { allowMutations || !it.mutating }

    fun listResult(allowMutations: Boolean, nextCursor: String? = null): JsonObject =
        buildJsonObject {
            put("resultType", McpResultType.COMPLETE)
            put(
                "tools",
                buildJsonArray { tools(allowMutations).forEach { add(it.definition()) } }
            )
            if (nextCursor != null) put("nextCursor", nextCursor)
        }

    /**
     * Dispatches a call. Returns either a result or a protocol error for the request itself
     * (unknown tool / malformed call), matching `server/tools.mdx` section Error Handling.
     */
    suspend fun call(
        name: String,
        arguments: JsonObject,
        ctx: McpCallContext,
        allowMutations: Boolean
    ): Outcome {
        val tool = byName[name]
            ?: return Outcome.ProtocolError(McpErrors.unknownTool(name))

        if (tool.mutating && !allowMutations) {
            return Outcome.ProtocolError(
                McpErrors.invalidToolCall(
                    "Tool '$name' is disabled: mutating tools are turned off in SimpleHook " +
                        "settings (MCP -> allow configuration changes)."
                )
            )
        }

        return try {
            Outcome.Result(tool.call(ToolArguments(arguments), ctx))
        } catch (argument: ToolArgumentException) {
            Outcome.Result(ToolResult.failure(argument.message ?: "Invalid arguments"))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            // Tool execution errors are reported inside the result so the model can react.
            // The message is generic on purpose: exception messages can carry paths and
            // secrets, and the model may repeat them. The exception type is enough to tell
            // "bad input" from "device problem".
            val detail = if (includeFailureDetails) {
                ": " + (throwable.message ?: "no details")
            } else {
                ""
            }
            Outcome.Result(
                ToolResult.failure(
                    "${tool.name} failed (${throwable.javaClass.simpleName})$detail"
                )
            )
        }
    }

    sealed interface Outcome {
        data class Result(val result: ToolResult) : Outcome
        data class ProtocolError(val error: JsonRpcError) : Outcome
    }

    private companion object {
        /** `server/tools.mdx` section Tool Names. */
        val TOOL_NAME = Regex("""^[A-Za-z0-9_.-]{1,128}$""")
    }
}
