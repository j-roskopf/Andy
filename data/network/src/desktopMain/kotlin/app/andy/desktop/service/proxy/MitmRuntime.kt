package app.andy.desktop.service.proxy

import app.andy.service.CommandResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Andy-managed mitmproxy runtime under `~/.andy/proxy/venv`.
 *
 * Prefer the pinned venv's mitmdump; fall back to a system mitmdump only when
 * its version is in the supported range so hook signatures stay compatible.
 */
object MitmRuntime {
    const val PINNED_MITMPROXY_VERSION = "12.2.3"

    /** Inclusive major.minor range accepted for system mitmdump fallback. */
    val MIN_SUPPORTED_VERSION = MitmVersion(10, 0, 0)
    val MAX_SUPPORTED_VERSION = MitmVersion(12, 99, 99)

    private const val MARKER_NAME = "mitmproxy-version"
    private const val MIN_PYTHON_MAJOR = 3
    private const val MIN_PYTHON_MINOR = 12

    data class MitmVersion(val major: Int, val minor: Int, val patch: Int = 0) : Comparable<MitmVersion> {
        override fun compareTo(other: MitmVersion): Int =
            compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

        override fun toString(): String = "$major.$minor.$patch"

        companion object {
            fun parse(raw: String): MitmVersion? {
                val match = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(raw.trim()) ?: return null
                return MitmVersion(
                    major = match.groupValues[1].toInt(),
                    minor = match.groupValues[2].toInt(),
                    patch = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toInt() ?: 0,
                )
            }
        }
    }

    data class ResolveResult(
        val executable: String?,
        val source: Source,
        val message: String,
    ) {
        enum class Source { PinnedVenv, System, None }
    }

    fun proxyHome(userHome: File = File(System.getProperty("user.home"))): File =
        File(userHome, ".andy/proxy")

    fun venvDir(userHome: File = File(System.getProperty("user.home"))): File =
        File(proxyHome(userHome), "venv")

    fun pinnedMitmdump(userHome: File = File(System.getProperty("user.home"))): File {
        val bin = venvExecutablesDir(venvDir(userHome))
        return if (isWindows()) File(bin, "mitmdump.exe") else File(bin, "mitmdump")
    }

    private fun venvExecutablesDir(venv: File): File =
        if (isWindows()) File(venv, "Scripts") else File(venv, "bin")

