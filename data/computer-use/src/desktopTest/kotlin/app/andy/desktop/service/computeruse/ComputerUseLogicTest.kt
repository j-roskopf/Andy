package app.andy.desktop.service.computeruse

import app.andy.model.HostElementBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostDumpPipelineTest {
    @Test
    fun viewportClipDropsOffscreenAndTinyNodes() {
        val viewport = HostElementBounds(0, 0, 1000, 800)
        val nodes = listOf(
            HostRawNode(
                id = "1",
                role = "AXButton",
                label = "OK",
                bounds = HostElementBounds(10, 10, 80, 30),
                actions = setOf("AXPress"),
                windowBounds = viewport,
            ),
            HostRawNode(
                id = "2",
                role = "AXButton",
                label = "Offscreen",
                bounds = HostElementBounds(2000, 10, 80, 30),
                actions = setOf("AXPress"),
                windowBounds = viewport,
            ),
            HostRawNode(
                id = "3",
                role = "AXButton",
                label = "Tiny",
                bounds = HostElementBounds(10, 10, 1, 1),
                actions = setOf("AXPress"),
                windowBounds = viewport,
            ),
            HostRawNode(
                id = "4",
                role = "AXStaticText",
                label = "Hello",
                bounds = HostElementBounds(20, 50, 100, 20),
                windowBounds = viewport,
            ),
        )
        val tree = HostDumpPipeline.prune("TestApp", nodes)
        assertEquals(listOf("1", "4"), tree.elements.map { it.id })
        assertFalse(tree.truncated)
    }

    @Test
    fun pressableWithoutControlRoleIsIncluded() {
        // §0.4 — Compose/Electron often report AXStaticText / AXGroup with AXPress.
        val viewport = HostElementBounds(0, 0, 500, 500)
        val nodes = listOf(
            HostRawNode(
                id = "g1",
                role = "AXGroup",
                label = "Save",
                bounds = HostElementBounds(5, 5, 40, 20),
                actions = setOf("AXPress"),
                windowBounds = viewport,
            ),
        )
        val tree = HostDumpPipeline.prune("Cursor", nodes)
        assertEquals(1, tree.elements.size)
        assertTrue(tree.elements.single().pressable)
    }

    @Test
    fun unlabeledPressableStillEmitted() {
        val viewport = HostElementBounds(0, 0, 500, 500)
        val nodes = listOf(
            HostRawNode(
                id = "u1",
                role = "AXButton",
                label = null,
                bounds = HostElementBounds(5, 5, 40, 20),
                actions = setOf("AXPress"),
                windowBounds = viewport,
            ),
        )
        val tree = HostDumpPipeline.prune("Finder", nodes)
        assertEquals(1, tree.elements.size)
        assertEquals(null, tree.elements.single().label)
    }

    @Test
    fun payloadCapReportsTruncation() {
        val viewport = HostElementBounds(0, 0, 2000, 2000)
        val nodes = (1..200).map { i ->
            HostRawNode(
                id = "$i",
                role = "AXButton",
                label = "Button number $i with a fairly long label for bytes",
                bounds = HostElementBounds(i, i, 40, 20),
                actions = setOf("AXPress"),
                windowBounds = viewport,
            )
        }
        val tree = HostDumpPipeline.prune("Heavy", nodes, payloadCapBytes = 2_000)
        assertTrue(tree.truncated)
        assertTrue(tree.elements.size < 200)
        assertTrue(tree.payloadBytes <= 2_000)
    }

    @Test
    fun wrapUntrustedMarksScreenText() {
        val wrapped = HostDumpPipeline.wrapUntrusted("click here")
        assertTrue(wrapped.contains("<untrusted_screen_content>"))
        assertTrue(wrapped.contains("click here"))
    }
}

class ComputerUseScopeDenylistTest {
    @Test
    fun deniesTerminalsPasswordManagersAndAndy() {
        assertTrue(ComputerUseScopeDenylist.isDenied("Terminal"))
        assertTrue(ComputerUseScopeDenylist.isDenied("iTerm2"))
        assertTrue(ComputerUseScopeDenylist.isDenied("com.apple.Terminal"))
        assertTrue(ComputerUseScopeDenylist.isDenied("1Password"))
        assertTrue(ComputerUseScopeDenylist.isDenied("Andy"))
        assertTrue(ComputerUseScopeDenylist.isDenied("com.joetr.andy"))
        assertFalse(ComputerUseScopeDenylist.isDenied("Google Chrome"))
        assertFalse(ComputerUseScopeDenylist.isDenied("Finder"))
    }
}

class HighConsequenceClassifierTest {
    @Test
    fun labelFirstRegardlessOfRole() {
        val hit = HighConsequenceClassifier.classify(label = "Delete forever", role = "AXStaticText")
        assertTrue(hit.highConsequence)
        assertEquals("delete", hit.matchedLabel)

        val miss = HighConsequenceClassifier.classify(label = "Cancel", role = "AXButton")
        assertFalse(miss.highConsequence)
    }

    @Test
    fun unlabeledUnattendedIsRefused() {
        val v = HighConsequenceClassifier.classify(
            label = null,
            unlabeledUnattended = true,
        )
        assertTrue(v.highConsequence)
    }

    @Test
    fun secureFieldDetection() {
        assertTrue(HighConsequenceClassifier.isSecureField("AXTextField", "AXSecureTextField"))
        assertTrue(HighConsequenceClassifier.isSecureField("AXSecureTextField", null))
        assertFalse(HighConsequenceClassifier.isSecureField("AXTextField", null))
    }
}

class CoordinateSpaceTest {
    @Test
    fun mapsLogicalPointOntoScaledDisplay() {
        val displays = listOf(
            DisplayGeometry(1, 0, 0, 1440, 900, scale = 2.0, primary = true),
        )
        val physical = CoordinateSpace.toPhysical(CoordinateSpace.Point(100, 50), displays)
        assertEquals(200, physical.x)
        assertEquals(100, physical.y)
    }

    @Test
    fun unionBoundsSpansDisplays() {
        val displays = listOf(
            DisplayGeometry(1, 0, 0, 1000, 800, 1.0, true),
            DisplayGeometry(2, 1000, 0, 800, 600, 1.0, false),
        )
        val union = CoordinateSpace.unionBounds(displays)!!
        assertEquals(0, union.x)
        assertEquals(1800, union.width)
    }
}

class ComputerUseProfileStoreTest {
    @Test
    fun roundTripProfiles() {
        val profiles = listOf(
            app.andy.model.ComputerUseGrantProfile(
                id = "p1",
                name = "Expense",
                scope = app.andy.model.ComputerUseScope(appNames = listOf("Google Chrome", "Preview")),
                attended = false,
                wallClockCapSeconds = 600,
                highConsequenceLabels = listOf("Submit expense"),
            ),
        )
        val encoded = ComputerUseProfileStore.encode(profiles)
        val decoded = ComputerUseProfileStore.decode(encoded)
        assertEquals(profiles, decoded)
    }

    @Test
    fun validateScopeRejectsDenylist() {
        val err = ComputerUseProfileStore.validateScope(
            app.andy.model.ComputerUseScope(appNames = listOf("Terminal")),
        )
        assertTrue(err != null && err.contains("denylist"))
    }
}
