package app.andy.desktop.service.remote

import java.util.concurrent.TimeUnit

/**
 * OS keychain / secret-service storage for optional SSH passwords.
 * Never writes secrets into workspace prefs — only the target string lives there.
 */
object SshCredentialStore {
    private const val ServiceName = "Andy SSH"

    fun load(target: String): String? {
        val account = normalize(target) ?: return null
        return when {
            isMac() -> macFind(account)
            isLinux() -> linuxLookup(account)
            else -> null
        }
    }

    fun save(target: String, secret: String) {
        val account = normalize(target) ?: return
        if (secret.isEmpty()) return
        when {
            isMac() -> macAdd(account, secret)
            isLinux() -> linuxStore(account, secret)
        }
    }

    fun delete(target: String) {
        val account = normalize(target) ?: return
        when {
            isMac() -> macDelete(account)
            isLinux() -> linuxClear(account)
        }
    }

    internal fun normalize(target: String): String? =
        target.trim().takeIf { it.isNotEmpty() }?.lowercase()

    private fun isMac(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return os.contains("mac") || os.contains("darwin")
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private fun macFind(account: String): String? {
        val process = ProcessBuilder(
            "security", "find-generic-password",
            "-s", ServiceName,
            "-a", account,
            "-w",
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }

    private fun macAdd(account: String, secret: String) {
        // -U updates an existing item; ignore failure if security is unavailable.
        runCatching {
            val process = ProcessBuilder(
                "security", "add-generic-password",
                "-s", ServiceName,
                "-a", account,
                "-w", secret,
                "-U",
            ).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun macDelete(account: String) {
        runCatching {
            val process = ProcessBuilder(
                "security", "delete-generic-password",
                "-s", ServiceName,
                "-a", account,
            ).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun linuxLookup(account: String): String? {
        if (!commandExists("secret-tool")) return null
        val process = ProcessBuilder(
            "secret-tool", "lookup",
            "service", ServiceName,
            "account", account,
        ).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }

    private fun linuxStore(account: String, secret: String) {
        if (!commandExists("secret-tool")) return
        runCatching {
            val process = ProcessBuilder(
                "secret-tool", "store",
                "--label=Andy SSH $account",
                "service", ServiceName,
                "account", account,
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { out ->
                out.write(secret)
                out.flush()
            }
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun linuxClear(account: String) {
        if (!commandExists("secret-tool")) return
        runCatching {
            val process = ProcessBuilder(
                "secret-tool", "clear",
                "service", ServiceName,
                "account", account,
            ).redirectErrorStream(true).start()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun commandExists(name: String): Boolean =
        runCatching {
            ProcessBuilder("which", name).start().waitFor() == 0
        }.getOrDefault(false)
}
