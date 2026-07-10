package app.andy.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals

class FilesPathHelpersTest {
    @Test
    fun parentPathHandlesRootsAndNestedPaths() {
        assertEquals("/", parentPath("/"))
        assertEquals("/", parentPath("/sdcard"))
        assertEquals("/sdcard", parentPath("/sdcard/Download"))
        assertEquals("/sdcard/Download", parentPath("/sdcard/Download/report.txt"))
    }
}
