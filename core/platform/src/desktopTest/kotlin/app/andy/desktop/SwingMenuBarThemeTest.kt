package app.andy.desktop

import androidx.compose.ui.graphics.Color
import app.andy.ui.theme.AndySurfaceMode
import app.andy.ui.theme.windowBackgroundForTint
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SwingMenuBarThemeTest {
    private val keys = listOf(
        "MenuBar.background",
        "MenuBar.foreground",
        "MenuBar.gradient",
        "MenuBar.borderColor",
        "Menu.background",
        "Menu.foreground",
        "Menu.selectionBackground",
        "MenuItem.background",
        "MenuItem.foreground",
        "PopupMenu.background",
        "PopupMenu.foreground",
    )
    private val previous = keys.associateWith { UIManager.get(it) }

    @AfterTest
    fun restoreUiManager() {
        keys.forEach { key ->
            UIManager.put(key, previous[key])
        }
    }

    @Test
    fun darkWindowUsesLightMenuForeground() {
        val colors = swingMenuColors(Color(0xFF111112))
        assertTrue(colors.foreground.red + colors.foreground.green + colors.foreground.blue > 400)
        assertEquals(0x11, colors.background.red)
    }

    @Test
    fun lightWindowUsesDarkMenuForeground() {
        val colors = swingMenuColors(Color(0xFFF1F4F7))
        assertTrue(colors.foreground.red + colors.foreground.green + colors.foreground.blue < 200)
    }

    @Test
    fun pitchBlackPaletteMatchesWindowBackground() {
        val window = windowBackgroundForTint("andy-blue", AndySurfaceMode.PitchBlack.id)
        val colors = swingMenuColors(window)
        val expected = java.awt.Color(window.red, window.green, window.blue)
        assertEquals(expected.rgb, colors.background.rgb)
    }

    @Test
    fun installReplacesMetalOceanGradient() {
        UIManager.put("MenuBar.gradient", listOf(0xFFFFFF))
        val colors = swingMenuColors(Color(0xFF111112))
        installSwingMenuUiDefaults(colors)
        val gradient = UIManager.get("MenuBar.gradient") as List<*>
        assertEquals(5, gradient.size)
        assertEquals(colors.background.rgb, (gradient[2] as java.awt.Color).rgb)
        assertEquals(colors.background.rgb, (UIManager.getColor("MenuBar.background") as java.awt.Color).rgb)
        assertEquals(colors.foreground.rgb, (UIManager.getColor("MenuBar.foreground") as java.awt.Color).rgb)
        assertEquals(colors.popupBackground.rgb, (UIManager.getColor("MenuItem.background") as java.awt.Color).rgb)
    }

    @Test
    fun themeMenuBarPaintsBarAndTopLevelMenus() {
        val colors = swingMenuColors(Color(0xFF111112))
        val bar = JMenuBar()
        val go = JMenu("Go")
        bar.add(go)
        themeMenuBar(bar, colors)
        assertEquals(colors.background.rgb, bar.background.rgb)
        assertEquals(colors.foreground.rgb, bar.foreground.rgb)
        assertTrue(bar.isOpaque)
        assertNotNull(bar.border)
        assertEquals(colors.background.rgb, go.background.rgb)
        assertEquals(colors.foreground.rgb, go.foreground.rgb)
    }
}
