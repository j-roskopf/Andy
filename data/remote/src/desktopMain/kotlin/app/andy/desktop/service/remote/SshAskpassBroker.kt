package app.andy.desktop.service.remote

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * In-memory SSH password / passphrase cache for the Andy process lifetime, with optional
 * OS keychain fill-in via [prepareTarget] / [lastSecretFor].
 * Askpass subprocesses query a unix socket; secrets never go into workspace prefs.
 */
object SshAskpassBroker {
    private val passwords = ConcurrentHashMap<String, String>()
    private val lastSecretByTarget = ConcurrentHashMap<String, String>()
    private val activeTarget = AtomicReference<String?>(null)
    private val keychainFallback = AtomicReference<String?>(null)
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var server: ServerSocketChannel? = null
    private var socketFile: File? = null

    fun socketPath(): File? = socketFile

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Keep under /tmp — java.io.tmpdir on macOS is too long for AF_UNIX.
        val file = File("/tmp", "andy-ap-${ProcessHandle.current().pid()}.sock")
        file.delete()
        socketFile = file
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        channel.bind(UnixDomainSocketAddress.of(file.toPath()))
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        server = channel
        thread(name = "andy-ssh-askpass", isDaemon = true) {
            while (true) {
                val client = runCatching { channel.accept() }.getOrNull() ?: break
                runCatching { handleClient(client) }
            }
        }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                clear()
                runCatching { channel.close() }
                file.delete()
            },
        )
    }

    /** Load any keychain secret for [target] and reset cancel state for a new connect attempt. */
    fun prepareTarget(target: String) {
        val trimmed = target.trim()
        activeTarget.set(trimmed)
        cancelled.set(false)
        keychainFallback.set(SshCredentialStore.load(trimmed))
    }

    fun clearActiveTarget() {
        activeTarget.set(null)
        keychainFallback.set(null)
    }

    fun wasCancelled(): Boolean = cancelled.get()

    fun lastSecretFor(target: String): String? = lastSecretByTarget[target.trim()]

    fun clear() {
        passwords.clear()
        lastSecretByTarget.clear()
        clearActiveTarget()
        cancelled.set(false)
    }

    fun forget(targetHint: String) {
        val needle = targetHint.lowercase()
        passwords.keys.filter { it.contains(needle) }.forEach { passwords.remove(it) }
        lastSecretByTarget.keys.filter { it.contains(needle) }.forEach { lastSecretByTarget.remove(it) }
        if (activeTarget.get()?.contains(needle, ignoreCase = true) == true) {
            clearActiveTarget()
        }
    }

    private fun handleClient(client: SocketChannel) {
        client.use { ch ->
            val prompt = readLine(ch)?.trim().orEmpty()
            if (prompt.isEmpty()) return
            // After Cancel, ssh may ask again — never show another dialog for this attempt.
            if (cancelled.get()) {
                ch.write(ByteBuffer.wrap("\n".toByteArray(StandardCharsets.UTF_8)))
                return
            }
            val key = cacheKey(prompt)
            val cached = passwords[key]
            val fromKeychain = if (cached == null) keychainFallback.getAndSet(null) else null
            val typed = if (cached == null && fromKeychain == null) {
                promptForSecret(prompt)
            } else {
                null
            }
            if (cached == null && fromKeychain == null && typed == null) {
                cancelled.set(true)
                ch.write(ByteBuffer.wrap("\n".toByteArray(StandardCharsets.UTF_8)))
                return
            }
            val secret = cached ?: fromKeychain ?: typed.orEmpty()
            if (secret.isNotEmpty()) {
                passwords[key] = secret
                activeTarget.get()?.let { lastSecretByTarget[it] = secret }
            }
            val out = (secret + "\n").toByteArray(StandardCharsets.UTF_8)
            ch.write(ByteBuffer.wrap(out))
        }
    }

    private fun cacheKey(prompt: String): String = prompt.trim().lowercase()

    private fun promptForSecret(prompt: String): String? {
        val escaped = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val cmd = if (os.contains("mac") || os.contains("darwin")) {
            listOf(
                "/usr/bin/osascript",
                "-e",
                "tell application \"System Events\" to activate",
                "-e",
                "text returned of (display dialog \"$escaped\" default answer \"\" with hidden answer " +
                    "buttons {\"Cancel\", \"OK\"} default button \"OK\" with title \"Andy SSH\")",
            )
        } else {
            when {
                commandExists("ssh-askpass") -> listOf("ssh-askpass", prompt)
                commandExists("zenity") -> listOf("zenity", "--password", "--title=Andy SSH", "--text=$prompt")
                commandExists("kdialog") -> listOf("kdialog", "--password", prompt)
                else -> return null
            }
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        return out.takeIf { code == 0 && it.isNotEmpty() }
    }

    private fun commandExists(name: String): Boolean =
        runCatching {
            ProcessBuilder("which", name).start().waitFor() == 0
        }.getOrDefault(false)

    private fun readLine(channel: SocketChannel): String? {
        val buffer = ByteBuffer.allocate(4096)
        val builder = StringBuilder()
        while (true) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) break
            buffer.flip()
            while (buffer.hasRemaining()) {
                val c = buffer.get().toInt().toChar()
                if (c == '\n') return builder.toString()
                if (c != '\r') builder.append(c)
            }
            if (builder.length > 4000) break
        }
        return builder.toString().takeIf { it.isNotEmpty() }
    }
}
