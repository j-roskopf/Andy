package app.andy.desktop.service.agents

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OS keychain / secret-service storage for the OpenRouter API key.
 * Never writes the secret into workspace prefs — only [WorkspaceState.openRouterBaseUrl] lives there.
 * On Windows the key is DPAPI-encrypted (user-scoped) under `~/.andy`; macOS uses Keychain and
 * Linux uses the Secret Service.
 */
object OpenRouterCredentialStore {
    private const val ServiceName = "Andy OpenRouter"
    private const val Account = "api-key"
    private const val WindowsKeyEnv = "ANDY_OPENROUTER_KEY"

    fun load(): String? = when {
        isMac() -> macFind()
        isLinux() -> linuxLookup()
        isWindows() -> windowsLoad()
        else -> null
    }

    fun save(secret: String) {
        if (secret.isEmpty()) return
        when {
            isMac() -> macAdd(secret)
            isLinux() -> linuxStore(secret)
            isWindows() -> windowsStore(secret)
        }
    }

    /** Removes the stored key. Returns true only when it is verifiably gone afterwards. */
    fun delete(): Boolean {
        when {
            isMac() -> macDelete()
            isLinux() -> linuxClear()
            isWindows() -> windowsDelete()
        }
        return !isPresent()
    }

    fun isPresent(): Boolean = !load().isNullOrBlank()

    private fun isMac(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return os.contains("mac") || os.contains("darwin")
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun macFind(): String? {
        val process = ProcessBuilder(
            "security", "find-generic-password",
            "-s", ServiceName,
            "-a", Account,
            "-w",
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }

    private fun macAdd(secret: String) {
        runCatching {
            val process = ProcessBuilder(
                "security", "add-generic-password",
                "-s", ServiceName,
                "-a", Account,
                "-w", secret,
                "-U",
            ).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun macDelete() {
        runCatching {
            val process = ProcessBuilder(
                "security", "delete-generic-password",
                "-s", ServiceName,
                "-a", Account,
            ).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun linuxLookup(): String? {
        if (!commandExists("secret-tool")) return null
        val process = ProcessBuilder(
            "secret-tool", "lookup",
            "service", ServiceName,
            "account", Account,
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }

    private fun linuxStore(secret: String) {
        if (!commandExists("secret-tool")) return
        runCatching {
            val process = ProcessBuilder(
                "secret-tool", "store",
                "--label=Andy OpenRouter API key",
                "service", ServiceName,
                "account", Account,
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { out ->
                out.write(secret)
                out.flush()
            }
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun linuxClear() {
        if (!commandExists("secret-tool")) return
        runCatching {
            val process = ProcessBuilder(
                "secret-tool", "clear",
                "service", ServiceName,
                "account", Account,
            ).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun commandExists(name: String): Boolean =
        runCatching {
            ProcessBuilder("which", name).start().waitFor() == 0
        }.getOrDefault(false)

    // Windows: DPAPI-encrypted (current-user) SecureString via PowerShell's Export/Import-Clixml.
    // Scripts are passed with -EncodedCommand (UTF-16LE base64) so no shell quoting is involved.

    private fun windowsFile(): File =
        File(System.getProperty("user.home"), ".andy/openrouter-key.dpapi")

    private fun windowsLoad(): String? {
        val file = windowsFile()
        if (!file.isFile) return null
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}sec = Import-Clixml -LiteralPath '${powerShellLiteral(file.absolutePath)}'
            [Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR(${'$'}sec)))
        """.trimIndent()
        val output = runPowerShell(script, emptyMap()) ?: return null
        return output.trim().takeIf { it.isNotEmpty() }
    }

    private fun windowsStore(secret: String) {
        val file = windowsFile()
        file.parentFile?.mkdirs()
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}sec = ConvertTo-SecureString -String ${'$'}env:$WindowsKeyEnv -AsPlainText -Force
            ${'$'}sec | Export-Clixml -LiteralPath '${powerShellLiteral(file.absolutePath)}'
        """.trimIndent()
        runPowerShell(script, mapOf(WindowsKeyEnv to secret))
    }

    private fun windowsDelete() {
        runCatching { windowsFile().delete() }
    }

    /** Runs a PowerShell script, returning stdout, or null on failure/timeout. */
    private fun runPowerShell(script: String, environment: Map<String, String>): String? = runCatching {
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val builder = ProcessBuilder(
            powerShellExecutable(), "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded,
        )
        builder.environment().putAll(environment)
        val process = builder.start()
        // Drain stdout/err concurrently so a full pipe cannot deadlock waitFor.
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val errThread = Thread { process.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
        outThread.start()
        errThread.start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        outThread.join(1_000)
        errThread.join(1_000)
        if (process.exitValue() != 0) {
            System.err.println("OpenRouterCredentialStore: PowerShell exited ${process.exitValue()}: ${err.toString().trim()}")
            return@runCatching null
        }
        out.toString()
    }.getOrElse {
        System.err.println("OpenRouterCredentialStore: PowerShell invocation failed: ${it.message}")
        null
    }

    private fun powerShellExecutable(): String {
        val root = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() }
            ?: System.getenv("WINDIR")?.takeIf { it.isNotBlank() }
        if (root != null) {
            val exe = File(root, "System32/WindowsPowerShell/v1.0/powershell.exe")
            if (exe.isFile) return exe.absolutePath
        }
        return "powershell"
    }

    private fun powerShellLiteral(path: String): String = path.replace("'", "''")
}
