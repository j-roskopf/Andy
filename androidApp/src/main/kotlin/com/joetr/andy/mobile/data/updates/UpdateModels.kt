package com.joetr.andy.mobile.data.updates

import app.andy.service.ReleaseAsset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long = 0L,
    val digest: String? = null,
    val state: String? = null,
) {
    fun toReleaseAsset(): ReleaseAsset =
        ReleaseAsset(
            name = name,
            downloadUrl = browserDownloadUrl,
            sizeBytes = size,
            sha256Digest = digest.normalizedSha256Digest(),
        )
}

internal data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VersionPattern = Regex("""(?:release/|v)?([0-9]+)\.([0-9]+)\.([0-9]+)(?:[-+].*)?""")

        fun parse(value: String?): SemanticVersion? {
            val match = VersionPattern.matchEntire(value?.trim().orEmpty()) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].toIntOrNull() ?: return null,
                patch = match.groupValues[3].toIntOrNull() ?: return null,
            )
        }
    }
}

internal fun selectAndroidApkAsset(assets: List<GitHubAssetDto>): ReleaseAsset? {
    val uploadedAssets = assets.filter { asset ->
        asset.browserDownloadUrl.isNotBlank() &&
            asset.state?.equals("uploaded", ignoreCase = true) != false
    }
    return uploadedAssets
        .firstOrNull { asset -> asset.name.endsWith(".apk", ignoreCase = true) }
        ?.toReleaseAsset()
}

internal fun String?.normalizedSha256Digest(): String? {
    val normalized = this?.trim()?.lowercase()?.removePrefix("sha256:") ?: return null
    return normalized.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
}

sealed interface UpdateInstallResult {
    val message: String

    data class Started(override val message: String) : UpdateInstallResult
    data class OpenedReleasePage(override val message: String) : UpdateInstallResult
    data class RequiresUserAction(override val message: String) : UpdateInstallResult
}
