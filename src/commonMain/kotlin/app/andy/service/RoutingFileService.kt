package app.andy.service

/**
 * Routes [FileService] calls to the Android or iOS backend based on
 * [IosTargetRegistry.isIosTarget].
 */
class RoutingFileService(
    private val android: FileService,
    private val ios: FileService,
) : FileService {
    private fun of(serial: String) =
        if (IosTargetRegistry.isIosTarget(serial)) ios else android

    override suspend fun list(serial: String, path: String) = of(serial).list(serial, path)
    override suspend fun pull(serial: String, remotePath: String, localPath: String) =
        of(serial).pull(serial, remotePath, localPath)
    override suspend fun push(serial: String, localPath: String, remotePath: String) =
        of(serial).push(serial, localPath, remotePath)
    override suspend fun delete(serial: String, remotePath: String) = of(serial).delete(serial, remotePath)
}
