package app.andy.ui.shell

import app.andy.service.LocalServerProcess
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalServerPortMappingLabelTest {
    @Test
    fun showsMappingOnlyWhenLocalDiffers() {
        val server = LocalServerProcess(pid = 1, ports = listOf(8080, 5173), displayName = "Vite")
        assertEquals(
            "localhost:8080, localhost:5173",
            server.portMappingLabel(emptyMap()),
        )
        assertEquals(
            "localhost:8080 → 15001, localhost:5173",
            server.portMappingLabel(mapOf(8080 to 15_001, 5173 to 5173)),
        )
    }
}
