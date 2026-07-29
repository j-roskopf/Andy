package app.andy.desktop.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginShellEnvironmentTest {
    private val nul = '\u0000'

    @Test
    fun parseMarkedEnvExtractsOnlyTheDelimitedBlock() {
        val raw = "some motd noise\n" +
            "__BEGIN__\nPATH=/usr/bin${nul}JAVA_HOME=/opt/jdk${nul}__END__\ntrailing noise"

        val parsed = LoginShellEnvironment.parseMarkedEnv(raw, "__BEGIN__", "__END__")

        assertEquals("/usr/bin", parsed["PATH"])
        assertEquals("/opt/jdk", parsed["JAVA_HOME"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun parseMarkedEnvHandlesValuesContainingEqualsSigns() {
        val raw = "__BEGIN__\nSOME_VAR=a=b=c${nul}__END__"

        val parsed = LoginShellEnvironment.parseMarkedEnv(raw, "__BEGIN__", "__END__")

        assertEquals("a=b=c", parsed["SOME_VAR"])
    }

    @Test
    fun parseMarkedEnvReturnsEmptyWhenMarkersMissing() {
        assertTrue(LoginShellEnvironment.parseMarkedEnv("no markers here", "__BEGIN__", "__END__").isEmpty())
        assertTrue(LoginShellEnvironment.parseMarkedEnv("__BEGIN__ but no end", "__BEGIN__", "__END__").isEmpty())
    }

    @Test
    fun parseMarkedEnvIgnoresEntriesWithoutEquals() {
        val raw = "__BEGIN__\nMALFORMED${nul}PATH=/usr/bin${nul}__END__"

        val parsed = LoginShellEnvironment.parseMarkedEnv(raw, "__BEGIN__", "__END__")

        assertEquals(1, parsed.size)
        assertEquals("/usr/bin", parsed["PATH"])
    }

    @Test
    fun captureReturnsEmptyMapOnWindows() {
        val result = LoginShellEnvironment.capture(
            shell = "/bin/zsh",
            osName = "Windows 11",
            runShell = { error("must not shell out on Windows") },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun captureReturnsEmptyMapWhenShellFails() {
        val result = LoginShellEnvironment.capture(
            shell = "/bin/zsh",
            osName = "Mac OS X",
            runShell = { null },
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun captureInvokesShellInteractivelyAndParsesOutput() {
        var invokedCommand: List<String>? = null
        val result = LoginShellEnvironment.capture(
            shell = "/bin/zsh",
            osName = "Mac OS X",
            runShell = { command ->
                invokedCommand = command
                "__ANDY_ENV_BEGIN__\nPATH=/opt/homebrew/bin:/usr/bin${nul}JAVA_HOME=/opt/jdk${nul}__ANDY_ENV_END__\n"
            },
        )
        assertEquals(listOf("/bin/zsh", "-ilc"), invokedCommand?.take(2))
        assertEquals("/opt/homebrew/bin:/usr/bin", result["PATH"])
        assertEquals("/opt/jdk", result["JAVA_HOME"])
    }
}
