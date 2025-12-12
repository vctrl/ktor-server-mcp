package com.vcontrol.ktor

import com.vcontrol.ktor.sessions.BearerSessionAccess
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.ApplicationResponse
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.util.Attributes
import io.ktor.util.reflect.TypeInfo
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext

/**
 * MCP-aware sessions with async write support.
 *
 * Unlike Ktor's standard session system which defers writes to `BeforeSend`
 * (breaking in SSE contexts), this writes directly to storage using
 * [BearerSessionAccess] from ktor-bearer-sessions.
 *
 * Note: This does NOT implement [io.ktor.server.sessions.CurrentSession] because
 * that interface requires sync `set()`. Instead, we provide async-native methods.
 *
 * @param sseCall The underlying SSE connection's ApplicationCall
 */
public class ToolingSessions @PublishedApi internal constructor(
    @PublishedApi internal val sseCall: ApplicationCall
) {

    /**
     * Get session by type. Reads from already-loaded Ktor session data.
     */
    public inline fun <reified T : Any> get(): T? = sseCall.sessions.get<T>()

    /**
     * Set session by type. Writes directly to storage via BearerSessionAccess.
     *
     * This is a suspend function - no runBlocking needed.
     * Uses ktor-bearer-sessions for direct storage writes in streaming contexts.
     *
     * @throws IllegalStateException if BearerSessionRegistry not installed
     */
    public suspend inline fun <reified T : Any> set(value: T) {
        BearerSessionAccess.set(sseCall, value)
    }

    /**
     * Clear session by name. Delegates to Ktor's session machinery.
     */
    public fun clear(name: String) {
        sseCall.sessions.clear(name)
    }
}

/**
 * An MCP tool invocation context that implements [ApplicationCall].
 *
 * Provides Ktor-idiomatic API for MCP tool handlers:
 * - `call.sessions.get/set` - MCP-aware sessions that work in SSE context
 * - `call.respond(result)` - sets the tool's [CallToolResult]
 * - `call.principal<T>()` - identity from SSE connection (via attributes delegation)
 * - `call.application` - access to application config
 *
 * This is NOT an HTTP request - `request` and `response` properties will throw
 * if accessed, as they don't apply to MCP tool invocations.
 *
 * Session writes use [BearerSessionAccess] from ktor-bearer-sessions, which
 * writes directly to storage (bypassing Ktor's deferred BeforeSend pattern
 * that fails in SSE/streaming contexts).
 *
 * Usage:
 * ```kotlin
 * tool("update_contact", "Update contact info") {
 *     handle {
 *         val session = call.sessions.get<ContactSession>()
 *         session.phone = requireString("value")
 *         call.sessions.set(session)
 *         call.respondText("Updated")
 *     }
 * }
 * ```
 *
 * @param sseCall The underlying SSE connection's ApplicationCall
 * @param args Tool arguments from the MCP protocol
 */
public class ToolingCall internal constructor(
    private val sseCall: ApplicationCall,
    public val args: JsonObject
) : ApplicationCall {

    // CoroutineScope delegation
    override val coroutineContext: CoroutineContext get() = sseCall.coroutineContext

    // Result accumulator for respond()
    private var _result: CallToolResult? = null

    /**
     * The tool's result after the handler completes.
     * Defaults to a "No response" text result if respond() was never called.
     */
    public val result: CallToolResult get() = _result ?: textResult("No response")

    /**
     * MCP-aware sessions with async write support.
     * Unlike Ktor's standard sessions, `set()` works in SSE contexts.
     */
    public val sessions: ToolingSessions by lazy {
        ToolingSessions(sseCall)
    }

    // Delegate identity/application to SSE call
    override val application: Application get() = sseCall.application
    override val attributes: Attributes get() = sseCall.attributes

    /**
     * Set the tool's result to a [CallToolResult].
     */
    public suspend fun respond(result: CallToolResult) {
        _result = result
    }

    /**
     * Set the tool's result to a text response.
     */
    public suspend fun respondText(text: String) {
        respond(textResult(text))
    }

    /**
     * Set the tool's result to an error response.
     */
    public suspend fun respondError(message: String) {
        respond(errorResult(message))
    }

    // ApplicationCall interface - HTTP-specific members that don't apply to MCP

    override val request: ApplicationRequest
        get() = error("MCP tools don't have HTTP requests. Use 'args' for tool arguments.")

    override val response: ApplicationResponse
        get() = error("MCP tools don't have HTTP responses. Use 'respond()' to set the result.")

    override val parameters: Parameters get() = Parameters.Empty

    override suspend fun <T> receiveNullable(typeInfo: TypeInfo): T? =
        error("MCP tools don't receive HTTP bodies. Use 'args' for tool arguments.")

    override suspend fun respond(message: Any?, typeInfo: TypeInfo?) {
        // Support Ktor's respond pattern by converting to CallToolResult
        when (message) {
            is CallToolResult -> _result = message
            is String -> _result = textResult(message)
            null -> _result = textResult("")
            else -> _result = textResult(message.toString())
        }
    }
}
