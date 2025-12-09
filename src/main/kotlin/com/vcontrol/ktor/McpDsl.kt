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
 * Context for handle { } blocks in the new DSL. Provides typed accessors for parameters.
 *
 * Example:
 * ```kotlin
 * handle {
 *     val name = requireString("name")
 *     val age = int("age") ?: 0
 *     textResult("Hello $name, age $age")
 * }
 * ```
 */
@KtorDsl
public class HandleScope(
    private val args: Map<String, Any?>,
    /** The Ktor ApplicationCall for accessing sessions, principal, etc. */
    public val call: ApplicationCall
) {
    // Nullable accessors
    /** Get a string parameter, or null if missing/wrong type. */
    public fun string(name: String): String? = args[name] as? String

    /** Get an integer parameter, or null if missing/wrong type. */
    public fun int(name: String): Int? = (args[name] as? Number)?.toInt()

    /** Get a long parameter, or null if missing/wrong type. */
    public fun long(name: String): Long? = (args[name] as? Number)?.toLong()

    /** Get a double parameter, or null if missing/wrong type. */
    public fun double(name: String): Double? = (args[name] as? Number)?.toDouble()

    /** Get a boolean parameter, or null if missing/wrong type. */
    public fun boolean(name: String): Boolean? = args[name] as? Boolean

    /** Get a string list parameter, or null if missing/wrong type. */
    @Suppress("UNCHECKED_CAST")
    public fun stringList(name: String): List<String>? = args[name] as? List<String>

    /** Get an object parameter as a Map, or null if missing/wrong type. */
    @Suppress("UNCHECKED_CAST")
    public fun obj(name: String): Map<String, Any?>? = args[name] as? Map<String, Any?>

    // Required accessors (throw if missing)
    /** Get a required string parameter. Throws if missing. */
    public fun requireString(name: String): String =
        string(name) ?: error("Missing required param: $name")

    /** Get a required integer parameter. Throws if missing. */
    public fun requireInt(name: String): Int =
        int(name) ?: error("Missing required param: $name")

    /** Get a required boolean parameter. Throws if missing. */
    public fun requireBoolean(name: String): Boolean =
        boolean(name) ?: error("Missing required param: $name")

    /** Get a required object parameter. Throws if missing. */
    public fun requireObj(name: String): Map<String, Any?> =
        obj(name) ?: error("Missing required param: $name")

    /** Get a required string list parameter. Throws if missing. */
    public fun requireStringList(name: String): List<String> =
        stringList(name) ?: error("Missing required param: $name")
}

/**
 * DSL builder for configuring a tool with schema and handler.
 *
 * Example:
 * ```kotlin
 * tool("greet") {
 *     description = "Greet a user"
 *
 *     schema {
 *         string("name", "User's name", required = true)
 *         int("age", "User's age") { minimum = 0 }
 *     }
 *
 *     handle {
 *         val name = requireString("name")
 *         textResult("Hello, $name!")
 *     }
 * }
 * ```
 */
@KtorDsl
public class ToolBuilder internal constructor(
    private val toolName: String,
    private val mcpCall: ApplicationCall
) {
    /** Tool description shown to MCP clients. */
    public var description: String = ""

    private var schemaBlock: (McpSchemaBuilder.() -> Unit)? = null
    private var handleBlock: (suspend HandleScope.() -> CallToolResult)? = null

    /**
     * Define the tool's input schema.
     */
    public fun schema(block: McpSchemaBuilder.() -> Unit) {
        schemaBlock = block
    }

    /**
     * Define the tool's handler. Runs when the tool is called.
     */
    public fun handle(block: suspend HandleScope.() -> CallToolResult) {
        handleBlock = block
    }

    internal fun build(): BuiltTool {
        val schema = schemaBlock?.let { McpSchemaBuilder().apply(it).build() } ?: ToolSchema()
        val handler = handleBlock ?: error("handle { } block is required")
        return BuiltTool(toolName, description, schema, handler, mcpCall)
    }
}

internal data class BuiltTool(
    val name: String,
    val description: String,
    val schema: ToolSchema,
    val handler: suspend HandleScope.() -> CallToolResult,
    val call: ApplicationCall
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
     * Register a tool using the DSL builder.
     *
     * Example:
     * ```kotlin
     * mcp("/mcp") {
     *     tool("greet") {
     *         description = "Greet a user"
     *
     *         schema {
     *             string("name", "User's name", required = true)
     *             int("age", "User's age") { minimum = 0 }
     *         }
     *
     *         handle {
     *             val name = requireString("name")
     *             val age = int("age") ?: 0
     *             textResult("Hello $name, age $age")
     *         }
     *     }
     * }
     * ```
     */
    public fun tool(name: String, block: ToolBuilder.() -> Unit) {
        val builder = ToolBuilder(name, call)
        builder.block()
        val built = builder.build()

        tools.add(Tool(name = built.name, description = built.description, inputSchema = built.schema))
        toolHandlers[name] = {
            val scope = HandleScope(this.args, built.call)
            built.handler(scope)
        }
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
