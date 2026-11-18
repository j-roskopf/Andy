package app.andy.desktop.service.agents.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenCodeSessionCompactorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun compactsOldOversizedAttachmentAndTextToolPartsWhileKeepingRecentTextBudget() {
        val dbFile = File.createTempFile("andy-opencode-test", ".db")
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE part (
                            id TEXT PRIMARY KEY,
                            message_id TEXT NOT NULL,
                            session_id TEXT NOT NULL,
                            time_created INTEGER NOT NULL,
                            time_updated INTEGER NOT NULL,
                            data TEXT NOT NULL
                        )
                        """.trimIndent(),
                    )
                }
                conn.prepareStatement(
                    "INSERT INTO part (id, message_id, session_id, time_created, time_updated, data) VALUES (?, ?, ?, ?, ?, ?)",
                ).use { insert ->
                    fun addPart(id: String, msgId: String, time: Long, data: String) {
                        insert.setString(1, id)
                        insert.setString(2, msgId)
                        insert.setString(3, "ses_test")
                        insert.setLong(4, time)
                        insert.setLong(5, time)
                        insert.setString(6, data)
                        insert.executeUpdate()
                    }

                    val bigBase64 = "A".repeat(700_000)
                    addPart(
                        id = "prt_1",
                        msgId = "msg_1",
                        time = 100L,
                        data = """{"type":"tool","tool":"read","state":{"status":"completed","output":"Image read successfully","attachments":[{"type":"file","mime":"image/png","url":"data:image/png;base64,$bigBase64"}],"time":{"start":90,"end":100}}}""",
                    )
                    val oldTextOutput = "B".repeat(300_000)
                    addPart(
                        id = "prt_2",
                        msgId = "msg_2",
                        time = 200L,
                        data = """{"type":"tool","tool":"read","state":{"status":"completed","output":"$oldTextOutput","time":{"start":190,"end":200}}}""",
                    )
                    val recentTextOutput = "C".repeat(20_000)
                    addPart(
                        id = "prt_3",
                        msgId = "msg_3",
                        time = 300L,
                        data = """{"type":"tool","tool":"read","state":{"status":"completed","output":"$recentTextOutput","time":{"start":290,"end":300}}}""",
                    )
                }
            }

            val compactedCount = OpenCodeSessionCompactor.compactOversizedToolPartsIfNeeded(
                sessionId = "ses_test",
                dbFile = dbFile,
                nowMillis = 123_456_789L,
            )
            assertEquals(2, compactedCount)

            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                fun compactedAt(partId: String): Long? {
                    conn.prepareStatement("SELECT data FROM part WHERE id = ?").use { ps ->
                        ps.setString(1, partId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) return null
                            val root = json.parseToJsonElement(rs.getString(1)).jsonObject
                            val time = root["state"]?.jsonObject?.get("time")?.jsonObject
                            return time?.get("compacted")?.jsonPrimitive?.longOrNull
                        }
                    }
                }
                assertEquals(123_456_789L, compactedAt("prt_1"))
                assertEquals(123_456_789L, compactedAt("prt_2"))
                assertNull(compactedAt("prt_3"))
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun claudeFallbackModelAliasMapsVersionedIdsToFamilyAliases() {
        assertEquals("opus", claudeAcpFallbackModelAlias("claude-opus-5-5"))
        assertEquals("opus", claudeAcpFallbackModelAlias("claude-opus-4-7[1m]"))
        assertEquals("sonnet", claudeAcpFallbackModelAlias("claude-sonnet-4-6"))
        assertEquals("haiku", claudeAcpFallbackModelAlias("claude-haiku-4-5"))
        assertEquals("fable", claudeAcpFallbackModelAlias("claude-fable-5-1"))
        assertNull(claudeAcpFallbackModelAlias("opus"))
    }
}
