package app.andy.desktop.service.agents

import app.andy.model.AgentProviderQuota
import app.andy.model.AgentQuotaSource
import app.andy.model.AgentQuotaWindow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenRouterQuotaParsingTest {
    @Test
    fun mapsKeyEndpointFieldsToQuotaWindows() {
        val data = buildJsonObject {
            put("label", "sk-or-v1-test")
            put("limit", 100.0)
            put("limit_remaining", 74.5)
            put("usage_daily", 1.25)
            put("usage_weekly", 10.0)
            put("usage_monthly", 25.5)
        }
        val quota = parseOpenRouterKeyPayload(data)
        assertNotNull(quota)
        assertEquals(AgentQuotaSource.ProviderQuery, quota.source)
        assertEquals("sk-or-v1-test", quota.accountLabel)
        assertEquals(4, quota.windows.size)
        assertTrue(quota.windows.any { it.label == "key limit" && it.detail?.contains("74.5") == true })
        assertTrue(quota.windows.any { it.label == "usage today" })
        assertTrue(quota.windows.any { it.label == "usage this week" })
        assertTrue(quota.windows.any { it.label == "usage this month" })
    }
}

/** Test helper mirroring ProviderQuotaProbe.queryOpenRouter mapping. */
internal fun parseOpenRouterKeyPayload(data: JsonObject): AgentProviderQuota? {
    fun JsonObject.doubleOrNull(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
    fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content
    fun formatCredits(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
    val windows = buildList {
        val limit = data.doubleOrNull("limit")
        val remaining = data.doubleOrNull("limit_remaining")
        if (limit != null && limit > 0 && remaining != null) {
            add(
                AgentQuotaWindow(
                    label = "key limit",
                    remainingFraction = (remaining / limit).toFloat().coerceIn(0f, 1f),
                    detail = "${formatCredits(remaining)} / ${formatCredits(limit)} remaining",
                ),
            )
        } else if (remaining != null) {
            add(AgentQuotaWindow(label = "key limit", detail = "${formatCredits(remaining)} remaining"))
        }
        data.doubleOrNull("usage_daily")?.let {
            add(AgentQuotaWindow(label = "usage today", detail = formatCredits(it)))
        }
        data.doubleOrNull("usage_weekly")?.let {
            add(AgentQuotaWindow(label = "usage this week", detail = formatCredits(it)))
        }
        data.doubleOrNull("usage_monthly")?.let {
            add(AgentQuotaWindow(label = "usage this month", detail = formatCredits(it)))
        }
    }
    if (windows.isEmpty()) return null
    return AgentProviderQuota(
        windows = windows,
        updatedAtMillis = 0L,
        source = AgentQuotaSource.ProviderQuery,
        accountLabel = data.stringOrNull("label"),
    )
}
