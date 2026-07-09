package app.andy.desktop.service

import app.andy.desktop.parser.AndroidParsers
import app.andy.model.DeviceFile
import app.andy.service.CommandResult
import app.andy.service.FileService

class DesktopFileService(
    private val runner: CommandRunner,
    private val devices: DesktopDeviceService,
) : FileService {
    override suspend fun list(serial: String, path: String): List<DeviceFile> {
        val adb = devices.adbPath() ?: return emptyList()
        val listPath = if (path != "/" && !path.endsWith("/")) "$path/" else path
        val result = runner.run(listOf(adb, "-s", serial, "shell", "ls", "-la", listPath), 10)
        return AndroidParsers.parseFileListing(path, result.stdout)
    }

    override suspend fun pull(serial: String, remotePath: String, localPath: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "pull", remotePath, localPath), 120)
    }

    override suspend fun push(serial: String, localPath: String, remotePath: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "push", localPath, remotePath), 120)
    }

    override suspend fun delete(serial: String, remotePath: String): CommandResult {
        val adb = devices.adbPath() ?: return CommandResult.failure("ADB not found")
        return runner.run(listOf(adb, "-s", serial, "shell", "rm", "-rf", remotePath), 30)
    }
}
