package app.andy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectoryPickerOutputTest {
    @Test
    fun extractPickerPathIgnoresZenityGtkWarnings() {
        val output = """
            (zenity:205385): Adwaita-WARNING **: 12:14:02.310: Using GtkSettings:gtk-application-prefer-dark-theme with libadwaita is unsupported. Please use AdwStyleManager:color-scheme instead.
            /home/joe/Code/Andy/Andy
        """.trimIndent()
        assertEquals("/home/joe/Code/Andy/Andy", extractPickerPath(output))
    }

    @Test
    fun extractPickerPathUsesCleanStdout() {
        assertEquals("/home/joe/Code/Andy/Andy", extractPickerPath("/home/joe/Code/Andy/Andy\n"))
        assertNull(extractPickerPath(""))
        assertNull(extractPickerPath("   \n"))
    }

    @Test
    fun extractPickerPathsKeepsZenityPipeSeparatedFiles() {
        val output = """
            (zenity:1): Gtk-WARNING **: ignored
            /tmp/a.txt|/tmp/b.txt
        """.trimIndent()
        assertEquals(listOf("/tmp/a.txt", "/tmp/b.txt"), extractPickerPaths(output, splitOn = "|"))
    }
}
