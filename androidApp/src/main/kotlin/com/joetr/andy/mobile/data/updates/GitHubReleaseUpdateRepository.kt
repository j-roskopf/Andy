package com.joetr.andy.mobile.data.updates

import app.andy.service.AvailableUpdate
import app.andy.updates.AndyBuildInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

class GitHubReleaseUpdateRepository(
    private val httpClient: HttpClient,
    private val currentVersionName: String = AndyBuildInfo.versionName,
    private val githubOwner: String = AndyBuildInfo.githubOwner,
    private val githubRepo: String = AndyBuildInfo.githubRepo,
) {
    suspend fun checkForUpdate(): AvailableUpdate? {
        val currentVersion = SemanticVersion.parse(currentVersionName) ?: return null
        val response = latestReleaseResponse()
        if (!response.status.isSuccess()) {
            error("GitHub release check failed: HTTP ${response.status.value}")
        }
        val release = response.body<GitHubReleaseDto>()
        if (release.draft || release.prerelease) return null
        val latestVersion = SemanticVersion.parse(release.tagName) ?: return null
        if (latestVersion <= currentVersion) return null

        val displayVersion = release.tagName
            .trim()
            .removePrefix("release/")
            .removePrefix("v")

        return AvailableUpdate(
            versionName = displayVersion.ifBlank { latestVersion.toString() },
            releaseName = release.name,
            releaseNotes = release.body,
            releasePageUrl = release.htmlUrl,
            asset = selectAndroidApkAsset(release.assets),
        )
    }

    private suspend fun latestReleaseResponse(): HttpResponse =
        httpClient.get("$GitHubApiBase/repos/$githubOwner/$githubRepo/releases/latest") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "Andy/$currentVersionName")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
}

private const val GitHubApiBase = "https://api.github.com"
