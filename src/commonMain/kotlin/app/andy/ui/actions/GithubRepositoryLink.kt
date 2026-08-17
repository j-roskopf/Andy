package app.andy.ui.actions

internal expect suspend fun detectGithubRepositoryUrl(contextDir: String): String?

internal fun githubRepositoryUrl(remoteUrl: String): String? {
    val remote = remoteUrl.trim().removeSuffix("/")
    val path = when {
        remote.startsWith("git@github.com:") -> remote.removePrefix("git@github.com:")
        remote.startsWith("ssh://git@github.com/") -> remote.removePrefix("ssh://git@github.com/")
        remote.startsWith("https://github.com/") -> remote.removePrefix("https://github.com/")
        remote.startsWith("http://github.com/") -> remote.removePrefix("http://github.com/")
        remote.startsWith("git://github.com/") -> remote.removePrefix("git://github.com/")
        else -> return null
    }.removeSuffix(".git")

    val segments = path.split('/')
    if (segments.size != 2 || segments.any { it.isBlank() }) return null
    return "https://github.com/${segments[0]}/${segments[1]}"
}
