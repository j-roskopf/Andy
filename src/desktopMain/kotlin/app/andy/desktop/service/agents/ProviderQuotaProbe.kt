package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaAccess
import app.andy.model.AgentQuotaSource
import app.andy.model.AgentQuotaWindow
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Pulls provider quotas after the user has explicitly enabled that provider's
 * account-access setting. Browser cookies are never read.
 */
internal class ProviderQuotaProbe {
    @Volatile private var cachedClaudeToken: String? = null
    @Volatile private var claudeKeychainAttempted = false

    fun query(agent: AgentKind, binary: String, access: AgentQuotaAccess): Pair<AgentKind, AgentProviderQuota>? = when (agent) {
        AgentKind.Codex -> queryCodex(binary)?.let { agent to it }
        AgentKind.ClaudeCode -> if (access.claudeAccountAccess) queryClaude()?.let { agent to it } else null
        AgentKind.Cursor -> if (access.cursorAccountAccess) queryCursor()?.let { agent to it } else null
        // Antigravity's quota surface is an internal local language-server protocol.
        // Its opt-in is stored now; the probe intentionally waits for a stable versioned endpoint.
        AgentKind.Antigravity -> null
    }

    fun clearAccountAccess(agent: AgentKind) {
        if (agent == AgentKind.ClaudeCode) cachedClaudeToken = null
    }

    private fun queryCodex(binary: String): AgentProviderQuota? = runCatching {
        val process = ProcessBuilder(binary, "app-server", "--stdio")
            .directory(File(System.getProperty("user.home")))
            .redirectErrorStream(true)
            .start()
        try {
            val writer = process.outputStream.bufferedWriter()
            writer.appendLine("""{"id":1,"method":"initialize","params":{"clientInfo":{"name":"Andy","version":"1"}}}""")
            writer.appendLine("""{"id":2,"method":"account/rateLimits/read","params":null}""")
            writer.flush()

            var rateLimits: JsonObject? = null
            val reader = process.inputStream.bufferedReader()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline && rateLimits == null) {
                while (reader.ready()) {
                    val event = reader.readLine() ?: break
                    val objectValue = parseJsonObject(event) ?: continue
                    when (objectValue["id"]?.toString()) {
                        "2" -> rateLimits = objectValue.objectOrNull("result")
                    }
                }
                if (rateLimits == null) Thread.sleep(25)
            }
            writer.close()
            rateLimits?.let { parseCodexQuota(it, System.currentTimeMillis()) }
        } finally {
            process.destroy()
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
    }.getOrNull()

    private fun parseCodexQuota(rateResponse: JsonObject, nowMillis: Long): AgentProviderQuota? {
        val legacySnapshot = rateResponse.objectOrNull("rateLimits") ?: return null
        val snapshots = rateResponse.objectOrNull("rateLimitsByLimitId")
            ?.mapNotNull { (limitId, value) -> (value as? JsonObject)?.let { limitId to it } }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(legacySnapshot.stringOrNull("limitId").orEmpty() to legacySnapshot)
        val windows = buildList {
            snapshots.forEach { (limitId, snapshot) ->
                val qualifier = snapshot.stringOrNull("limitName")
                    ?: limitId.takeIf { snapshots.size > 1 }
                snapshot.objectOrNull("primary")?.let { primary -> addCodexWindow(primary, "primary", qualifier) }
                snapshot.objectOrNull("secondary")?.let { secondary -> addCodexWindow(secondary, "secondary", qualifier) }
                snapshot.objectOrNull("individualLimit")?.let { limit ->
                    add(
                        AgentQuotaWindow(
                            label = listOfNotNull(qualifier, "spend limit").joinToString(" · "),
                            remainingFraction = limit.longOrNull("remainingPercent")?.div(100f),
                            resetAtMillis = limit.longOrNull("resetsAt")?.times(1000),
                            detail = limit.stringOrNull("used")?.let { "$it used" },
                        ),
                    )
                }
                snapshot.objectOrNull("credits")?.takeIf { it.booleanOrNull("hasCredits") == true }?.let { credits ->
                    add(AgentQuotaWindow(label = listOfNotNull(qualifier, "credits").joinToString(" · "), detail = credits.stringOrNull("balance") ?: "available"))
                }
            }
            rateResponse.objectOrNull("rateLimitResetCredits")?.longOrNull("availableCount")
                ?.takeIf { it > 0 }
                ?.let { count -> add(AgentQuotaWindow(label = "reset credits", detail = "$count available")) }
        }
        return AgentProviderQuota(
            windows = windows,
            updatedAtMillis = nowMillis,
            source = AgentQuotaSource.ProviderQuery,
            accountLabel = legacySnapshot.stringOrNull("planType")?.replace('_', ' ')?.replaceFirstChar(Char::uppercase),
        )
    }

