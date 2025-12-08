package com.vcontrol.ktor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.CurrentSession
import io.ktor.server.sessions.sessions
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.ktor.utils.io.KtorDsl
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Icon
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

private val logger = KotlinLogging.logger {}

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
 * Context for tool handlers. Provides access to arguments, sessions, and principal.
 */
@KtorDsl
public class ToolScope(
    /** Tool arguments as a map. */
    public val args: Map<String, Any?>,
    /** The Ktor ApplicationCall for accessing sessions, principal, etc. */
    public val call: ApplicationCall
) {
    /** Shortcut for call.sessions */
    public val sessions: CurrentSession get() = call.sessions
}

/**
 * Scope for configuring the MCP ServerSession with access to Ktor call context.
 */
@KtorDsl
public class ConfigureScope(
    /** The underlying MCP ServerSession. Use SDK methods directly. */
    public val server: ServerSession,
    /** The Ktor ApplicationCall for accessing sessions, principal, etc. */
    public val call: ApplicationCall
) {
    /** Shortcut for call.sessions */
    public val sessions: CurrentSession get() = call.sessions
}

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
    /** Shortcut for call.sessions */
    public val sessions: CurrentSession get() = call.sessions
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
     *     val user = sessions.get<UserSession>()
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
     * Ktor sessions available via [ConfigureScope.sessions].
     *
     * Example:
     * ```kotlin
     * mcp("/mcp") {
     *     configure {
     *         val user = sessions.get<UserSession>()
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

/**
 * Registers an MCP endpoint at the given path.
 *
 * This extension works with Ktor's route hierarchy, including inside
 * `authenticate {}` blocks and other route builders.
 *
 * Example:
 * ```kotlin
 * routing {
 *     authenticate("bearer") {
 *         mcp("/api") {
 *             name = "my-server"
 *
 *             tool("hello", "Says hello") {
 *                 CallToolResult(content = listOf(TextContent("Hello!")))
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * @param path Path for the MCP endpoint (SSE and POST handlers)
 * @param configure Configuration block called per-connection
 */
@KtorDsl
public fun Route.mcp(path: String, configure: McpConfig.() -> Unit): Route {
    return route(path) {
        mcp(configure)
    }
}

/**
 * Registers an MCP endpoint at the current route.
 *
 * @see mcp(String, McpConfig.() -> Unit)
 */
@KtorDsl
public fun Route.mcp(configure: McpConfig.() -> Unit): Route {
    val transports = ConcurrentMap<String, SseServerTransport>()

    sse {
        handleSse(configure, transports)
    }

    post {
        handlePost(transports)
    }

    return this
}

private suspend fun ServerSSESession.handleSse(
    configure: McpConfig.() -> Unit,
    transports: ConcurrentMap<String, SseServerTransport>
) {
    val transport = SseServerTransport("", this)
    transports[transport.sessionId] = transport

    val config = McpConfig(call).apply(configure)

    // Auto-set tools capability if tools are registered
    val capabilities = if (config.tools.isNotEmpty() && config.capabilities.tools == null) {
        config.capabilities.copy(tools = ServerCapabilities.Tools())
    } else {
        config.capabilities
    }

    val serverInfo = Implementation(
        name = config.name,
        version = config.version,
        title = config.title,
        websiteUrl = config.websiteUrl,
        icons = config.icons
    )
    val options = ServerOptions(capabilities = capabilities)

    logger.debug { "New MCP connection: server=${config.name}, sessionId=${transport.sessionId}" }

    try {
        val session = ServerSession(
            serverInfo = serverInfo,
            options = options,
            instructions = null
        )

        // Register tool handlers from the DSL
        if (config.tools.isNotEmpty()) {
            session.setRequestHandler<ListToolsRequest>(Method.Defined.ToolsList) { _, _ ->
                ListToolsResult(tools = config.tools, nextCursor = null)
            }

            session.setRequestHandler<CallToolRequest>(Method.Defined.ToolsCall) { request, _ ->
                val toolName = request.params.name
                val handler = config.toolHandlers[toolName]

                if (handler != null) {
                    try {
                        val scope = ToolScope(
                            args = request.params.arguments ?: emptyMap(),
                            call = config.call
                        )
                        handler(scope)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Error executing tool: $toolName" }
                        errorResult("Error: ${e.message}")
                    }
                } else {
                    logger.warn { "Unknown tool: $toolName" }
                    errorResult("Unknown tool: $toolName")
                }
            }
        }

        // Let user configure the session with SDK methods (with call access)
        config.configureBlock?.invoke(ConfigureScope(session, config.call))

        session.onClose {
            logger.debug { "MCP connection closed: sessionId=${transport.sessionId}" }
            transports.remove(transport.sessionId)
        }

        session.connect(transport)
        awaitCancellation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(e) { "MCP session error: sessionId=${transport.sessionId}" }
        transports.remove(transport.sessionId)
        throw e
    }
}

private suspend fun RoutingContext.handlePost(
    transports: ConcurrentMap<String, SseServerTransport>
) {
    val sessionId = call.request.queryParameters["sessionId"]
        ?: run {
            call.respond(HttpStatusCode.BadRequest, "sessionId required")
            return
        }

    val transport = transports[sessionId]
        ?: run {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return
        }

    transport.handlePostMessage(call)
}
