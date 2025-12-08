# ktor-server-mcp

Session-aware MCP server integration for Ktor.

A thin wrapper around the official [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk), designed to work with [ktor-server-oauth](https://github.com/vctrl/ktor-server-oauth) and any Ktor `authenticate {}` flow. Build MCP servers where tools have access to authenticated user sessions and principals.

## Why This Library?

The official MCP Kotlin SDK provides excellent protocol support but doesn't integrate with Ktor sessions or authentication blocks. This library bridges that gap:

- **Works inside `authenticate {}`** - Respects Ktor's route hierarchy, so MCP endpoints can be protected by any auth provider.
- **Session & Principal access** - Read user-specific data via `sessions` and `call.principal<T>()`.
- **Designed for ktor-server-oauth** - Seamlessly integrates with OAuth provision flows.
- **Idiomatic Kotlin DSL** - Clean `tool()` syntax with automatic error handling.
- **Full SDK access** - Use `configure {}` for prompts, resources, and advanced features.

## Installation

```kotlin
dependencies {
    implementation("com.vcontrol:ktor-server-mcp:0.1.0")
    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.1")
}
```

## Quick Start

```kotlin
fun Application.module() {
    install(SSE)
    install(Authentication) {
        bearer("api-key") { /* ... */ }
    }

    routing {
        authenticate("api-key") {
            mcp("/mcp") {
                name = "my-server"
                version = "1.0.0"

                tool("greet", "Greets the user") {
                    val name = args["name"] ?: "World"
                    textResult("Hello, $name!")
                }
            }
        }
    }
}
```

## Session & Principal Access

Access authenticated user data from your tools:

```kotlin
@Serializable
data class UserSession(val apiKey: String, val name: String)

routing {
    authenticate {
        mcp("/mcp") {
            val user = sessions.get<UserSession>()
            val principal = call.principal<UserPrincipal>()

            tool("whoami", "Returns current user") {
                textResult("Hello, ${user?.name ?: principal?.name ?: "stranger"}!")
            }

            tool("call_api", "Calls API with user's key") {
                val endpoint = args["endpoint"] ?: "/default"
                val result = apiClient.call(endpoint, user?.apiKey)
                textResult(result)
            }
        }
    }
}
```

## Helper Functions

Simple helpers for common response patterns:

```kotlin
// Single text result
textResult("Hello!")

// Multiple text results
textResult("Line 1", "Line 2", "Line 3")

// Error result
errorResult("Something went wrong")
```

## Error Handling

Exceptions are automatically caught and returned as error results:

```kotlin
tool("risky", "Might fail") {
    if (args["value"] == null) {
        throw IllegalArgumentException("value is required")
    }
    textResult("Success!")
}
// Errors returned as: CallToolResult(isError = true, content = "Error: value is required")
```

## With ktor-server-oauth

Pair with [ktor-server-oauth](https://github.com/vctrl/ktor-server-oauth) for OAuth 2.0 protected MCP servers:

```kotlin
fun Application.module() {
    install(OAuth) {
        authorizationServer(LocalAuthServer) { openRegistration = true }
    }
    install(OAuthSessions) {
        session<ApiKeySession>()
    }
    install(Authentication) {
        oauthJwt()
    }

    routing {
        provision {
            get { call.respondHtml { apiKeyForm() } }
            post {
                sessions.set(ApiKeySession(call.receiveParameters()["api_key"]!!))
                complete()
            }
        }

        authenticate {
            mcp("/mcp") {
                val session = sessions.get<ApiKeySession>()

                tool("query", "Query using user's API key") {
                    val result = queryWithKey(session?.apiKey)
                    textResult(result)
                }
            }
        }
    }
}
```

See [ktor-oauth-mcp-samples](https://github.com/vctrl/ktor-oauth-mcp-samples) for complete working examples.

## SDK Passthrough

Use `configure {}` for full SDK access (prompts, resources, advanced features):

```kotlin
mcp("/mcp") {
    tool("hello", "Says hello") {
        textResult("Hello!")
    }

    configure {
        val user = sessions.get<UserSession>()

        server.addPrompt("summarize", "Summarizes text") { request ->
            GetPromptResult(messages = listOf(...))
        }

        server.addResource("config://settings", "User settings") { request ->
            ReadResourceResult(contents = listOf(...))
        }
    }
}
```

## API Reference

### Route.mcp()

```kotlin
fun Route.mcp(path: String = "", configure: McpConfig.() -> Unit): Route
```

Registers MCP SSE and POST endpoints at the given path.

### McpConfig

| Property | Description |
|----------|-------------|
| `name` | Server name (shown to clients) |
| `version` | Server version |
| `title` | Human-readable title (optional) |
| `websiteUrl` | Server website URL (optional) |
| `icons` | Server icons (optional) |
| `capabilities` | `ServerCapabilities` - auto-set when using `tool()` |
| `call` | Ktor `ApplicationCall` for sessions, principal, etc. |
| `sessions` | Shortcut for `call.sessions` |

### tool()

```kotlin
fun tool(
    name: String,
    description: String,
    inputSchema: ToolSchema = ToolSchema(),
    handler: suspend ToolScope.() -> CallToolResult
)
```

Register a tool. Use `textResult()` helper for simple responses. Errors are caught automatically.

### ToolScope

| Property | Description |
|----------|-------------|
| `args` | Tool arguments as `Map<String, Any?>` |
| `call` | Ktor `ApplicationCall` for sessions, principal, etc. |
| `sessions` | Shortcut for `call.sessions` |

### Helper Functions

```kotlin
fun textResult(text: String): CallToolResult
fun textResult(vararg texts: String): CallToolResult
fun errorResult(message: String): CallToolResult
```

### configure {}

```kotlin
fun configure(block: suspend ConfigureScope.() -> Unit)
```

Direct SDK access. `ConfigureScope` provides:
- `server` - the MCP `ServerSession` for SDK methods
- `call` - Ktor `ApplicationCall`
- `sessions` - shortcut for `call.sessions`

## License

Apache 2.0