    private fun MutableList<AgentQuotaWindow>.addCodexWindow(
        window: JsonObject,
        fallbackLabel: String,
        qualifier: String?,
    ) {
        val minutes = window.longOrNull("windowDurationMins")
        val label = when (minutes) {
            60L -> "hourly"
            300L -> "session"
            10_080L -> "weekly"
            null -> fallbackLabel
            else -> "${minutes} min"
        }
        add(
            AgentQuotaWindow(
                label = listOfNotNull(qualifier, label).joinToString(" · "),
                remainingFraction = window.longOrNull("usedPercent")?.let { (100L - it).coerceIn(0L, 100L) / 100f },
                // The Codex app-server schema specifies Unix seconds, unlike CLI stream events.
                resetAtMillis = window.longOrNull("resetsAt")?.times(1000),
            ),
        )
    }

    private fun queryCursor(): AgentProviderQuota? = runCatching {
        val accessToken = cursorAccessToken() ?: return@runCatching null
        val userID = jwtClaim(accessToken, "sub")?.substringAfterLast('|')?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val response = getJson(
            "https://cursor.com/api/usage-summary",
            mapOf("Cookie" to "WorkosCursorSessionToken=$userID%3A%3A$accessToken"),
        ) ?: return@runCatching null
        val individual = response.objectOrNull("individualUsage")
        val plan = individual?.objectOrNull("plan")
        val reset = response.stringOrNull("billingCycleEnd")?.let(::parseIsoMillis)
        val windows = buildList {
            plan?.let { value ->
                val used = percentUsed(value)
                add(AgentQuotaWindow("included plan", used?.let { 1f - it }, reset, currencyDetail(value)))
                value.doubleOrNull("autoPercentUsed")?.let { add(AgentQuotaWindow("auto + composer", 1f - percentFraction(it), reset)) }
                value.doubleOrNull("apiPercentUsed")?.let { add(AgentQuotaWindow("named models", 1f - percentFraction(it), reset)) }
            }
            individual?.objectOrNull("overall")?.let { value ->
                percentUsed(value)?.let { add(AgentQuotaWindow("individual cap", 1f - it, reset, currencyDetail(value))) }
            }
        }
        AgentProviderQuota(
            windows = windows,
            updatedAtMillis = System.currentTimeMillis(),
            source = AgentQuotaSource.ProviderQuery,
            accountLabel = response.stringOrNull("membershipType")?.replaceFirstChar(Char::uppercase),
        )
    }.getOrNull()

    private fun queryClaude(): AgentProviderQuota? = runCatching {
        val accessToken = claudeAccessToken() ?: return@runCatching null
        val response = getJson(
            "https://api.anthropic.com/api/oauth/usage",
            mapOf(
                "Authorization" to "Bearer $accessToken",
                "anthropic-beta" to "oauth-2025-04-20",
            ),
        ) ?: return@runCatching null
        val windows = buildList {
            response.objectOrNull("five_hour")?.let { addClaudeWindow(it, "session") }
            response.objectOrNull("seven_day")?.let { addClaudeWindow(it, "weekly") }
            response.objectOrNull("seven_day_sonnet")?.let { addClaudeWindow(it, "sonnet weekly") }
            response.objectOrNull("seven_day_opus")?.let { addClaudeWindow(it, "opus weekly") }
            response.objectOrNull("seven_day_routines")?.let { addClaudeWindow(it, "daily routines") }
        }
        AgentProviderQuota(
            windows = windows,
            updatedAtMillis = System.currentTimeMillis(),
            source = AgentQuotaSource.ProviderQuery,
            accountLabel = response.stringOrNull("subscriptionType")
                ?: response.stringOrNull("rate_limit_tier"),
        )
    }.getOrNull()

