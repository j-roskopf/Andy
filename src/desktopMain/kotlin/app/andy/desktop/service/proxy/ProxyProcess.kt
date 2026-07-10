package app.andy.desktop.service.proxy

import app.andy.desktop.service.CommandRunner
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

interface ProxyProcess {
    val stdout: InputStream
    val stderr: InputStream
    fun isAlive(): Boolean
    fun destroy()
}

class RealProxyProcess(command: List<String>, directory: File, environment: Map<String, String>) : ProxyProcess {
    private val delegate = ProcessBuilder(command)
        .directory(directory)
        .redirectErrorStream(false)
        .also { builder -> builder.environment().putAll(environment) }
        .start()

    private val shutdownHook = Thread {
        try {
            if (delegate.isAlive) {
                delegate.destroy()
                if (!delegate.waitFor(500, TimeUnit.MILLISECONDS)) {
                    delegate.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    override val stdout: InputStream get() = delegate.inputStream
    override val stderr: InputStream get() = delegate.errorStream
    override fun isAlive(): Boolean = delegate.isAlive
    override fun destroy() {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        delegate.destroy()
        if (!delegate.waitFor(800, TimeUnit.MILLISECONDS)) delegate.destroyForcibly()
    }
}

internal const val MaxNetworkExchanges = 20_000
internal const val ExchangePublishIntervalMs = 100L

internal fun findMitmdumpExecutable(): String? {
    val pathCandidates = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "mitmdump") }
    return (pathCandidates + listOf(File("/opt/homebrew/bin/mitmdump"), File("/usr/local/bin/mitmdump")))
        .firstOrNull { it.exists() && it.canExecute() }
        ?.absolutePath
}

internal suspend fun defaultCertificateSubjectHash(runner: CommandRunner, certificate: File): String? {
    return listOf("PEM", "DER").firstNotNullOfOrNull { format ->
        val result = runner.run(
            listOf("openssl", "x509", "-inform", format, "-subject_hash_old", "-in", certificate.absolutePath, "-noout"),
            10,
        )
        if (!result.isSuccess) {
            null
        } else {
            result.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.matches(Regex("[0-9a-fA-F]{8}")) }
                ?.lowercase()
        }
    }
}

internal suspend fun defaultCertificateSpkiFingerprint(runner: CommandRunner, certificate: File): String? {
    val command = "openssl x509 -in ${shellQuote(certificate.absolutePath)} -pubkey -noout | " +
        "openssl pkey -pubin -outform der | " +
        "openssl dgst -sha256 -binary | " +
        "base64"
    val result = runner.run(listOf("/bin/sh", "-c", command), 10)
    return if (result.isSuccess) result.stdout.trim().takeIf { it.isNotBlank() } else null
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal fun resolveLanIp(): String {
    return NetworkInterface.getNetworkInterfaces().toList().asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.hostAddress.startsWith("169.254.") }
        ?.hostAddress
        ?: "127.0.0.1"
}

