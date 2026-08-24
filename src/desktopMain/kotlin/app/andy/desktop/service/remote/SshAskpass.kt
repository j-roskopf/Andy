package app.andy.desktop.service.remote

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * GUI [SSH_ASKPASS] helper so OpenSSH can prompt for a password / key passphrase when Andy
 * has no TTY (Compose desktop). Secrets are remembered for this Andy process only via
 * [SshAskpassBroker] (no workspace / disk credential store).
 */
object SshAskpass {
    private val script: File by lazy { writeAskpassScript() }
    private val pythonHelper: File by lazy { writePythonHelper() }

    /** Environment entries for [ProcessBuilder.environment] so ssh may prompt without a TTY. */
    fun environmentEntries(): Map<String, String> {
        SshAskpassBroker.start()
        val sock = SshAskpassBroker.socketPath()?.absolutePath
            ?: error("SSH askpass broker failed to start")
        // Touch helpers so paths exist before ssh runs.
        script
        pythonHelper
        val env = linkedMapOf(
            "SSH_ASKPASS" to script.absolutePath,
            "SSH_ASKPASS_REQUIRE" to "force",
            "ANDY_SSH_ASKPASS_SOCK" to sock,
            "ANDY_SSH_ASKPASS_PY" to pythonHelper.absolutePath,
        )
        if (System.getenv("DISPLAY").isNullOrBlank() && isLinux()) {
            env["DISPLAY"] = ":0"
        }
        return env
    }

    fun applyTo(builder: ProcessBuilder) {
        val env = builder.environment()
        environmentEntries().forEach { (k, v) -> env[k] = v }
        builder.redirectInput(ProcessBuilder.Redirect.PIPE)
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private fun writePythonHelper(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "andy-ssh-askpass").also { it.mkdirs() }
        val file = File(dir, "askpass_client.py")
        file.writeText(
            """
            import socket, sys
            sock_path, prompt = sys.argv[1], sys.argv[2]
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.connect(sock_path)
            s.sendall((prompt + "\n").encode())
            data = b""
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                data += chunk
                if b"\n" in data:
                    break
            sys.stdout.write(data.decode(errors="replace").split("\n", 1)[0])
            """.trimIndent() + "\n",
        )
        return file
    }

    private fun writeAskpassScript(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "andy-ssh-askpass").also { it.mkdirs() }
        val file = File(dir, "askpass.sh")
        val body = """
            #!/bin/sh
            PROMPT="${'$'}{1:-SSH password:}"
            SOCK="${'$'}ANDY_SSH_ASKPASS_SOCK"
            PY="${'$'}ANDY_SSH_ASKPASS_PY"
            if [ -z "${'$'}SOCK" ] || [ ! -S "${'$'}SOCK" ]; then
              echo "Andy SSH askpass socket missing" >&2
              exit 1
            fi
            if command -v python3 >/dev/null 2>&1 && [ -f "${'$'}PY" ]; then
              exec python3 "${'$'}PY" "${'$'}SOCK" "${'$'}PROMPT"
            fi
            if command -v nc >/dev/null 2>&1; then
              printf '%s\n' "${'$'}PROMPT" | nc -U "${'$'}SOCK"
              exit ${'$'}?
            fi
            echo "Need python3 or nc for Andy SSH askpass" >&2
            exit 1
        """.trimIndent()
        file.writeText(body)
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        file.setExecutable(true)
        return file
    }
}
