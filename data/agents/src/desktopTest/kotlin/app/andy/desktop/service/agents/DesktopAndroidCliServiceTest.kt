package app.andy.desktop.service.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAndroidCliServiceTest {

    @Test
    fun detectsInstallTargetForKnownPlatforms() {
        val os = System.getProperty("os.name").lowercase()
        val target = detectAndroidCliInstallTarget()
        when {
            os.contains("mac") || os.contains("darwin") -> {
                assertTrue(target == "darwin_arm64" || target == "darwin_x86_64", "unexpected: $target")
            }
            os.contains("linux") -> {
                assertTrue(target == "linux_x86_64" || target == null, "unexpected: $target")
            }
        }
    }

    @Test
    fun parsesMarketplaceSkillPaths() {
        val json = """
            {
              "name": "android-skills",
              "plugins": [
                {
                  "name": "android-skills",
                  "skills": [
                    "./navigation/navigation-3",
                    "./build-system/agp/agp-9-upgrade",
                    "./system/edge-to-edge"
                  ]
                }
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("navigation/navigation-3", "build-system/agp/agp-9-upgrade", "system/edge-to-edge"),
            parseMarketplaceSkillPaths(json),
        )
    }

    @Test
    fun marketplaceParsingToleratesMalformedInput() {
        assertEquals(emptyList(), parseMarketplaceSkillPaths("not json"))
        assertEquals(emptyList(), parseMarketplaceSkillPaths("{}"))
        assertEquals(emptyList(), parseMarketplaceSkillPaths("""{"plugins": []}"""))
    }

    @Test
    fun parsesNameAndFoldedDescription() {
        val markdown = """
            ---
            name: navigation-3
            description: Learn how to install and migrate to Jetpack Navigation 3, and how to
              implement features and patterns such as deep links and multiple backstacks.
            license: Complete terms in LICENSE.txt
            metadata:
              author: Google LLC
            ---
            # Navigation 3
        """.trimIndent()

        val (name, description) = parseSkillFrontmatter(markdown)
        assertEquals("navigation-3", name)
        assertEquals(
            "Learn how to install and migrate to Jetpack Navigation 3, and how to " +
                "implement features and patterns such as deep links and multiple backstacks.",
            description,
        )
    }

    @Test
    fun parsesSingleLineDescription() {
        val markdown = """
            ---
            name: r8-analyzer
            description: Analyzes Android build files and R8 keep rules.
            ---
            body
        """.trimIndent()

        val (name, description) = parseSkillFrontmatter(markdown)
        assertEquals("r8-analyzer", name)
        assertEquals("Analyzes Android build files and R8 keep rules.", description)
    }

    @Test
    fun missingFrontmatterYieldsNulls() {
        assertNull(parseSkillFrontmatter("# Heading\nNo frontmatter").first)
        assertNull(parseSkillFrontmatter(null).second)
        assertNull(parseSkillFrontmatter("").second)
    }
}
