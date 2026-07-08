package app.andy.desktop.service

import app.andy.model.HostFileDocument
import app.andy.model.HostFileEntry
import app.andy.model.HostFileSaveResult
import app.andy.model.HostIndexStatus
import app.andy.model.HostSearchMatchKind
import app.andy.model.HostSearchMode
import app.andy.model.HostSearchResult
import app.andy.service.HostFileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

class DesktopHostFileService(
    private val indexDir: File = File(System.getProperty("user.home"), ".andy/file-index"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : HostFileService {
    private val roots = ConcurrentHashMap<String, RootIndex>()
    private val statuses = ConcurrentHashMap<String, MutableStateFlow<HostIndexStatus>>()
    private val watchers = ConcurrentHashMap<String, WatchHandle>()

    override suspend fun list(path: String): List<HostFileEntry> = withContext(Dispatchers.IO) {
        val dir = File(path)
        dir.listFiles()
            ?.map { it.toHostFileEntry() }
            ?.sortedWith(compareByDescending<HostFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()
    }

    override suspend fun read(path: String): HostFileDocument = withContext(Dispatchers.IO) {
        val file = File(path)
        val bytes = file.readBytes()
        val charset = detectCharset(bytes)
        HostFileDocument(
            path = file.absolutePath,
            content = bytes.toString(charset),
            modifiedMillis = file.lastModified(),
            sizeBytes = file.length(),
            charset = charset.name(),
            languageHint = languageHintForFile(file),
        )
    }

    override suspend fun save(path: String, content: String, expectedModifiedMillis: Long): HostFileSaveResult = withContext(Dispatchers.IO) {
        val file = File(path)
        if (file.exists() && expectedModifiedMillis > 0L && file.lastModified() != expectedModifiedMillis) {
            return@withContext HostFileSaveResult.Conflict(file.lastModified())
        }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile ?: File("."), ".${file.name}.andy-${System.nanoTime()}.tmp")
            temp.writeText(content, StandardCharsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            refreshFileInIndexes(file.toPath())
            HostFileSaveResult.Saved(file.lastModified())
        }.getOrElse {
            HostFileSaveResult.Failed(it.message ?: "Save failed")
        }
    }

    override suspend fun indexStatus(root: String): HostIndexStatus = withContext(Dispatchers.IO) {
        val normalized = normalizeRoot(root)
        rootStatus(normalized).value.also {
            if (it.indexedFiles == 0 && !it.indexing) loadIndex(normalized)
        }
    }

    override fun indexRoot(root: String): Flow<HostIndexStatus> = channelFlow {
        val normalized = normalizeRoot(root)
        val status = rootStatus(normalized)
        val collector = launch { status.collect(::send) }
        val indexer = launch { rebuildIndex(normalized) }
        startWatcher(normalized)
        awaitClose {
            collector.cancel()
            indexer.cancel()
        }
    }

    override suspend fun search(query: String, mode: HostSearchMode, roots: List<String>, limit: Int): List<HostSearchResult> = withContext(Dispatchers.IO) {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return@withContext emptyList()
        roots.asSequence()
            .map(::normalizeRoot)
            .map { loadIndex(it) }
            .flatMap { index -> searchIndex(index, terms, mode).asSequence() }
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    private suspend fun rebuildIndex(root: String) = withContext(Dispatchers.IO) {
        val rootFile = File(root)
        val status = rootStatus(root)
        if (!rootFile.isDirectory) {
            status.value = HostIndexStatus(root, 0, 0, indexing = false, message = "Root missing", updatedAtMillis = System.currentTimeMillis())
            return@withContext
        }
        status.value = status.value.copy(indexing = true, message = "Indexing", updatedAtMillis = System.currentTimeMillis())
        val entries = mutableMapOf<String, IndexedFile>()
        var bytes = 0L
        rootFile.toPath().walkIndexedFiles { path ->
            val file = path.toFile()
            bytes += file.length()
            entries[file.absolutePath] = indexFile(root, file)
            if (entries.size % 250 == 0) {
                status.value = HostIndexStatus(root, entries.size, bytes, indexing = true, message = "Indexing ${entries.size} files", updatedAtMillis = System.currentTimeMillis())
            }
        }
        val index = RootIndex(root, entries)
        roots[root] = index
        saveIndex(index)
        status.value = HostIndexStatus(root, entries.size, bytes, indexing = false, message = "Indexed ${entries.size} files", updatedAtMillis = System.currentTimeMillis())
    }

    private fun searchIndex(index: RootIndex, terms: List<String>, mode: HostSearchMode): List<HostSearchResult> {
        val results = mutableListOf<HostSearchResult>()
        if (mode == HostSearchMode.FileName || mode == HostSearchMode.Combined) {
            index.files.values.asSequence()
                .filter { file -> terms.all { file.pathTokens.contains(it) || file.nameTokens.contains(it) || file.name.lowercase().contains(it) } }
                .sortedBy { it.relativePath.length }
                .forEach { file ->
                    results += HostSearchResult(file.path, index.root, HostSearchMatchKind.FileName, preview = file.relativePath)
                }
        }
        if (mode == HostSearchMode.Content || mode == HostSearchMode.Combined) {
            index.files.values.asSequence()
                .filter { it.contentLines.isNotEmpty() }
                .forEach { file ->
                    file.contentLines.firstOrNull { line -> terms.all { line.textLower.contains(it) } }?.let { line ->
                        results += HostSearchResult(file.path, index.root, HostSearchMatchKind.Content, line.lineNumber, line.textLower.indexOf(terms.first()).takeIf { it >= 0 }?.plus(1), line.text)
                    }
                }
        }
        return results.distinctBy { "${it.kind}:${it.path}:${it.lineNumber}" }
    }

    private fun indexFile(root: String, file: File): IndexedFile {
        val relative = file.toPath().let { path ->
            runCatching { File(root).toPath().relativize(path).toString() }.getOrDefault(file.name)
        }
        val textLines = runCatching {
            val bytes = file.readBytes()
            if (bytes.size > MaxIndexedBytes || looksBinary(bytes)) emptyList() else bytes.toString(detectCharset(bytes)).lineSequence()
                .take(MaxIndexedLines)
                .mapIndexed { index, line -> IndexedLine(index + 1, line.take(MaxPreviewChars), line.lowercase().take(MaxPreviewChars)) }
                .filter { it.text.isNotBlank() }
                .toList()
        }.getOrDefault(emptyList())
        val name = file.name
        return IndexedFile(
            path = file.absolutePath,
            relativePath = relative,
            name = name,
            modifiedMillis = file.lastModified(),
            sizeBytes = file.length(),
            extension = file.extension,
            languageHint = languageHintForFile(file),
            nameTokens = tokenize(name),
            pathTokens = tokenize(relative),
            contentLines = textLines,
        )
    }

    private fun startWatcher(root: String) {
        watchers[root]?.close()
        val rootPath = File(root).toPath()
        if (!rootPath.isDirectory()) return
        val watchService = FileSystems.getDefault().newWatchService()
        val keys = ConcurrentHashMap<WatchKey, Path>()
        fun register(dir: Path) {
            runCatching {
                keys[dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)] = dir
            }
        }
        rootPath.walkDirectories { register(it) }
        val job = scope.launch {
            while (true) {
                val key = runCatching { watchService.take() }.getOrNull() ?: break
                val dir = keys[key]
                val changed = key.pollEvents().mapNotNull { event -> dir?.resolve(event.context() as Path) }
                key.reset()
                delay(350)
                changed.forEach { path ->
                    if (path.isDirectory()) path.walkDirectories { register(it) }
                    refreshPath(root, path)
                }
            }
        }
        watchers[root] = WatchHandle(watchService, job)
    }

    private fun refreshPath(root: String, path: Path) {
        val index = roots[root] ?: loadIndex(root)
        val file = path.toFile()
        if (!file.exists() || shouldExclude(path)) {
            index.files.remove(file.absolutePath)
        } else if (file.isFile) {
            index.files[file.absolutePath] = indexFile(root, file)
        }
        saveIndex(index)
        rootStatus(root).value = HostIndexStatus(root, index.files.size, index.files.values.sumOf { it.sizeBytes }, indexing = false, message = "Updated ${file.name}", updatedAtMillis = System.currentTimeMillis())
    }

    private fun refreshFileInIndexes(path: Path) {
        roots.keys.forEach { root ->
            if (path.toAbsolutePath().normalize().startsWith(File(root).toPath().toAbsolutePath().normalize())) {
                refreshPath(root, path)
            }
        }
    }

    private fun loadIndex(root: String): RootIndex {
        roots[root]?.let { return it }
        val file = indexFileFor(root)
        val entries = ConcurrentHashMap<String, IndexedFile>()
        if (file.isFile) {
            file.useLines { lines ->
                lines.drop(1).forEach { line ->
                    decodeIndexedFile(line)?.let { entries[it.path] = it }
                }
            }
        }
        val index = RootIndex(root, entries)
        roots[root] = index
        rootStatus(root).value = HostIndexStatus(root, entries.size, entries.values.sumOf { it.sizeBytes }, indexing = false, message = if (entries.isEmpty()) "Not indexed" else "Loaded ${entries.size} files", updatedAtMillis = System.currentTimeMillis())
        return index
    }

    private fun saveIndex(index: RootIndex) {
        indexDir.mkdirs()
        val file = indexFileFor(index.root)
        file.printWriter().use { writer ->
            writer.println("andy-index-v1")
            index.files.values.sortedBy { it.path }.forEach { writer.println(encodeIndexedFile(it)) }
        }
    }

    private fun encodeIndexedFile(file: IndexedFile): String {
        val lines = file.contentLines.joinToString("\u001e") {
            "${it.lineNumber}\u001f${encodeField(it.text)}"
        }
        return listOf(
            file.path,
            file.relativePath,
            file.name,
            file.modifiedMillis.toString(),
            file.sizeBytes.toString(),
            file.extension,
            file.languageHint,
            file.nameTokens.joinToString(" "),
            file.pathTokens.joinToString(" "),
            lines,
        ).joinToString("\t") { encodeField(it) }
    }

    private fun decodeIndexedFile(line: String): IndexedFile? {
        val parts = line.split('\t').map(::decodeField)
        if (parts.size < 10) return null
        val contentLines = parts[9].split('\u001e')
            .filter { it.isNotBlank() }
            .mapNotNull { encodedLine ->
                val pieces = encodedLine.split('\u001f', limit = 2)
                val number = pieces.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
                val text = pieces.getOrNull(1)?.let(::decodeField).orEmpty()
                IndexedLine(number, text, text.lowercase())
            }
        return IndexedFile(
            path = parts[0],
            relativePath = parts[1],
            name = parts[2],
            modifiedMillis = parts[3].toLongOrNull() ?: 0L,
            sizeBytes = parts[4].toLongOrNull() ?: 0L,
            extension = parts[5],
            languageHint = parts[6],
            nameTokens = parts[7].split(' ').filter { it.isNotBlank() }.toSet(),
            pathTokens = parts[8].split(' ').filter { it.isNotBlank() }.toSet(),
            contentLines = contentLines,
        )
    }

    private fun rootStatus(root: String): MutableStateFlow<HostIndexStatus> = statuses.getOrPut(root) {
        MutableStateFlow(HostIndexStatus(root, 0, 0, indexing = false, message = "Not indexed", updatedAtMillis = System.currentTimeMillis()))
    }

    private fun indexFileFor(root: String): File = File(indexDir, "${sha256(root)}.tsv")

    private data class RootIndex(val root: String, val files: MutableMap<String, IndexedFile>)

    private data class IndexedFile(
        val path: String,
        val relativePath: String,
        val name: String,
        val modifiedMillis: Long,
        val sizeBytes: Long,
        val extension: String,
        val languageHint: String,
        val nameTokens: Set<String>,
        val pathTokens: Set<String>,
        val contentLines: List<IndexedLine>,
    )

    private data class IndexedLine(val lineNumber: Int, val text: String, val textLower: String)

    private data class WatchHandle(val watchService: WatchService, val job: kotlinx.coroutines.Job) {
        fun close() {
            job.cancel()
            runCatching { watchService.close() }
        }
    }

    companion object {
        private const val MaxIndexedBytes = 1_000_000
        private const val MaxIndexedLines = 8_000
        private const val MaxPreviewChars = 500
    }
}

private val ExcludedNames = setOf(
    ".git", ".gradle", "build", "node_modules", ".idea", ".cache", "target", "dist",
    "out", ".next", ".nuxt", ".venv", "venv", "__pycache__", ".tox", ".parcel-cache",
)

private fun Path.walkIndexedFiles(onFile: (Path) -> Unit) {
    if (!exists()) return
    Files.walk(this).use { stream ->
        stream.asSequence()
            .filter { it.isRegularFile() && !shouldExclude(it) }
            .forEach(onFile)
    }
}

private fun Path.walkDirectories(onDirectory: (Path) -> Unit) {
    if (!exists()) return
    Files.walk(this).use { stream ->
        stream.asSequence()
            .filter { it.isDirectory() && !shouldExclude(it) }
            .forEach(onDirectory)
    }
}

private fun shouldExclude(path: Path): Boolean = path.asSequence().any { it.name in ExcludedNames }

private fun File.toHostFileEntry(): HostFileEntry = HostFileEntry(
    path = absolutePath,
    name = name,
    isDirectory = isDirectory,
    sizeBytes = if (isFile) length() else 0L,
    modifiedMillis = lastModified(),
    extension = extension,
    languageHint = languageHintForFile(this),
)

private fun languageHintForFile(file: File): String {
    val lowerName = file.name.lowercase()
    return when (lowerName) {
        "dockerfile", "containerfile" -> "dockerfile"
        "makefile" -> "makefile"
        ".env" -> "properties"
        else -> languageHint(file.extension)
    }
}

private fun normalizeRoot(root: String): String = File(root.ifBlank { System.getProperty("user.home") }).absoluteFile.normalize().absolutePath

private fun tokenize(value: String): Set<String> = value.lowercase()
    .split(Regex("[^a-z0-9_.$-]+|(?=[A-Z])"))
    .map { it.trim('.', '-', '_') }
    .filter { it.length >= 2 }
    .toSet()

private fun looksBinary(bytes: ByteArray): Boolean = bytes.take(4096).any { it == 0.toByte() }

private fun detectCharset(bytes: ByteArray): Charset = when {
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8
    else -> StandardCharsets.UTF_8
}

private fun languageHint(extension: String): String = when (extension.lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "gradle", "groovy" -> "groovy"
    "js", "jsx", "mjs", "cjs" -> "javascript"
    "ts", "tsx" -> "typescript"
    "json" -> "json"
    "xml" -> "xml"
    "html", "htm" -> "html"
    "css", "scss", "sass" -> "css"
    "md", "markdown" -> "markdown"
    "py" -> "python"
    "rb" -> "ruby"
    "rs" -> "rust"
    "go" -> "go"
    "c", "h" -> "c"
    "cpp", "cc", "cxx", "hpp" -> "cpp"
    "sh", "bash", "zsh" -> "shell"
    "yml", "yaml" -> "yaml"
    "toml" -> "toml"
    "sql" -> "sql"
    "properties", "props" -> "properties"
    "ini", "conf", "cfg" -> "ini"
    "dockerfile" -> "dockerfile"
    "mk", "mak" -> "makefile"
    "csv" -> "csv"
    else -> "text"
}

private fun encodeField(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeField(value: String): String = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
