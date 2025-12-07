import java.util.Properties

plugins {
    alias(libs.plugins.kotlinJvm)
    `java-library`
    `maven-publish`
    signing
}

val versionProps = Properties().apply {
    file("version.props").inputStream().use { load(it) }
}

group = "com.vcontrol"
version = versionProps.getProperty("version")

kotlin {
    jvmToolchain(22)
    explicitApi()
}

dependencies {
    // Ktor Server (API - exposed to consumers)
    api(libs.ktorServerCore)
    api(libs.ktorServerSse)
    api(libs.ktorServerSessions)

    // MCP SDK (API - exposed to consumers)
    api(libs.mcpSdk)

    // Logging (implementation detail)
    implementation(libs.kotlinLogging)

    // Testing
    testImplementation(libs.ktorServerTests)
    testRuntimeOnly(libs.slf4jSimple)
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("ktor-server-mcp")
                description.set("Ktor integration for MCP (Model Context Protocol) with session support")
                url.set("https://github.com/vctrl/ktor-server-mcp")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("vctrl")
                        name.set("vctrl")
                    }
                }

                scm {
                    url.set("https://github.com/vctrl/ktor-server-mcp")
                    connection.set("scm:git:git://github.com/vctrl/ktor-server-mcp.git")
                    developerConnection.set("scm:git:ssh://github.com/vctrl/ktor-server-mcp.git")
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["maven"])
}