    private fun venvPython(venv: File): File? {
        val bin = venvExecutablesDir(venv)
        return if (isWindows()) {
            File(bin, "python.exe").takeIf { it.isFile }
        } else {
            File(bin, "python").takeIf { it.isFile && it.canExecute() }
                ?: File(bin, "python3").takeIf { it.isFile && it.canExecute() }
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    fun versionMarker(userHome: File = File(System.getProperty("user.home"))): File =
        File(proxyHome(userHome), MARKER_NAME)

    /**
     * Ensure the pinned venv exists (or is rebuilt when the pin changes).
     * Surfaces progress via [onStatus]. Offline-friendly: returns failure without
     * wiping a still-usable older venv when pip cannot reach the network.
     */
    fun ensureProvisioned(
        userHome: File = File(System.getProperty("user.home")),
        onStatus: (String) -> Unit = {},
        findPython: () -> String? = { findSuitablePython() },
        runProcess: (List<String>, File?, Long) -> ProcessResult = { command, directory, timeoutSec ->
            defaultRunProcess(command, directory, timeoutSec)
        },
    ): CommandResult {
        val home = proxyHome(userHome)
        home.mkdirs()
        val venv = venvDir(userHome)
        val mitmdump = pinnedMitmdump(userHome)
        val marker = versionMarker(userHome)
        val markerVersion = marker.takeIf { it.isFile }?.readText()?.trim()
        if (mitmdump.isFile && mitmdump.canExecute() && markerVersion == PINNED_MITMPROXY_VERSION) {
            return CommandResult.success(mitmdump.absolutePath)
        }

        val python = findPython()
            ?: return CommandResult.failure(
                "Python $MIN_PYTHON_MAJOR.$MIN_PYTHON_MINOR+ not found. Andy needs it to install " +
                    "mitmproxy==$PINNED_MITMPROXY_VERSION into ~/.andy/proxy/venv. " +
                    "Install Python 3.12+ (e.g. `brew install python@3.12`) or install a supported " +
                    "mitmdump on PATH (`brew install mitmproxy`).",
            )

        onStatus("Provisioning mitmproxy $PINNED_MITMPROXY_VERSION (first run may take a minute)…")
        if (venv.exists() && markerVersion != PINNED_MITMPROXY_VERSION) {
            onStatus("Updating Andy's mitmproxy venv to $PINNED_MITMPROXY_VERSION…")
            venv.deleteRecursively()
        }
        if (!venv.exists()) {
            val create = runProcess(listOf(python, "-m", "venv", venv.absolutePath), home, 120)
            if (!create.success) {
                return CommandResult.failure(
                    "Could not create ~/.andy/proxy/venv with $python. ${create.output}".trim(),
                )
            }
        }

        val venvPython = venvPython(venv)
            ?: return CommandResult.failure("venv created but python binary missing under ${venv.absolutePath}")

        val pipUpgrade = runProcess(
            listOf(venvPython.absolutePath, "-m", "pip", "install", "--upgrade", "pip"),
            venv,
            180,
        )
        if (!pipUpgrade.success) {
            onStatus("pip upgrade failed; continuing with existing pip…")
        }

        onStatus("Installing mitmproxy==$PINNED_MITMPROXY_VERSION…")
        val install = runProcess(
            listOf(venvPython.absolutePath, "-m", "pip", "install", "mitmproxy==$PINNED_MITMPROXY_VERSION"),
            venv,
            600,
        )
        if (!install.success) {
            val stillUsable = mitmdump.isFile && mitmdump.canExecute()
            return if (stillUsable) {
                CommandResult.success(mitmdump.absolutePath)
            } else {
                CommandResult.failure(
                    "Could not install mitmproxy==$PINNED_MITMPROXY_VERSION into ~/.andy/proxy/venv. " +
                        "Check network access to PyPI, or install a supported mitmdump on PATH " +
                        "(`brew install mitmproxy`, versions " +
                        "${MIN_SUPPORTED_VERSION.major}.x–${MAX_SUPPORTED_VERSION.major}.x). " +
                        install.output.trim(),
                )
            }
        }
        if (!mitmdump.isFile || !mitmdump.canExecute()) {
            return CommandResult.failure(
                "pip reported success but ${mitmdump.absolutePath} is missing or not executable.",
            )
        }
        marker.writeText(PINNED_MITMPROXY_VERSION)
        onStatus("mitmproxy $PINNED_MITMPROXY_VERSION ready at ${mitmdump.absolutePath}")
        return CommandResult.success(mitmdump.absolutePath)
    }

    /**
     * Resolve mitmdump: pinned venv → supported system binary → clear error.
     * When [provisionIfNeeded] is true, attempts venv creation first.
     */
    fun resolveMitmdump(
        userHome: File = File(System.getProperty("user.home")),
        provisionIfNeeded: Boolean = true,
        onStatus: (String) -> Unit = {},
        findSystemMitmdump: () -> String? = { findMitmdumpExecutable() },
        readVersion: (String) -> MitmVersion? = { executable -> readMitmdumpVersion(executable) },
        ensure: () -> CommandResult = {
            ensureProvisioned(userHome = userHome, onStatus = onStatus)
        },
    ): ResolveResult {
        val pinned = pinnedMitmdump(userHome)
        val markerOk = versionMarker(userHome).takeIf { it.isFile }?.readText()?.trim() == PINNED_MITMPROXY_VERSION
        if (pinned.isFile && pinned.canExecute() && markerOk) {
            return ResolveResult(pinned.absolutePath, ResolveResult.Source.PinnedVenv, pinned.absolutePath)
        }
        if (provisionIfNeeded) {
            val provisioned = ensure()
            if (provisioned.isSuccess && pinned.isFile && pinned.canExecute()) {
                return ResolveResult(pinned.absolutePath, ResolveResult.Source.PinnedVenv, pinned.absolutePath)
            }
            // Fall through to system binary when provisioning fails (offline, old Python, etc.).
            onStatus(provisioned.stderr.ifBlank { provisioned.stdout }.take(220))
        }

        val system = findSystemMitmdump()
        if (system != null) {
            val version = readVersion(system)
            if (version != null && version in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION) {
                return ResolveResult(
                    system,
                    ResolveResult.Source.System,
                    "$system (system mitmproxy $version; Andy prefers pinned $PINNED_MITMPROXY_VERSION)",
                )
            }
            val versionLabel = version?.toString() ?: "unknown"
            return ResolveResult(
                null,
                ResolveResult.Source.None,
                "Found system mitmdump at $system (version $versionLabel) but Andy requires " +
                    "mitmproxy ${MIN_SUPPORTED_VERSION.major}.x–${MAX_SUPPORTED_VERSION.major}.x. " +
                    installInstructions(),
            )
        }

        return ResolveResult(null, ResolveResult.Source.None, installInstructions())
    }

    fun installInstructions(): String =
        "Andy could not provide mitmdump. Install Python $MIN_PYTHON_MAJOR.$MIN_PYTHON_MINOR+ so Andy " +
            "can create ~/.andy/proxy/venv with mitmproxy==$PINNED_MITMPROXY_VERSION, " +
            "or install a supported mitmdump on PATH (`brew install mitmproxy`). "

    fun findSuitablePython(): String? {
        val pythonNames = if (isWindows()) {
            listOf("python3.14.exe", "python3.13.exe", "python3.12.exe", "python3.exe", "python.exe")
        } else {
            listOf("python3.14", "python3.13", "python3.12", "python3", "python")
        }
        val candidates = buildList {
            if (!isWindows()) {
                addAll(
                    pythonNames.flatMap { name ->
                        listOf(
                            File("/opt/homebrew/bin", name),
                            File("/usr/local/bin", name),
                            File("/usr/bin", name),
                        )
                    },
                )
            }
            System.getenv("PATH").orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .forEach { dir ->
                    pythonNames.forEach { name -> add(File(dir, name)) }
                }
        }
        return candidates
            .distinctBy { it.absolutePath }
            .firstOrNull { file ->
                file.isFile && (isWindows() || file.canExecute()) &&
                    pythonVersionAtLeast(file.absolutePath, MIN_PYTHON_MAJOR, MIN_PYTHON_MINOR)
            }
            ?.absolutePath
    }

    fun readMitmdumpVersion(executable: String): MitmVersion? {
        val result = defaultRunProcess(listOf(executable, "--version"), null, 10)
        if (!result.success && result.output.isBlank()) return null
        // Typical: "Mitmproxy: 12.2.3 binary" or "Mitmproxy: 10.4.2"
        val line = result.output.lineSequence().firstOrNull { it.contains("Mitmproxy", ignoreCase = true) }
            ?: result.output.lineSequence().firstOrNull()
            ?: return null
        return MitmVersion.parse(line)
    }

    private fun pythonVersionAtLeast(executable: String, major: Int, minor: Int): Boolean {
        val result = defaultRunProcess(
            listOf(
                executable,
                "-c",
                "import sys; print(f'{sys.version_info[0]}.{sys.version_info[1]}'); " +
                    "raise SystemExit(0 if sys.version_info[:2] >= ($major, $minor) else 1)",
            ),
            null,
            10,
        )
        return result.success
    }

    data class ProcessResult(val success: Boolean, val output: String)

    private fun defaultRunProcess(command: List<String>, directory: File?, timeoutSec: Long): ProcessResult {
        return runCatching {
            val process = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (output.length < 8_000) {
                            output.appendLine(line)
                        }
                    }
                }
            }.also { it.isDaemon = true; it.start() }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(500)
                return ProcessResult(false, "Timed out after ${timeoutSec}s: ${command.joinToString(" ")}\n$output")
            }
            reader.join(2_000)
            ProcessResult(process.exitValue() == 0, output.toString())
        }.getOrElse { error ->
            ProcessResult(false, error.message ?: error::class.simpleName.orEmpty())
        }
    }
}
