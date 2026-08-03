package app.andy.desktop.service.agents.acp

import com.agentclientprotocol.common.FileSystemOperations
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import com.agentclientprotocol.protocol.acpFail
import kotlinx.serialization.json.JsonElement
import java.io.File

/** ACP fs capability restricted to the task's canonical cwd/worktree. */
class AcpFileSystemOperations(
    private val cwd: File,
) : FileSystemOperations {
    private val root = cwd.canonicalFile

    override suspend fun fsReadTextFile(
        path: String,
        line: UInt?,
        limit: UInt?,
        _meta: JsonElement?,
    ): ReadTextFileResponse {
        val target = resolve(path)
        if (!target.isFile) acpFail("File does not exist: $path")
        val lines = target.readLines()
        val start = (line?.toInt()?.coerceAtLeast(1) ?: 1) - 1
        val selected = lines.drop(start).let { values ->
            if (limit == null) values else values.take(limit.toInt().coerceAtLeast(0))
        }
        return ReadTextFileResponse(selected.joinToString("\n"), null)
    }

    override suspend fun fsWriteTextFile(
        path: String,
        content: String,
        _meta: JsonElement?,
    ): WriteTextFileResponse {
        val target = resolve(path)
        target.parentFile?.mkdirs()
        target.writeText(content)
        return WriteTextFileResponse(null)
    }

    private fun resolve(path: String): File {
        val candidate = File(path).let { if (it.isAbsolute) it else File(root, path) }
        val canonical = if (candidate.exists()) {
            candidate.canonicalFile
        } else {
            File(candidate.parentFile?.canonicalPath ?: root.path, candidate.name)
        }
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separator
        if (canonical.path != root.path && !canonical.path.startsWith(rootPath)) {
            acpFail("Path is outside the task workspace: $path")
        }
        return canonical
    }
}
