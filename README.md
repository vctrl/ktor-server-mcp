# ktor-server-mcp

Session-aware MCP server integration for Ktor.

A thin wrapper around the official [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk), designed to work with [ktor-server-oauth](https://github.com/vctrl/ktor-server-oauth) and any Ktor `authenticate {}` flow. Build MCP servers where tools have access to authenticated user sessions.

## Why This Library?

The official MCP Kotlin SDK provides excellent protocol support but doesn't integrate with Ktor sessions or authentication blocks. This library bridges that gap:

- **Works inside `authenticate {}`** - The SDK registers routes at the application root. This library respects Ktor's route hierarchy, so MCP endpoints can be protected by any auth provider.
- **Session access** - Read user-specific data (API keys, preferences, credentials) collected during OAuth or other auth flows.
- **Designed for ktor-server-oauth** - Seamlessly integrates with OAuth provision flows.
- **Thin wrapper** - Uses SDK methods directly (`addTool`, `addPrompt`, `addResource`, etc.) without proprietary abstractions.

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
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())

                configure {
                    addTool("greet", "Greets the user", ToolSchema()) { request ->
                        val name = request.params.arguments?.get("name")?.toString() ?: "World"
                        CallToolResult(content = listOf(TextContent(text = "Hello, $name!")))
                    }
                }
            }
        }
    }
}
```

## Session Access

Access Ktor sessions from the config block - useful for user-specific data collected during OAuth:

```kotlin
@Serializable
data class UserSession(val apiKey: String, val username: String)

routing {
    authenticate {
        mcp("/mcp") {
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())

            // Session captured at connection time
            val userSession = sessions.get<UserSession>()

            server {
                addTool("whoami", "Returns the current user", ToolSchema()) { _ ->
                    CallToolResult(
                        content = listOf(TextContent(text = userSession?.username ?: "Unknown"))
                    )
                }

                addTool("call_api", "Calls external API with user's key", ToolSchema()) { _ ->
                    val result = externalApi.call(userSession?.apiKey)
                    CallToolResult(content = listOf(TextContent(text = result)))
                }
            }
        }
    }
}
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
        // Collect API key during OAuth flow
        provision {
            get { call.respondHtml { apiKeyForm() } }
            post {
                sessions.set(ApiKeySession(call.receiveParameters()["api_key"]!!))
                complete()
            }
        }

        // MCP with session access
        authenticate {
            mcp("/mcp") {
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())
                val session = sessions.get<ApiKeySession>()

                configure {
                    addTool("query", "Query using user's API key", ToolSchema()) { _ ->
                        val result = queryWithKey(session?.apiKey)
                        CallToolResult(content = listOf(TextContent(text = result)))
                    }
                }
            }
        }
    }
}
```

See [ktor-oauth-mcp-samples](https://github.com/vctrl/ktor-oauth-mcp-samples) for complete working examples.

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
| `capabilities` | `ServerCapabilities` - configure based on features you register |
| `sessions` | Ktor `CurrentSession` for accessing session data |

### configure {}

```kotlin
fun configure(block: suspend ServerSession.() -> Unit)
```

Configure the MCP `ServerSession` directly. Full access to all SDK methods: `addTool()`, `addPrompt()`, `addResource()`, `setRequestHandler()`, etc.

## License

Apache 2.0