    private fun MutableList<AgentQuotaWindow>.addClaudeWindow(window: JsonObject, label: String) {
        val utilization = window.doubleOrNull("utilization")
            ?: window.doubleOrNull("utilization_percentage")
            ?: return
        val used = percentFraction(utilization)
        add(
            AgentQuotaWindow(
                label = label,
                remainingFraction = 1f - used,
                resetAtMillis = window.stringOrNull("resets_at")?.let(::parseIsoMillis)
                    ?: window.stringOrNull("resetsAt")?.let(::parseIsoMillis),
            ),
        )
    }

    private fun claudeAccessToken(): String? {
        cachedClaudeToken?.let { return it }
        val fileToken = File(System.getProperty("user.home"), ".claude/.credentials.json")
            .takeIf(File::isFile)
            ?.let { file -> runCatching { claudeTokenFromJson(file.readText()) }.getOrNull() }
        if (fileToken != null) return fileToken.also { cachedClaudeToken = it }
        if (claudeKeychainAttempted || !isMac()) return null
        claudeKeychainAttempted = true
        val keychainValue = runCatching {
            val process = ProcessBuilder("security", "find-generic-password", "-s", "Claude Code-credentials", "-w")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
            claudeTokenFromJson(process.inputStream.bufferedReader().readText())
        }.getOrNull()
        return keychainValue?.also { cachedClaudeToken = it }
    }

    private fun claudeTokenFromJson(raw: String): String? {
        val root = parseJsonObject(raw) ?: return null
        val oauth = root.objectOrNull("claudeAiOauth") ?: root.objectOrNull("claude_ai_oauth") ?: root
        return oauth.stringOrNull("accessToken") ?: oauth.stringOrNull("access_token")
    }

    private fun cursorAccessToken(): String? {
        val home = System.getProperty("user.home") ?: return null
        val db = File(home, "Library/Application Support/Cursor/User/globalStorage/state.vscdb")
        if (!db.isFile) return null
        val sqlite = listOfNotNull(
            System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/sqlite3" },
            "/usr/bin/sqlite3",
            "/opt/homebrew/bin/sqlite3",
        ).firstOrNull { File(it).canExecute() } ?: return null
        return runCatching {
            val process = ProcessBuilder(
                sqlite,
                "-readonly",
                db.absolutePath,
                "SELECT value FROM ItemTable WHERE key='cursorAuth/accessToken' LIMIT 1;",
            ).redirectErrorStream(true).start()
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
            process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun getJson(url: String, headers: Map<String, String>): JsonObject? = runCatching {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(10))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        response.takeIf { it.statusCode() == 200 }?.body()?.let(::parseJsonObject)
    }.getOrNull()

    private fun jwtClaim(token: String, name: String): String? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return@runCatching null
        val decoded = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
        parseJsonObject(decoded)?.stringOrNull(name)
    }.getOrNull()

    private fun percentUsed(value: JsonObject): Float? = value.doubleOrNull("totalPercentUsed")?.let(::percentFraction)
        ?: value.doubleOrNull("used")?.let { used ->
            value.doubleOrNull("limit")?.takeIf { it > 0 }?.let { limit -> (used / limit).toFloat() }
        }

    private fun percentFraction(value: Double): Float = (if (value <= 1.0) value else value / 100.0).toFloat().coerceIn(0f, 1f)

    private fun currencyDetail(value: JsonObject): String? {
        val used = value.longOrNull("used") ?: return null
        val limit = value.longOrNull("limit") ?: return "${used / 100}.${(used % 100).toString().padStart(2, '0')} used"
        return "${used / 100}.${(used % 100).toString().padStart(2, '0')} / ${limit / 100}.${(limit % 100).toString().padStart(2, '0')}"
    }

    private fun parseIsoMillis(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private fun isMac(): Boolean = System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

    private companion object {
        val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }
}
