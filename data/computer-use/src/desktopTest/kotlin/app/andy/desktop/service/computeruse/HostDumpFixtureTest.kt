package app.andy.desktop.service.computeruse

import app.andy.model.HostElementBounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostDumpFixtureTest {
    @Test
    fun prunesRecordedFixtureLikeSpikeViewportPipeline() {
        val raw = javaClass.classLoader
            .getResourceAsStream("computeruse/fixture-tree.json")!!
            .bufferedReader()
            .readText()
        val arr = Json.parseToJsonElement(raw).jsonArray
        val nodes = arr.map { el ->
            val o = el.jsonObject
            HostRawNode(
                id = o["id"]!!.jsonPrimitive.content,
                role = o["role"]!!.jsonPrimitive.content,
                label = o["label"]?.jsonPrimitive?.content,
                bounds = HostElementBounds.parse(o["bounds"]!!.jsonPrimitive.content),
                actions = o["actions"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
                windowBounds = HostElementBounds.parse(o["windowBounds"]!!.jsonPrimitive.content),
            )
        }
        val tree = HostDumpPipeline.prune("FixtureApp", nodes)
        assertEquals(listOf("1", "2", "3"), tree.elements.map { it.id })
        assertTrue(tree.elements.any { it.label == "Delete" && it.pressable })
        val compact = HostDumpPipeline.compactJson(tree)
        assertTrue(compact.contains("\"i\":\"1\""))
        assertTrue(compact.length < 2_000)
    }
}
