package com.vcontrol.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.utils.io.KtorDsl
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Icon
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema

/**
 * Create a CallToolResult with a single text content.
 */
public fun textResult(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = text)))

/**
 * Create a CallToolResult with multiple text contents.
 */
public fun textResult(vararg texts: String): CallToolResult =
    CallToolResult(content = texts.map { TextContent(text = it) })

/**
 * Create an error CallToolResult.
 */
public fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = message)), isError = true)

/**
 * Context for tool handlers. Provides access to arguments and the Ktor call.
 */
@KtorDsl
public class ToolScope(
    /** Tool arguments as a map. */
    public val args: Map<String, Any?>,
    /** The Ktor ApplicationCall for accessing sessions, principal, etc. */
    public val call: ApplicationCall
)

/**
 * Scope for configuring the MCP ServerSession with access to Ktor call context.
 */
@KtorDsl
public class ConfigureScope(
    /** The underlying MCP ServerSession. Use SDK methods directly. */
    public val server: ServerSession,
    /** The Ktor ApplicationCall for accessing sessions, principal, etc. */
    public val call: ApplicationCall
)

/**
 * Configuration for an MCP endpoint.
 *
 * The configuration block is invoked when each SSE connection is established,
 * providing access to [call] for sessions, principal, and other request context.
 */
@KtorDsl
public class McpConfig internal constructor(
    /**
     * The Ktor ApplicationCall for accessing sessions, principal, etc.
     */
    public val call: ApplicationCall
) {
    /** Server name shown to MCP clients. */
    public var name: String = "mcp-server"

    /** Server version. */
    public var version: String = "1.0.0"

    /** Human-readable title for the server. */
    public var title: String? = null

    /** URL to the server's website. */
    public var websiteUrl: String? = null

    /** Icons representing the server. */
    public var icons: List<Icon>? = null

    /** Server capabilities. Set automatically when using tool(), or configure manually. */
    public var capabilities: ServerCapabilities = ServerCapabilities()

    internal val tools = mutableListOf<Tool>()
    internal val toolHandlers = mutableMapOf<String, suspend ToolScope.() -> CallToolResult>()
    internal var configureBlock: (suspend ConfigureScope.() -> Unit)? = null

    /**
     * Register a tool with a handler.
     *
     * Use [textResult] helper for simple text responses.
     * Exceptions are caught and returned as error results.
     *
     * Example:
     * ```kotlin
     * mcp("/mcp") {
     *     val user = call.sessions.get<UserSession>()
     *
     *     tool("whoami", "Returns current user") {
     *         textResult("Hello, ${user?.name ?: "stranger"}!")
     *     }
     *
     *     tool("multi", "Returns multiple items") {
     *         textResult("First", "Second", "Third")
     *     }
     * }
     * ```
     */
    public fun tool(
        name: String,
        description: String,
        inputSchema: ToolSchema = ToolSchema(),
        handler: suspend ToolScope.() -> CallToolResult
    ) {
        tools.add(Tool(name = name, description = description, inputSchema = inputSchema))
        toolHandlers[name] = handler
    }

    /**
     * Configure the MCP ServerSession directly using SDK methods.
     *
     * Provides full access to SDK features via [ConfigureScope.server].
     * Ktor call available via [ConfigureScope.call].
     *
     * Example:
     * ```kotlin
     * mcp("/mcp") {
     *     configure {
     *         val user = call.sessions.get<UserSession>()
     *
     *         server.addTool("advanced", "Advanced tool", ToolSchema()) { request ->
     *             CallToolResult(content = listOf(TextContent("Result")))
     *         }
     *     }
     * }
     * ```
     */
    public fun configure(block: suspend ConfigureScope.() -> Unit) {
        configureBlock = block
    }
}
