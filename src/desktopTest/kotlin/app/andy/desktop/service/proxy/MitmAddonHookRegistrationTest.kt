package app.andy.desktop.service.proxy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume.assumeTrue

/**
 * Fast CI guard: the addon must import and expose all hooks under a supported
 * mitmproxy (pinned venv preferred, system fallback in range).
 */
class MitmAddonHookRegistrationTest {
    @Test
    fun mitmVersionParsingAcceptsSupportedRange() {
        assertNotNull(MitmRuntime.MitmVersion.parse("Mitmproxy: 12.2.3 binary"))
        assertTrue(MitmRuntime.MitmVersion.parse("10.4.2")!! >= MitmRuntime.MIN_SUPPORTED_VERSION)
        assertTrue(MitmRuntime.MitmVersion.parse("12.2.3")!! <= MitmRuntime.MAX_SUPPORTED_VERSION)
        assertTrue(MitmRuntime.MitmVersion.parse("9.0.0")!! < MitmRuntime.MIN_SUPPORTED_VERSION)
    }

    @Test
    fun addonImportsAndRegistersHooksUnderSupportedMitmproxy() {
        val resolved = MitmRuntime.resolveMitmdump(provisionIfNeeded = true)
        assumeTrue(
            "Skipping: no supported mitmdump (${resolved.message})",
            resolved.executable != null,
        )

        val python = resolvePythonFor(resolved.executable!!)
        assumeTrue("Skipping: could not locate python for ${resolved.executable}", python != null)

        val addon = File.createTempFile("andy-mitm-addon", ".py")
        try {
            addon.writeBytes(MitmAddon.getAddonSource())
            val script = """
                import importlib.util
                import sys
                path = ${pythonString(addon.absolutePath)}
                spec = importlib.util.spec_from_file_location("andy_mitm_addon", path)
                mod = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                required = [
                    "load", "request", "response", "error",
                    "client_connected", "client_disconnected",
                    "tls_failed_client", "tls_clienthello", "server_connect",
                ]
                missing = [name for name in required if not hasattr(mod, name)]
                if missing:
                    raise SystemExit("missing hooks: " + ",".join(missing))
                if not callable(getattr(mod, "tls_clienthello")):
                    raise SystemExit("tls_clienthello not callable")
                print("hooks-ok")
            """.trimIndent()
            val process = ProcessBuilder(python!!, "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0 || !output.contains("hooks-ok")) {
                fail("addon import/hook registration failed ($code): $output")
            }
        } finally {
            addon.delete()
        }
    }

    private fun resolvePythonFor(mitmdump: String): String? {
        val mitmdumpFile = File(mitmdump)
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val siblingCandidates = if (isWindows) {
            listOf(File(mitmdumpFile.parentFile, "python.exe"))
        } else {
            listOf(
                File(mitmdumpFile.parentFile, "python"),
                File(mitmdumpFile.parentFile, "python3"),
            )
        }
        return siblingCandidates
            .firstOrNull { it.isFile && (isWindows || it.canExecute()) }
            ?.absolutePath
            ?: MitmRuntime.findSuitablePython()
    }

    private fun pythonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
