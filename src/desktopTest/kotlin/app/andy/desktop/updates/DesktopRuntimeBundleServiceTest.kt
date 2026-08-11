package app.andy.desktop.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRuntimeBundleServiceTest {
    @Test
    fun findAssetMatchesCliPattern() {
        val assets = listOf(
            GitHubAssetDto(
                name = "andy-2026.0811.0422-macos-arm64",
                browserDownloadUrl = "https://example.test/andy",
                state = "uploaded",
            ),
            GitHubAssetDto(
                name = "andyd-2026.0811.0422-macos-arm64.jar",
                browserDownloadUrl = "https://example.test/andyd.jar",
                state = "uploaded",
            ),
            GitHubAssetDto(
                name = "tmux-2026.0811.0422-macos-arm64",
                browserDownloadUrl = "https://example.test/tmux",
                state = "uploaded",
            ),
            GitHubAssetDto(
                name = "andy-status-hook.sh",
                browserDownloadUrl = "https://example.test/hook",
                state = "uploaded",
            ),
        )
        val target = "macos-arm64"
        assertEquals(
            "andy-2026.0811.0422-macos-arm64",
            findAsset(assets, "^andy-.+-${Regex.escape(target)}$".toRegex())?.name,
        )
        assertEquals(
            "andyd-2026.0811.0422-macos-arm64.jar",
            findAsset(assets, "^andyd-.+-${Regex.escape(target)}\\.jar$".toRegex())?.name,
        )
        assertEquals(
            "tmux-2026.0811.0422-macos-arm64",
            findAsset(assets, "^tmux-.+-${Regex.escape(target)}$".toRegex())?.name,
        )
        assertEquals(
            "andy-status-hook.sh",
            findAsset(assets, "^andy-status-hook\\.sh$".toRegex())?.name,
        )
    }

    @Test
    fun findAssetSkipsNonUploaded() {
        val assets = listOf(
            GitHubAssetDto(
                name = "andy-1.0.0-linux-x86_64",
                browserDownloadUrl = "https://example.test/andy",
                state = "starter",
            ),
        )
        assertNull(findAsset(assets, "^andy-.+-linux-x86_64$".toRegex()))
    }

    @Test
    fun parseGitHubReleaseReadsTagAndAssets() {
        val json = """
            {
              "tag_name": "v2026.0811.0422",
              "html_url": "https://github.com/j-roskopf/Andy/releases/tag/v2026.0811.0422",
              "assets": [
                {
                  "name": "andy-2026.0811.0422-macos-arm64",
                  "browser_download_url": "https://example.test/andy",
                  "size": 12,
                  "state": "uploaded"
                }
              ]
            }
        """.trimIndent()
        val release = parseGitHubRelease(json)
        assertEquals("v2026.0811.0422", release.tagName)
        assertEquals(1, release.assets.size)
        assertEquals("andy-2026.0811.0422-macos-arm64", release.assets[0].name)
        assertEquals("2026.0811.0422", normalizeReleaseVersion(release.tagName))
        assertTrue(isNewerRelease(installed = "2026.0801.0000", latest = "2026.0811.0422"))
        assertTrue(!isNewerRelease(installed = "2026.0811.0422", latest = "2026.0811.0422"))
    }

    @Test
    fun detectRuntimeInstallTargetRecognizesCommonHosts() {
        // Just ensure the helper returns a known shape when it can detect something;
        // CI may run on macOS arm64 or Linux x86_64.
        val target = detectRuntimeInstallTarget()
        if (target != null) {
            assertTrue(target == "macos-arm64" || target == "linux-x86_64", "unexpected target $target")
        }
        assertNotNull(INSTALLED_RELEASE_FILE)
    }
}
