plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "com.vcontrol"
version = file("version.properties")
    .readLines().first { it.startsWith("version=") }.substringAfter("=")

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    // Ktor Server (API - exposed to consumers)
    api(libs.ktor.server.core)
    api(libs.ktor.server.sse)
    api(libs.ktor.server.sessions)

    // Bearer sessions (for direct session access in streaming contexts)
    implementation("com.vcontrol:ktor-bearer-sessions:0.2.0")

    // MCP SDK (API - exposed to consumers)
    api(libs.mcp.sdk)

    // Logging (implementation detail)
    implementation(libs.kotlin.logging)

    // Testing
    testImplementation(libs.ktor.server.tests)
    testRuntimeOnly(libs.slf4j.simple)
}

// Maven Central publishing via vanniktech plugin
mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        name.set("ktor-server-mcp")
        description.set("Session-aware MCP server integration for Ktor. A thin wrapper around the official MCP Kotlin SDK.")
        url.set("https://github.com/vctrl/ktor-server-mcp")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        developers {
            developer {
                id.set("pmokbel")
                name.set("Paul Mokbel")
                email.set("paul@mokbel.com")
            }
        }

        scm {
            url.set("https://github.com/vctrl/ktor-server-mcp")
            connection.set("scm:git:git://github.com/vctrl/ktor-server-mcp.git")
            developerConnection.set("scm:git:ssh://git@github.com/vctrl/ktor-server-mcp.git")
        }
    }
}
