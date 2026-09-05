package app.andy.desktop.service.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteHostCapabilityScannerTest {
    @Test
    fun splitProbeSectionsKeepsMarkersApart() {
        val raw = """
            ___ANDY_LSOF___
            p1
            claunchd
            n*:5900
            ___ANDY_NETSTAT___
            tcp4 0 0 *.5900 *.* LISTEN
            ___ANDY_CONNECT___
            ANDY_VNC_OPEN
            ___ANDY_LAUNCHCTL___
            system/com.apple.screensharing = {
            	state = active
            }
        """.trimIndent()
        val sections = RemoteHostCapabilityScanner.splitProbeSections(raw)
        assertTrue(sections.getValue("LSOF").contains("n*:5900"))
        assertTrue(sections.getValue("NETSTAT").contains("*.5900"))
        assertEquals("ANDY_VNC_OPEN", sections.getValue("CONNECT").trim())
        assertTrue(sections.getValue("LAUNCHCTL").contains("com.apple.screensharing"))
    }
}
