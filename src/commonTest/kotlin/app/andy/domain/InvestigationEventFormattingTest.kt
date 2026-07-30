package app.andy.domain

import app.andy.model.InvestigationEvent
import app.andy.model.InvestigationEventKind
import app.andy.model.InvestigationInlinePayload
import app.andy.model.InvestigationPayloadRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvestigationEventFormattingTest {
    @Test
    fun networkExchangeSurfacesMethodUrlStatusAndDuration() {
        val event = InvestigationEvent(
            id = "network-1",
            atMillis = 10L,
            kind = InvestigationEventKind.NetworkExchange,
            summary = "GET example.test",
            inline = InvestigationInlinePayload(
                method = "GET",
                url = "https://example.test/api",
                statusCode = 500,
                durationMillis = 42L,
            ),
        )
        val fields = event.detailFields().toMap()
        assertEquals("GET", fields["method"])
        assertEquals("https://example.test/api", fields["url"])
        assertEquals("500", fields["status"])
        assertEquals("42ms", fields["duration"])
    }

    @Test
    fun crashSurfacesPackageAndCrashKind() {
        val event = InvestigationEvent(
            id = "crash-1",
            atMillis = 10L,
            kind = InvestigationEventKind.Crash,
            summary = "NullPointerException",
            inline = InvestigationInlinePayload(packageName = "app.demo", crashKind = "JavaCrash"),
        )
        val fields = event.detailFields().toMap()
        assertEquals("app.demo", fields["package"])
        assertEquals("JavaCrash", fields["crash kind"])
    }

    @Test
    fun payloadRefSurfacesAsSidecarPath() {
        val event = InvestigationEvent(
            id = "hierarchy-1",
            atMillis = 10L,
            kind = InvestigationEventKind.HierarchySnapshot,
            summary = "Hierarchy captured",
            payloadRef = InvestigationPayloadRef(relativePath = "events/hierarchy/hierarchy-1.json", kind = "hierarchy"),
        )
        val fields = event.detailFields().toMap()
        assertEquals("events/hierarchy/hierarchy-1.json", fields["sidecar"])
    }

    @Test
    fun shortTagsAreStableForEveryKind() {
        InvestigationEventKind.entries.forEach { kind ->
            assertTrue(kind.shortTag().isNotBlank())
        }
    }
}
