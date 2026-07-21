package app.andy.desktop.service.inspector

import app.andy.desktop.service.CommandRunner
import app.andy.desktop.service.DesktopDeviceService
import app.andy.model.PrefEntry
import app.andy.model.SharedPrefsXml
import app.andy.service.CommandResult
import app.andy.service.SharedPrefsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DesktopSharedPrefsService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : SharedPrefsService {
    override suspend fun listFiles(serial: String, packageName: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureRunAs(serial, packageName)
            val result = runAs(serial, packageName, "ls", "shared_prefs")
            if (!result.isSuccess) {
                error(result.stderr.ifBlank { result.stdout }.ifBlank { "Unable to list shared_prefs" })
            }
            result.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(".xml") }
                .sorted()
                .toList()
        }
    }

    override suspend fun read(serial: String, packageName: String, fileName: String): Result<List<PrefEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureRunAs(serial, packageName)
                val safeName = requireXmlName(fileName)
                val result = runAs(serial, packageName, "cat", "shared_prefs/$safeName")
                if (!result.isSuccess) {
                    error(result.stderr.ifBlank { result.stdout }.ifBlank { "Unable to read $safeName" })
                }
                SharedPrefsXml.parse(result.stdout)
            }
        }

    override suspend fun upsert(
        serial: String,
        packageName: String,
        fileName: String,
        entry: PrefEntry,
    ): CommandResult = withContext(Dispatchers.IO) {
        mutate(serial, packageName, fileName) { SharedPrefsXml.upsert(it, entry) }
    }

    override suspend fun delete(
        serial: String,
        packageName: String,
        fileName: String,
        key: String,
    ): CommandResult = withContext(Dispatchers.IO) {
        mutate(serial, packageName, fileName) { SharedPrefsXml.delete(it, key) }
    }

    private suspend fun mutate(
        serial: String,
        packageName: String,
        fileName: String,
        transform: (List<PrefEntry>) -> List<PrefEntry>,
    ): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        ensureRunAs(serial, packageName)
        val safeName = requireXmlName(fileName)
        val current = runAs(serial, packageName, "cat", "shared_prefs/$safeName")
        val existing = if (current.isSuccess) SharedPrefsXml.parse(current.stdout) else emptyList()
        val nextXml = SharedPrefsXml.serialize(transform(existing))
        val local = File.createTempFile("andy-prefs-", ".xml")
        val remoteTmp = "/data/local/tmp/andy-prefs-${UUID.randomUUID()}.xml"
        return try {
            local.writeText(nextXml)
            val push = runner.run(listOf(adb, "-s", serial, "push", local.absolutePath, remoteTmp), 30)
            if (!push.isSuccess) return CommandResult.failure(push.stderr.ifBlank { "Failed to push prefs" })
            val copy = runAs(serial, packageName, "cp", remoteTmp, "shared_prefs/$safeName")
            if (!copy.isSuccess) {
                return CommandResult.failure(copy.stderr.ifBlank { copy.stdout }.ifBlank { "Failed to write prefs" })
            }
            runner.run(listOf(adb, "-s", serial, "shell", "rm", "-f", remoteTmp), 10)
            CommandResult.success("Updated $safeName")
        } finally {
            local.delete()
        }
    }

    private suspend fun ensureRunAs(serial: String, packageName: String) {
        val probe = runAs(serial, packageName, "id")
        if (!probe.isSuccess) {
            error(
                probe.stderr.ifBlank { probe.stdout }
                    .ifBlank { "run-as failed — package must be debuggable" },
            )
        }
    }

    private suspend fun runAs(serial: String, packageName: String, vararg command: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell", "run-as", packageName, *command), 20)
    }

    private fun requireXmlName(fileName: String): String {
        val name = fileName.substringAfterLast('/').trim()
        require(
            name.endsWith(".xml") &&
                name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' },
        ) {
            "Invalid prefs file name"
        }
        return name
    }
}
