package app.andy.ui.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GithubRepositoryLinkTest {
    @Test
    fun convertsSupportedGithubRemotesToBrowserUrls() {
        assertEquals("https://github.com/acme/widget", githubRepositoryUrl("git@github.com:acme/widget.git"))
        assertEquals("https://github.com/acme/widget", githubRepositoryUrl("ssh://git@github.com/acme/widget.git"))
        assertEquals("https://github.com/acme/widget", githubRepositoryUrl("https://github.com/acme/widget.git"))
        assertEquals("https://github.com/acme/widget", githubRepositoryUrl("git://github.com/acme/widget"))
    }

    @Test
    fun rejectsNonGithubAndMalformedRemotes() {
        assertNull(githubRepositoryUrl("git@gitlab.com:acme/widget.git"))
        assertNull(githubRepositoryUrl("https://github.com/acme"))
        assertNull(githubRepositoryUrl("https://github.com/acme/widget/issues"))
    }
}
