package com.vcontrol.ktor

import io.ktor.server.application.ApplicationCall
import io.ktor.utils.io.KtorDsl
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Icon
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Create a CallToolResult with a single text content.
 * For use outside of tool handlers or for building custom results.
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
 * Context for handle { } blocks. Provides typed parameter accessors and Ktor-like call access.
 *
 * Use `call.sessions` for session access and `call.respond()` / `call.respondText()` for responses.
 *
 * Example:
 * ```kotlin
 * handle {
 *     val name = requireString("name")
 *     val session = call.sessions.get<MySession>()
 *     call.sessions.set(session.copy(name = name))  // Works in SSE!
 *     call.respondText("Hello $name")
 * }
 * ```
 */
@KtorDsl
public class HandleScope(
    /** The MCP tool call context with sessions, respond(), etc. */
    public val call: ToolingCall
) {
    private val args: JsonObject get() = call.args

    private fun primitive(name: String): JsonPrimitive? = args[name] as? JsonPrimitive

    // Nullable accessors
    /** Get a string parameter, or null if missing/wrong type. */
    public fun string(name: String): String? = primitive(name)?.contentOrNull

    /** Get an integer parameter, or null if missing/wrong type. */
    public fun int(name: String): Int? = primitive(name)?.intOrNull

    /** Get a long parameter, or null if missing/wrong type. */
    public fun long(name: String): Long? = primitive(name)?.longOrNull

    /** Get a double parameter, or null if missing/wrong type. */
    public fun double(name: String): Double? = primitive(name)?.doubleOrNull

    /** Get a boolean parameter, or null if missing/wrong type. */
    public fun boolean(name: String): Boolean? = primitive(name)?.booleanOrNull

    /** Get a string list parameter, or null if missing/wrong type. */
    public fun stringList(name: String): List<String>? =
        (args[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    /** Get an object parameter as a JsonObject, or null if missing/wrong type. */
    public fun obj(name: String): JsonObject? = args[name] as? JsonObject

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
    public fun requireObj(name: String): JsonObject =
        obj(name) ?: error("Missing required param: $name")

    /** Get a required string list parameter. Throws if missing. */
    public fun requireStringList(name: String): List<String> =
        stringList(name) ?: error("Missing required param: $name")
}

/**
 * DSL builder for tool annotations (behavioral hints for LLMs).
 *
 * All properties are **hints** - not guaranteed to provide faithful description of tool behavior.
 *
 * Example:
 * ```kotlin
 * annotations {
 *     readOnlyHint = false      // Tool modifies state
 *     destructiveHint = false   // But only additive changes
 *     idempotentHint = true     // Safe to retry
 *     openWorldHint = false     // Closed domain (e.g., internal data)
 * }
 * ```
 */
@KtorDsl
public class ToolAnnotationsBuilder internal constructor() {
    /**
     * Human-readable title for the tool (takes precedence over Tool.title and Tool.name for display).
     */
    public var title: String? = null

    /**
     * If true, the tool does not modify its environment.
     * Default: false (assumes tool may modify)
     */
    public var readOnlyHint: Boolean? = null

    /**
     * If true, the tool may perform destructive updates.
     * Only meaningful when readOnlyHint = false.
     * Default: true (assumes destructive if not read-only)
     */
    public var destructiveHint: Boolean? = null

    /**
     * If true, calling repeatedly with same arguments has no additional effect.
     * Only meaningful when readOnlyHint = false.
     * Default: false
     */
    public var idempotentHint: Boolean? = null

    /**
     * If true, tool may interact with external entities (open world).
     * If false, tool's domain is closed (e.g., internal memory/data).
     * Default: true
     */
    public var openWorldHint: Boolean? = null

    internal fun build(): ToolAnnotations? {
        // Return null if no annotations set (avoids empty object in JSON)
        if (title == null && readOnlyHint == null && destructiveHint == null &&
            idempotentHint == null && openWorldHint == null
        ) {
            return null
        }
        return ToolAnnotations(
            title = title,
            readOnlyHint = readOnlyHint,
            destructiveHint = destructiveHint,
            idempotentHint = idempotentHint,
            openWorldHint = openWorldHint
        )
    }
}

/**
 * DSL builder for configuring a tool with schema, annotations, and handler.
 *
 * Example:
 * ```kotlin
 * tool("update_contact") {
 *     title = "Update Contact"
 *     description = "Update a contact field"
 *
 *     annotations {
 *         readOnlyHint = false
 *         destructiveHint = false
 *         idempotentHint = true
 *     }
 *
 *     schema {
 *         string("field", "Field name to update", required = true)
 *         string("value", "New value", required = true)
 *     }
 *
 *     handle {
 *         val field = requireString("field")
 *         val value = requireString("value")
 *         // ... update logic
 *         call.respondText("Updated $field to $value")
 *     }
 * }
 * ```
 */
@KtorDsl
public class ToolBuilder internal constructor(
    private val toolName: String
) {
    /**
     * Human-readable display name for this tool.
     * Note: [ToolAnnotations.title] takes precedence if set via [annotations] block.
     */
    public var title: String? = null

    /** Tool description shown to MCP clients. */
    public var description: String = ""

    /** Icons representing this tool. Clients must support PNG and JPEG; should support SVG and WebP. */
    public var icons: List<Icon>? = null

    /** Arbitrary metadata for vendor-specific extensions. */
    public var meta: JsonObject? = null

    private var inputSchemaBlock: (McpSchemaBuilder.() -> Unit)? = null
    private var outputSchemaBlock: (McpSchemaBuilder.() -> Unit)? = null
    private var annotationsBlock: (ToolAnnotationsBuilder.() -> Unit)? = null
    private var handleBlock: (suspend HandleScope.() -> Unit)? = null

    /**
     * Define the tool's input schema.
     */
    public fun schema(block: McpSchemaBuilder.() -> Unit) {
        inputSchemaBlock = block
    }

    /**
     * Define the tool's output schema (optional).
     * Defines structure of [CallToolResult.structuredContent].
     */
    public fun outputSchema(block: McpSchemaBuilder.() -> Unit) {
        outputSchemaBlock = block
    }

    /**
     * Define behavioral hints for LLMs about this tool.
     */
    public fun annotations(block: ToolAnnotationsBuilder.() -> Unit) {
        annotationsBlock = block
    }

    /**
     * Define the tool's handler. Use `call.respond()` or `call.respondText()` to set the result.
     */
    public fun handle(block: suspend HandleScope.() -> Unit) {
        handleBlock = block
    }

    internal fun build(): BuiltTool {
        val inputSchema = inputSchemaBlock?.let { McpSchemaBuilder().apply(it).build() } ?: ToolSchema()
        val outputSchema = outputSchemaBlock?.let { McpSchemaBuilder().apply(it).build() }
        val annotations = annotationsBlock?.let { ToolAnnotationsBuilder().apply(it).build() }
        val handler = handleBlock ?: error("handle { } block is required")
        return BuiltTool(
            name = toolName,
            title = title,
            description = description,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            annotations = annotations,
            icons = icons,
            meta = meta,
            handler = handler
        )
    }
}

internal data class BuiltTool(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchema: ToolSchema,
    val outputSchema: ToolSchema?,
    val annotations: ToolAnnotations?,
    val icons: List<Icon>?,
    val meta: JsonObject?,
    val handler: suspend HandleScope.() -> Unit
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
 * providing access to the SSE connection context for sessions, principal, etc.
 */
@KtorDsl
public class McpConfig internal constructor(
    /**
     * The SSE connection's ApplicationCall. Used to create ToolingCalls per tool invocation.
     */
    internal val sseCall: ApplicationCall
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
    internal val toolHandlers = mutableMapOf<String, suspend ToolingCall.() -> Unit>()
    internal var configureBlock: (suspend ConfigureScope.() -> Unit)? = null

    /**
     * Register a tool with a simple handler.
     *
     * The handler receives a [ToolingCall] and should call `call.respond()` or `call.respondText()`.
     *
     * Example:
     * ```kotlin
     * mcp("/mcp") {
     *     tool("whoami", "Returns current user") {
     *         val user = sessions.get<UserSession>()
     *         respondText("Hello, ${user?.name ?: "stranger"}!")
     *     }
     * }
     * ```
     */
    public fun tool(
        name: String,
        description: String,
        inputSchema: ToolSchema = ToolSchema(),
        handler: suspend ToolingCall.() -> Unit
    ) {
        tools.add(Tool(name = name, description = description, inputSchema = inputSchema))
        toolHandlers[name] = handler
    }

    /**
     * Register a tool using the DSL builder.
     *
     * Use `call.respond()` or `call.respondText()` to set the result.
     *
     * Example:
     * ```kotlin
     * mcp("/mcp") {
     *     tool("greet") {
     *         title = "Greet User"
     *         description = "Greet a user by name"
     *
     *         annotations {
     *             readOnlyHint = true  // Doesn't modify state
     *         }
     *
     *         schema {
     *             string("name", "User's name", required = true)
     *             int("age", "User's age") { minimum = 0 }
     *         }
     *
     *         handle {
     *             val name = requireString("name")
     *             val age = int("age") ?: 0
     *             call.respondText("Hello $name, age $age")
     *         }
     *     }
     * }
     * ```
     */
    public fun tool(name: String, block: ToolBuilder.() -> Unit) {
        val builder = ToolBuilder(name)
        builder.block()
        val built = builder.build()

        tools.add(
            Tool(
                name = built.name,
                title = built.title,
                description = built.description,
                inputSchema = built.inputSchema,
                outputSchema = built.outputSchema,
                annotations = built.annotations,
                icons = built.icons,
                meta = built.meta
            )
        )
        toolHandlers[name] = {
            val scope = HandleScope(this)
            built.handler(scope)
        }
    }

    /**
     * Configure the MCP ServerSession directly using SDK methods.
     *
     * Provides full access to SDK features via [ConfigureScope.server].
     * Ktor SSE call available via [ConfigureScope.call].
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
