package com.joetr.andy.mobile.data.updates

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubReleaseUpdateRepositoryTest {
    @Test
    fun semanticVersionParsesReleaseTagsAndCompares() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("release/1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("v1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3+45"))
        assertEquals(
            SemanticVersion(2026, 906, 1358),
            SemanticVersion.parse("release/2026.0906.1358"),
        )
        assertTrue(SemanticVersion.parse("1.2.4")!! > SemanticVersion.parse("1.2.3")!!)
        assertTrue(
            SemanticVersion.parse("2026.0906.1358")!! >
                SemanticVersion.parse("2026.0905.0628")!!,
        )
        assertNull(SemanticVersion.parse("release/1.2"))
    }

    @Test
    fun normalizesOnlyGithubSha256Digests() {
        val digest = "A".repeat(64)
        assertEquals(digest.lowercase(), "sha256:$digest".normalizedSha256Digest())
        assertNull("sha512:$digest".normalizedSha256Digest())
        assertNull("sha256:not-a-digest".normalizedSha256Digest())
    }

    @Test
    fun selectsUploadedApkAsset() {
        val assets = listOf(
            GitHubAssetDto("Andy-1.2.3.dmg", "https://example.test/andy.dmg", state = "uploaded"),
            GitHubAssetDto("Andy-1.2.3.apk", "https://example.test/andy.apk", size = 42, digest = "sha256:${"b".repeat(64)}", state = "uploaded"),
            GitHubAssetDto("Andy-1.2.3.zip", "https://example.test/andy.zip", state = "new"),
        )

        val apk = selectAndroidApkAsset(assets)
        assertNotNull(apk)
        assertEquals("Andy-1.2.3.apk", apk!!.name)
        assertEquals("b".repeat(64), apk.sha256Digest)
        assertNull(selectAndroidApkAsset(assets.filterNot { it.name.endsWith(".apk") }))
    }

    @Test
    fun latestStableReleaseReturnsAvailableUpdate() = runTest {
        val digest = "b".repeat(64)
        val repository = GitHubReleaseUpdateRepository(
            httpClient = testHttpClient(
                MockEngine { request ->
                    assertEquals("/repos/j-roskopf/Andy/releases/latest", request.url.encodedPath)
                    assertEquals("Andy/2026.0905.0628", request.headers[HttpHeaders.UserAgent])
                    respondJson(
                        """
                        {
                          "tag_name": "release/2026.0906.1358",
                          "html_url": "https://github.com/j-roskopf/Andy/releases/tag/release/2026.0906.1358",
                          "name": "Andy 2026.0906.1358",
                          "body": "Mobile companion fixes",
                          "draft": false,
                          "prerelease": false,
                          "assets": [
                            {
                              "name": "Andy-2026.0906.1358.apk",
                              "browser_download_url": "https://example.test/Andy-2026.0906.1358.apk",
                              "size": 42,
                              "digest": "sha256:$digest",
                              "state": "uploaded"
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                },
            ),
            currentVersionName = "2026.0905.0628",
            githubOwner = "j-roskopf",
            githubRepo = "Andy",
        )

        val update = repository.checkForUpdate()
        assertNotNull(update)

        assertEquals("2026.0906.1358", update!!.versionName)
        assertEquals("Andy 2026.0906.1358", update.releaseName)
        assertEquals("Mobile companion fixes", update.releaseNotes)
        assertEquals("Andy-2026.0906.1358.apk", update.asset?.name)
        assertEquals(digest, update.asset?.sha256Digest)
    }

    @Test
    fun latestReleaseReturnsNullWhenCurrentOrPrerelease() = runTest {
        val currentRepository = repositoryForJson(
            currentVersionName = "2026.0906.1358",
            json = """
                {
                  "tag_name": "release/2026.0906.1358",
                  "html_url": "https://github.com/j-roskopf/Andy/releases/tag/release/2026.0906.1358",
                  "draft": false,
                  "prerelease": false,
                  "assets": []
                }
            """.trimIndent(),
        )
        val prereleaseRepository = repositoryForJson(
            currentVersionName = "2026.0905.0628",
            json = """
                {
                  "tag_name": "release/2026.0906.1358",
                  "html_url": "https://github.com/j-roskopf/Andy/releases/tag/release/2026.0906.1358",
                  "draft": false,
                  "prerelease": true,
                  "assets": []
                }
            """.trimIndent(),
        )

        assertNull(currentRepository.checkForUpdate())
        assertNull(prereleaseRepository.checkForUpdate())
    }

    @Test
    fun latestReleaseHttpFailureIsReported() = runTest {
        val repository = GitHubReleaseUpdateRepository(
            httpClient = testHttpClient(MockEngine { respond("rate limited", HttpStatusCode.Forbidden) }),
            currentVersionName = "2026.0905.0628",
        )

        try {
            repository.checkForUpdate()
            fail("Expected HTTP failure")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("HTTP 403"))
        }
    }

    private fun repositoryForJson(
        currentVersionName: String,
        json: String,
    ): GitHubReleaseUpdateRepository =
        GitHubReleaseUpdateRepository(
            httpClient = testHttpClient(MockEngine { respondJson(json) }),
            currentVersionName = currentVersionName,
        )
}

private fun MockRequestHandleScope.respondJson(content: String) = respond(
    content = content,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun testHttpClient(engine: MockEngine): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
    }
