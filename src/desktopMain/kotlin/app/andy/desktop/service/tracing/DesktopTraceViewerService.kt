package app.andy.desktop.service.tracing

import app.andy.service.CommandResult
import app.andy.service.TraceViewerService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Serves local Perfetto traces and opens them in the system browser via a same-origin
 * host page that postMessages the bytes into ui.perfetto.dev (browsers block the old
 * https://ui.perfetto.dev?url=http://127.0.0.1… deep link).
 */
class DesktopTraceViewerService(
    private val tracesDir: File = File(System.getProperty("user.home"), ".andy/traces"),
) : TraceViewerService {
    private val serverMutex = Mutex()
    @Volatile private var server: EmbeddedServer<*, *>? = null
    @Volatile private var serverPort: Int = 0

    override suspend fun openExternally(traceId: String): CommandResult = withContext(Dispatchers.IO) {
        val url = localViewerUrl(traceId) ?: return@withContext CommandResult.failure("Trace not found")
        runCatching {
            Desktop.getDesktop().browse(URI(url))
            CommandResult.success(url)
        }.getOrElse { CommandResult.failure(it.message ?: "Failed to open browser") }
    }

    override fun shutdown() {
        runCatching { server?.stop(1000, 2000) }
        server = null
        serverPort = 0
    }

    private suspend fun localViewerUrl(traceId: String): String? {
        val file = File(tracesDir, "$traceId.perfetto-trace")
        if (!file.isFile) return null
        val port = ensureServer()
        val name = URLEncoder.encode(file.name, StandardCharsets.UTF_8)
        return "http://127.0.0.1:$port/view?name=$name"
    }

    internal suspend fun ensureServer(): Int = serverMutex.withLock {
        server?.let { return serverPort }
        tracesDir.mkdirs()
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/view") {
                    val name = call.request.queryParameters["name"].orEmpty()
                    if (!TRACE_NAME_REGEX.matches(name)) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid trace name")
                        return@get
                    }
                    val file = File(tracesDir, name).canonicalFile
                    val root = tracesDir.canonicalFile
                    if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
                        call.respond(HttpStatusCode.NotFound, "Trace not found")
                        return@get
                    }
                    call.respondText(openTraceHtml(fileName = name), ContentType.Text.Html)
                }
                get("/traces/{name}") {
                    val name = call.parameters["name"].orEmpty()
                    val file = File(tracesDir, name).canonicalFile
                    val root = tracesDir.canonicalFile
                    if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.respondFile(file)
                }
            }
        }
        engine.start(wait = false)
        server = engine
        serverPort = engine.engine.resolvedConnectors().first().port
        serverPort
    }

    /** Test helper: serves [tracesDir]. */
    suspend fun startServerForTests(): Int = ensureServer()

    companion object {
        private val TRACE_NAME_REGEX = Regex("""[\w.\-]+\.perfetto-trace""")

        internal fun openTraceHtml(fileName: String): String {
            val title = fileName.removeSuffix(".perfetto-trace")
            val safeName = fileName
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "")
                .replace("\r", "")
            val safeTitle = title
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "")
                .replace("\r", "")
            return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>$safeTitle — Andy Trace</title>
                  <style>
                    html, body, #perfetto { margin: 0; height: 100%; width: 100%; border: 0; }
                    body { background: #121212; color: #e8e8e8; font: 14px/1.4 -apple-system, BlinkMacSystemFont, sans-serif; }
                    #status { padding: 24px; max-width: 40rem; }
                    #status.error { color: #ffb4a9; }
                  </style>
                </head>
                <body>
                  <div id="status">Opening trace…</div>
                  <iframe id="perfetto" style="display:none" title="Perfetto"></iframe>
                  <script>
                    (async () => {
                      const status = document.getElementById('status');
                      const iframe = document.getElementById('perfetto');
                      const fileName = '$safeName';
                      const title = '$safeTitle';
                      try {
                        const resp = await fetch('/traces/' + encodeURIComponent(fileName));
                        if (!resp.ok) throw new Error('Trace HTTP ' + resp.status);
                        const buffer = await resp.arrayBuffer();
                        iframe.src = 'https://ui.perfetto.dev/#!/';
                        iframe.style.display = 'block';
                        status.style.display = 'none';
                        await new Promise((resolve, reject) => {
                          const started = Date.now();
                          const interval = setInterval(() => {
                            if (!iframe.contentWindow) return;
                            iframe.contentWindow.postMessage('PING', '*');
                            if (Date.now() - started > 60000) {
                              clearInterval(interval);
                              reject(new Error('Timed out waiting for Perfetto UI'));
                            }
                          }, 100);
                          window.addEventListener('message', function onMsg(evt) {
                            if (evt.source === iframe.contentWindow && evt.data === 'PONG') {
                              clearInterval(interval);
                              window.removeEventListener('message', onMsg);
                              resolve();
                            }
                          });
                        });
                        iframe.contentWindow.postMessage({
                          perfetto: {
                            buffer: buffer,
                            title: title,
                            fileName: fileName,
                            localOnly: false,
                            keepApiOpen: true,
                          }
                        }, '*');
                      } catch (error) {
                        status.style.display = 'block';
                        status.className = 'error';
                        status.textContent = 'Could not open trace: ' + (error && error.message ? error.message : error);
                        iframe.style.display = 'none';
                      }
                    })();
                  </script>
                </body>
                </html>
            """.trimIndent()
        }
    }
}
