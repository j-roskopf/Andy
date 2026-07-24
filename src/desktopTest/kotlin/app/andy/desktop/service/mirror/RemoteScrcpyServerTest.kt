package app.andy.desktop.service.mirror

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteScrcpyServerTest {
    @Test
    fun matchesPlainStatSizeOutput() {
        assertTrue(remoteScrcpyServerSizeMatches(731_648L, "731648\n"))
        assertTrue(remoteScrcpyServerSizeMatches(731_648L, "731648"))
    }

    @Test
    fun matchesWcStyleSizeOutput() {
        assertTrue(
            remoteScrcpyServerSizeMatches(
                731_648L,
                "731648 /data/local/tmp/scrcpy-server-andy.jar\n",
            ),
        )
    }

    @Test
    fun rejectsMismatchedMissingOrGarbageSizes() {
        assertFalse(remoteScrcpyServerSizeMatches(731_648L, "731647\n"))
        assertFalse(remoteScrcpyServerSizeMatches(731_648L, ""))
        assertFalse(remoteScrcpyServerSizeMatches(731_648L, "stat: No such file or directory\n"))
        assertFalse(remoteScrcpyServerSizeMatches(0L, "0\n"))
        assertFalse(remoteScrcpyServerSizeMatches(731_648L, "not-a-number\n"))
    }
}
