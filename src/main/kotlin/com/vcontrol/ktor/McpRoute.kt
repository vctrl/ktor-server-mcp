package com.vcontrol.ktor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.CurrentSession
import io.ktor.server.sessions.sessions
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.ktor.utils.io.KtorDsl
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Icon
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

private val logger = KotlinLogging.logger {}

/**
 * Configuration for an MCP endpoint.
 *
 * The configuration block is invoked when each SSE connection is established,
 * providing access to [sessions] for capturing user-specific data.
 */
@KtorDsl
public class McpConfig internal constructor(
    /**
     * Ktor session accessor. Use to retrieve session data at connection time.
     */
    public val sessions: CurrentSession
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

    /** Server capabilities. Configure based on what features you register. */
    public var capabilities: ServerCapabilities = ServerCapabilities()

    internal var configureBlock: (suspend ServerSession.() -> Unit)? = null

    /**
     * Configure the MCP ServerSession directly using SDK methods.
     *
     * Provides full access to SDK features: tools, prompts, resources, etc.
     *
     * Example:
     * ```kotlin
     * mcp("/mcp") {
     *     capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())
     *
     *     configure {
     *         addTool("hello", "Says hello", ToolSchema()) { request ->
     *             CallToolResult(content = listOf(TextContent("Hello!")))
     *         }
     *     }
     * }
     * ```
     */
    public fun configure(block: suspend ServerSession.() -> Unit) {
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

    val config = McpConfig(call.sessions).apply(configure)

    val serverInfo = Implementation(
        name = config.name,
        version = config.version,
        title = config.title,
        websiteUrl = config.websiteUrl,
        icons = config.icons
    )
    val options = ServerOptions(capabilities = config.capabilities)

    logger.debug { "New MCP connection: server=${config.name}, sessionId=${transport.sessionId}" }

    try {
        val session = ServerSession(
            serverInfo = serverInfo,
            options = options,
            instructions = null
        )

        // Let user configure the session with SDK methods
        config.configureBlock?.invoke(session)

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
