package app.andy.desktop.service.hub

import app.andy.model.McpHubAuthKind
import app.andy.model.McpHubTransport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class McpHubRegistryStoreTest {
    @Test
    fun saveLoadRoundTrip() {
        val dir = kotlin.io.path.createTempDirectory("andy-mcp-hub").toFile()
        try {
            val store = McpHubRegistryStore(rootDir = dir)
            store.upsert(
                "sentry",
                app.andy.model.McpHubServerConfig(
                    transport = McpHubTransport.Http,
                    url = "https://mcp.sentry.dev/mcp",
                    auth = McpHubAuthKind.Oauth,
                ),
            )
            val loaded = store.load()
            assertEquals(1, loaded.servers.size)
            assertEquals("https://mcp.sentry.dev/mcp", loaded.servers["sentry"]?.url)
            assertEquals(McpHubAuthKind.Oauth, loaded.servers["sentry"]?.auth)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun importFromCursorSkipsAndyAndEmu() {
        val dir = kotlin.io.path.createTempDirectory("andy-mcp-hub").toFile()
        val cursor = File(dir, "cursor-mcp.json")
        try {
            cursor.writeText(
                """
                {
                  "mcpServers": {
                    "andy": { "type": "http", "url": "http://127.0.0.1:8565/mcp-http" },
                    "emu": { "type": "http", "url": "http://127.0.0.1:8565/mcp" },
                    "sentry": { "type": "http", "url": "https://mcp.sentry.dev/mcp" },
                    "local-stdio": {
                      "command": "npx",
                      "args": ["-y", "@example/mcp"],
                      "env": { "FOO": "bar" }
                    }
                  }
                }
                """.trimIndent(),
            )
            val store = McpHubRegistryStore(rootDir = File(dir, "andy-mcp"))
            val (_, imported) = store.importFromCursor(cursor)
            assertEquals(listOf("sentry", "local-stdio").sorted(), imported.sorted())
            val registry = store.load()
            assertFalse(registry.servers.containsKey("andy"))
            assertFalse(registry.servers.containsKey("emu"))
            assertEquals(McpHubTransport.Http, registry.servers["sentry"]?.transport)
            assertEquals(McpHubAuthKind.Oauth, registry.servers["sentry"]?.auth)
            assertEquals(McpHubTransport.Stdio, registry.servers["local-stdio"]?.transport)
            assertEquals("npx", registry.servers["local-stdio"]?.command)
            assertTrue(registry.imports.cursorGlobal)
            assertTrue(registry.imports.importedFrom.contains("Cursor"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun importFromCodexToml() {
        val home = kotlin.io.path.createTempDirectory("andy-mcp-codex-home").toFile()
        val originalHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val codex = File(home, ".codex/config.toml")
            codex.parentFile.mkdirs()
            codex.writeText(
                """
                [mcp_servers.andy]
                url = "http://127.0.0.1:8565/mcp-http"

                [mcp_servers.sentry]
                url = "https://mcp.sentry.dev/mcp"
                """.trimIndent(),
            )
            val store = McpHubRegistryStore(rootDir = File(home, ".andy/mcp"))
            val (_, imported) = store.importFrom("Codex")
            assertEquals(listOf("sentry"), imported)
            assertEquals("https://mcp.sentry.dev/mcp", store.load().servers["sentry"]?.url)
        } finally {
            System.setProperty("user.home", originalHome)
            home.deleteRecursively()
        }
    }

    @Test
    fun importFromOpenCodeJsonMcpKey() {
        val parsed = McpHubRegistryStore.parseServerObject(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"type":"remote","url":"https://mcp.example.com/mcp"}""",
            ).jsonObject,
        )
        assertNotNull(parsed)
        assertEquals("https://mcp.example.com/mcp", parsed.url)
        assertEquals(McpHubAuthKind.Oauth, parsed.auth)
    }

    @Test
    fun parseYamlHermesMcpServers() {
        val yaml = """
            mcp_servers:
              andy:
                url: "http://127.0.0.1:8565/mcp-http"
              docs:
                url: "https://docs.example.com/mcp"
        """.trimIndent()
        val parsed = McpHubRegistryStore.parseYamlServerBlock(yaml, rootKey = "mcp_servers")
        assertEquals("https://docs.example.com/mcp", parsed["docs"]?.url)
        assertEquals("http://127.0.0.1:8565/mcp-http", parsed["andy"]?.url)
    }

    @Test
    fun normalizeIdLowercasesAndSanitizes() {
        assertEquals("sentry", McpHubRegistryStore.normalizeId(" Sentry "))
        assertEquals("my-server", McpHubRegistryStore.normalizeId("My Server!"))
        assertEquals(null, McpHubRegistryStore.normalizeId("***"))
    }

    @Test
    fun importSourceLabelsIncludeMajorProviders() {
        val labels = McpHubRegistryStore().importSourceLabels()
        assertTrue(labels.contains("Cursor"))
        assertTrue(labels.contains("Codex"))
        assertTrue(labels.contains("Claude Code"))
        assertTrue(labels.contains("Antigravity"))
    }
}

class McpHubOAuthStoreTest {
    @Test
    fun importFromCursorAuthFile() {
        val dir = kotlin.io.path.createTempDirectory("andy-mcp-oauth").toFile()
        try {
            val authFile = File(dir, "mcp-auth.json")
            authFile.writeText(
                """
                {
                  "sentry": {
                    "tokens": {
                      "access_token": "tok-access",
                      "refresh_token": "tok-refresh",
                      "token_type": "bearer",
                      "expires_in": 3600,
                      "scope": "org:read"
                    },
                    "clientInfo": { "client_id": "client-123" }
                  }
                }
                """.trimIndent(),
            )
            val parsed = McpHubOAuthStore.parseCursorMcpAuth(authFile, "sentry")
            assertNotNull(parsed)
            assertEquals("tok-access", parsed.accessToken)
            assertEquals("tok-refresh", parsed.refreshToken)
            assertEquals("client-123", parsed.clientId)

            val store = McpHubOAuthStore(rootDir = File(dir, "vault"))
            store.save("sentry", parsed)
            assertEquals("tok-access", store.accessToken("sentry"))
        } finally {
            dir.deleteRecursively()
        }
    }
}

class McpUpstreamHttpSessionTest {
    @Test
    fun parseSseAndJsonBodies() {
        val jsonBody = """{"jsonrpc":"2.0","id":1,"result":{"ok":true}}"""
        val fromJson = McpUpstreamHttpSession.parseMcpHttpBody(jsonBody)
        assertEquals(true, fromJson?.get("result") != null)

        val sse = """
            event: message
            data: {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}

        """.trimIndent()
        val fromSse = McpUpstreamHttpSession.parseMcpHttpBody(sse)
        assertNotNull(fromSse?.get("result"))
    }
}
