package app.andy

import app.andy.ui.hostfiles.hostAncestorDirectories
import kotlin.test.Test
import kotlin.test.assertEquals

class HostFilePathsTest {
    @Test
    fun windowsDriveRootAncestorsDoNotInsertDuplicateSeparator() {
        assertEquals(
            listOf("C:\\", "C:\\Users", "C:\\Users\\joe"),
            hostAncestorDirectories(path = "C:\\Users\\joe\\notes.txt", root = "C:\\"),
        )
    }
}
