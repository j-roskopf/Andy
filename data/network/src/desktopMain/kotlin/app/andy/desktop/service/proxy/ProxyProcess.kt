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
    // Prefer real LAN NICs over VPN/Tailscale (often utun*/100.64/10 CGNAT). Phones on
    // Wi‑Fi cannot reach the Mac's Tailscale address, which looks like "Wi‑Fi fine, no apps".
    return lanIpv4Candidates().firstOrNull()?.address?.hostAddress ?: "127.0.0.1"
}

/**
 * Hosts to advertise for Network Access URLs in Settings.
 *
 * Prefer physical LAN first (same ordering as [resolveLanIp]), then VPN/overlay
 * addresses (Tailscale CGNAT, WireGuard, utun, …). VPN-only machines must not fall
 * back to 127.0.0.1 — that defeats QR / phone first-run on the user's private network.
 */
fun resolveNetworkAccessHosts(): List<String> {
    val lan = lanIpv4Candidates().map { it.address.hostAddress }
    val vpn = vpnIpv4Candidates().map { it.address.hostAddress }
    return (lan + vpn).filterNotNull().distinct().ifEmpty { listOf("127.0.0.1") }
}

private data class IpCandidate(val interfaceName: String, val address: Inet4Address)

private fun lanIpv4Candidates(): List<IpCandidate> =
    NetworkInterface.getNetworkInterfaces().toList().asSequence()
        .filter { iface ->
            iface.isUp &&
                !iface.isLoopback &&
                !iface.isVirtual &&
                !isVpnLikeInterfaceName(iface.name)
        }
        .flatMap { iface ->
            iface.inetAddresses.toList().asSequence()
                .filterIsInstance<Inet4Address>()
                .filter { address -> isReachableLanIpv4(address) }
                .map { address -> IpCandidate(iface.name, address) }
        }
        .sortedWith(
            compareByDescending<IpCandidate> { isRfc1918(it.address) }
                .thenByDescending { isPreferredLanInterfaceName(it.interfaceName) }
                .thenBy { it.interfaceName },
        )
        .toList()

private fun vpnIpv4Candidates(): List<IpCandidate> =
    NetworkInterface.getNetworkInterfaces().toList().asSequence()
        .filter { iface ->
            iface.isUp &&
                !iface.isLoopback &&
                !iface.isVirtual &&
                isVpnLikeInterfaceName(iface.name)
        }
        .flatMap { iface ->
            iface.inetAddresses.toList().asSequence()
                .filterIsInstance<Inet4Address>()
                .filter { address -> isReachableVpnIpv4(address) }
                .map { address -> IpCandidate(iface.name, address) }
        }
        .sortedWith(
            compareByDescending<IpCandidate> { isCarrierGradeNat(it.address.hostAddress.orEmpty()) }
                .thenBy { it.interfaceName },
        )
        .toList()

/** VPN/overlay IPv4 usable for Network Access (includes Tailscale CGNAT). */
internal fun isReachableVpnIpv4(address: Inet4Address): Boolean {
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return false
    val host = address.hostAddress ?: return false
    if (host.startsWith("169.254.")) return false
    return true
}

internal fun isVpnLikeInterfaceName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("utun") ||
        lower.startsWith("tun") ||
        lower.startsWith("tap") ||
        lower.startsWith("ppp") ||
        lower.startsWith("ipsec") ||
        lower.startsWith("wg") ||
        lower.startsWith("tailscale") ||
        lower.contains("tailscale")
}

internal fun isPreferredLanInterfaceName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("en") || lower.startsWith("eth") || lower.startsWith("wlan") || lower.startsWith("wl")
}

internal fun isReachableLanIpv4(address: Inet4Address): Boolean {
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return false
    val host = address.hostAddress ?: return false
    if (host.startsWith("169.254.")) return false
    // Carrier-grade NAT / Tailscale / many VPN overlays — not reachable from phone Wi‑Fi.
    if (isCarrierGradeNat(host)) return false
    return true
}

fun isCarrierGradeNat(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    // 100.64.0.0/10
    return a == 100 && b in 64..127
}

internal fun isRfc1918(address: Inet4Address): Boolean {
    val host = address.hostAddress ?: return false
    val parts = host.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

