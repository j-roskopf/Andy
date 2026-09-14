package app.andy.desktop.service.agents

import java.util.concurrent.TimeUnit

/**
 * OS keychain / secret-service storage for the OpenRouter API key.
 * Never writes the secret into workspace prefs — only [WorkspaceState.openRouterBaseUrl] lives there.
 */
object OpenRouterCredentialStore {
    private const val ServiceName = "Andy OpenRouter"
    private const val Account = "api-key"

    fun load(): String? = when {
        isMac() -> macFind()
        isLinux() -> linuxLookup()
        else -> null
    }

    fun save(secret: String) {
        if (secret.isEmpty()) return
        when {
            isMac() -> macAdd(secret)
            isLinux() -> linuxStore(secret)
        }
    }

    /** Removes the stored key. Returns true only when it is verifiably gone afterwards. */
    fun delete(): Boolean {
        when {
            isMac() -> macDelete()
            isLinux() -> linuxClear()
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
}
