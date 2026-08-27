package app.andy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportedImageFilesTest {
    @Test
    fun recognizesCommonImageExtensions() {
        assertTrue("/tmp/mockup.png".isSupportedImagePath())
        assertTrue("C:\\shots\\screen.JPEG".isSupportedImagePath())
        assertFalse("/tmp/notes.txt".isSupportedImagePath())
        assertFalse("no-extension".isSupportedImagePath())
    }

    @Test
    fun mergeChatImagePathsDedupesAndFilters() {
        val merged = listOf("/tmp/a.png").mergeChatImagePaths(
            listOf("/tmp/b.jpg", "/tmp/a.png", "/tmp/readme.md"),
        )
        assertEquals(listOf("/tmp/a.png", "/tmp/b.jpg"), merged)
    }
}
