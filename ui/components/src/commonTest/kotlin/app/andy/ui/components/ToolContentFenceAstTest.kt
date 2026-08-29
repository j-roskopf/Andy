package app.andy.ui.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolContentFenceAstTest {
    @Test
    fun nestedContentFenceStaysInsideListItem() {
        val md = """
            |- **content:**
            |  ```kotlin
            |      variant == Ghost
            |  }
            |  val shape = 1
            |  ```
        """.trimMargin()
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(md)
        fun find(node: ASTNode, type: org.intellij.markdown.IElementType): ASTNode? {
            if (node.type == type) return node
            return node.children.firstNotNullOfOrNull { find(it, type) }
        }
        val listItem = find(root, MarkdownElementTypes.LIST_ITEM)
        assertTrue(listItem != null, "expected list item")
        val fenceInList = find(listItem, MarkdownElementTypes.CODE_FENCE)
        assertTrue(fenceInList != null, "fence should nest under list item, not sit as a sibling")
    }
}
