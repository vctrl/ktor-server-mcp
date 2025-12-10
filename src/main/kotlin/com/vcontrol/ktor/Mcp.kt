package com.vcontrol.ktor

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.ktor.utils.io.KtorDsl
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

private val logger = KotlinLogging.logger {}

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
 *                 textResult("Hello!")
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
                            args = request.params.arguments ?: JsonObject(emptyMap()),
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
