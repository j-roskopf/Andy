package app.andy.desktop.service.agents

import java.io.File

/** One managed evidence bundle (§4) copied into a task's local Andy-managed directory. */
data class TaskEvidenceBundle(
    val id: String,
    val taskLocalDir: File,
    val manifestFile: File?,
    val keyFiles: List<File>,
)

/**
 * Copies materialized evidence bundles from Andy's shared managed root
 * (`~/.andy/evidence/<bundleId>/`) into a task-local directory
 * (`~/.andy/agents/<taskId>/evidence/<bundleId>/`) so a task's evidence survives even if the
 * shared bundle is later deleted, and so a launched/resumed prompt can point the agent at
 * concrete local file paths.
 *
 * This only ever copies bytes a bundle id resolves to under the managed root — it never reads
 * investigation data directly and never accepts an arbitrary source path.
 */
object AgentEvidenceMaterializer {
    private const val ManifestFileName = "manifest.json"
    private const val MaxKeyFilesInPrompt = 8
    private val SafeBundleIdPattern = Regex("^[A-Za-z0-9._-]+$")

  /** Copies [bundleId] from [managedRootDir] into [taskEvidenceDir]/<bundleId>, if it exists. */
    fun copyBundle(managedRootDir: File, bundleId: String, taskEvidenceDir: File): TaskEvidenceBundle? {
        val sourceDir = resolveManagedBundleDir(managedRootDir, bundleId) ?: return null
        val destinationDir = resolveTaskBundleDir(taskEvidenceDir, bundleId) ?: return null
        destinationDir.deleteRecursively()
        sourceDir.copyRecursively(destinationDir, overwrite = true)
        val manifestFile = File(destinationDir, ManifestFileName).takeIf { it.isFile }
        val keyFiles = destinationDir.walkTopDown()
            .filter { it.isFile && it.name != ManifestFileName }
            .toList()
            .sortedBy { it.relativeTo(destinationDir).path }
        return TaskEvidenceBundle(id = bundleId, taskLocalDir = destinationDir, manifestFile = manifestFile, keyFiles = keyFiles)
    }

    /** Copies every id in [bundleIds], silently skipping ids no longer present under [managedRootDir]. */
    fun copyBundles(managedRootDir: File, bundleIds: List<String>, taskEvidenceDir: File): List<TaskEvidenceBundle> =
        bundleIds.distinct().mapNotNull { id -> copyBundle(managedRootDir, id, taskEvidenceDir) }

    /** Prompt text pointing the agent at the copied bundles' local paths, or "" when [bundles] is empty. */
    fun promptSuffix(bundles: List<TaskEvidenceBundle>): String {
        if (bundles.isEmpty()) return ""
        return buildString {
            append("\n\nEvidence bundle")
            if (bundles.size != 1) append('s')
            append(" copied to this task's local evidence directory:\n")
            bundles.forEach { bundle ->
                append("- ").append(bundle.id).append(": ").append(bundle.taskLocalDir.absolutePath).append('\n')
                bundle.manifestFile?.let { append("  manifest: ").append(it.absolutePath).append('\n') }
                bundle.keyFiles.take(MaxKeyFilesInPrompt).forEach { file ->
                    append("  - ").append(file.absolutePath).append('\n')
                }
                val remaining = bundle.keyFiles.size - MaxKeyFilesInPrompt
                if (remaining > 0) append("  - …and $remaining more file(s)\n")
            }
        }.trimEnd()
    }

    private fun resolveManagedBundleDir(root: File, bundleId: String): File? {
        if (!SafeBundleIdPattern.matches(bundleId)) return null
        val rootCanonical = root.canonicalFile
        val bundleDir = File(rootCanonical, bundleId).canonicalFile
        if (!isDescendant(bundleDir, rootCanonical) || !bundleDir.isDirectory) return null
        return bundleDir
    }

    private fun resolveTaskBundleDir(taskEvidenceDir: File, bundleId: String): File? {
        if (!SafeBundleIdPattern.matches(bundleId)) return null
        val rootCanonical = taskEvidenceDir.canonicalFile
        taskEvidenceDir.mkdirs()
        val bundleDir = File(rootCanonical, bundleId).canonicalFile
        if (!isDescendant(bundleDir, rootCanonical)) return null
        return bundleDir
    }

    private fun isDescendant(child: File, parent: File): Boolean {
        val childPath = child.path
        val parentPath = parent.path
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    }
}
