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

    /**
     * Tri-state lookup so a locked/unavailable store is never confused with a confirmed absence.
     * Without this, a failed lookup would make [delete] report success while the secret remains.
     */
    private sealed interface Lookup {
        data class Found(val value: String) : Lookup
        data object Absent : Lookup
        data object Unavailable : Lookup
    }

    fun load(): String? = (lookup() as? Lookup.Found)?.value

    fun save(secret: String) {
        if (secret.isEmpty()) return
        when {
            isMac() -> macAdd(secret)
            isLinux() -> linuxStore(secret)
            isWindows() -> runCatching { windowsStore(secret) }
        }
    }

    /** Removes the stored key. Returns true only when a follow-up lookup confirms absence. */
    fun delete(): Boolean {
        when {
            isMac() -> macDelete()
            isLinux() -> linuxClear()
            isWindows() -> windowsDelete()
        }
        return lookup() is Lookup.Absent
    }

    fun isPresent(): Boolean = lookup() is Lookup.Found

    private fun lookup(): Lookup = when {
        isMac() -> macLookup()
        isLinux() -> linuxLookup()
        isWindows() -> windowsLookup()
        else -> Lookup.Absent
    }

    private fun isMac(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return os.contains("mac") || os.contains("darwin")
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun macLookup(): Lookup {
        val process = ProcessBuilder(
            "security", "find-generic-password",
            "-s", ServiceName,
            "-a", Account,
            "-w",
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) return Lookup.Unavailable
        val value = process.inputStream.bufferedReader().readText().trim()
        return when {
            process.exitValue() == 0 -> if (value.isNotEmpty()) Lookup.Found(value) else Lookup.Absent
            // 44 = errSecItemNotFound; anything else (locked keychain, denied access) is unavailable.
            process.exitValue() == 44 -> Lookup.Absent
            else -> Lookup.Unavailable
        }
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

    private fun linuxLookup(): Lookup {
        if (!commandExists("secret-tool")) return Lookup.Unavailable
        val process = ProcessBuilder(
            "secret-tool", "lookup",
            "service", ServiceName,
            "account", Account,
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) return Lookup.Unavailable
        // stderr is merged into the stream, so a non-blank payload on a failed exit is a D-Bus or
        // Secret Service error (Unavailable); a silent non-zero exit is the no-match case (Absent).
        val value = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0) {
            return if (value.isNotEmpty()) Lookup.Unavailable else Lookup.Absent
        }
        return if (value.isNotEmpty()) Lookup.Found(value) else Lookup.Absent
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

    private fun windowsLookup(): Lookup {
        if (!windowsFile().isFile) return Lookup.Absent
        return runCatching {
            val value = windowsLoad()
            if (value.isNullOrBlank()) Lookup.Absent else Lookup.Found(value)
        }.getOrElse { Lookup.Unavailable }
    }

    internal fun windowsLoad(): String? {
        val file = windowsFile()
        if (!file.isFile) return null
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Security
            ${'$'}enc = [IO.File]::ReadAllBytes('${powerShellLiteral(file.absolutePath)}')
            ${'$'}bytes = [Security.Cryptography.ProtectedData]::Unprotect(${'$'}enc, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            [Console]::Out.Write([Text.Encoding]::UTF8.GetString(${'$'}bytes))
        """.trimIndent()
        val output = runPowerShell(script, emptyMap())
        return output.trim().takeIf { it.isNotEmpty() }
    }

    internal fun windowsStore(secret: String) {
        val file = windowsFile()
        file.parentFile?.mkdirs()
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Security
            ${'$'}bytes = [Text.Encoding]::UTF8.GetBytes(${'$'}env:$WindowsKeyEnv)
            ${'$'}enc = [Security.Cryptography.ProtectedData]::Protect(${'$'}bytes, ${'$'}null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            [IO.File]::WriteAllBytes('${powerShellLiteral(file.absolutePath)}', ${'$'}enc)
        """.trimIndent()
        runPowerShell(script, mapOf(WindowsKeyEnv to secret))
    }

    private fun windowsDelete() {
        runCatching { windowsFile().delete() }
    }

    /** Runs a PowerShell script, returning stdout. Throws with stderr on non-zero/timeout. */
    private fun runPowerShell(script: String, environment: Map<String, String>): String {
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
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("PowerShell timed out")
        }
        outThread.join(1_000)
        errThread.join(1_000)
        if (process.exitValue() != 0) {
            throw IllegalStateException("PowerShell exit ${process.exitValue()}: ${err.toString().trim()}")
        }
        return out.toString()
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
