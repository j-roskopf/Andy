package app.andy.desktop.service.proxy

import app.andy.desktop.service.CommandRunner
import java.io.File

/**
 * Export the host OS trust store to a PEM file so mitmproxy can verify upstream
 * certificates that were re-signed by corporate roots already trusted on the Mac.
 *
 * [ssl_verify_upstream_trusted_ca] replaces certifi, so the PEM must include the
 * roots the host would normally trust — not only the corporate intermediate.
 */
internal object HostTrustedCas {
    const val PEM_NAME = "host-trusted-cas.pem"

    suspend fun exportTo(proxyDir: File, hostOsName: String, runner: CommandRunner): File? {
        proxyDir.mkdirs()
        val out = File(proxyDir, PEM_NAME)
        return when {
            hostOsName.contains("Mac", ignoreCase = true) -> exportMacOs(out, runner)
            hostOsName.contains("Linux", ignoreCase = true) -> exportLinux(out)
            else -> null
        }
    }

    private suspend fun exportMacOs(out: File, runner: CommandRunner): File? {
        val keychains = listOf(
            "/System/Library/Keychains/SystemRootCertificates.keychain",
            "/Library/Keychains/System.keychain",
            File(System.getProperty("user.home"), "Library/Keychains/login.keychain-db").absolutePath,
            File(System.getProperty("user.home"), "Library/Keychains/login.keychain").absolutePath,
        ).filter { File(it).exists() }
        if (keychains.isEmpty()) return null

        val pem = StringBuilder()
        keychains.forEach { keychain ->
            val result = runner.run(listOf("/usr/bin/security", "find-certificate", "-a", "-p", keychain), 60)
            val output = listOf(result.stdout, result.stderr).filter { it.isNotBlank() }.joinToString("\n")
            if (result.isSuccess && output.contains("BEGIN CERTIFICATE")) {
                pem.append(output.trim()).append('\n')
            }
        }
        if (pem.isBlank()) return null
        out.writeText(pem.toString())
        return out.takeIf { it.isFile && it.length() > 0 }
    }

    private fun exportLinux(out: File): File? {
        val candidates = listOf(
            "/etc/ssl/certs/ca-certificates.crt",
            "/etc/pki/tls/certs/ca-bundle.crt",
            "/etc/ssl/ca-bundle.pem",
        )
        val source = candidates.firstOrNull { File(it).isFile } ?: return null
        File(source).copyTo(out, overwrite = true)
        return out.takeIf { it.isFile && it.length() > 0 }
    }
}
