package app.andy.weblauncher;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class Main {
    public static final int PORT = 10_000;
    public static final URI ORIGIN = URI.create("http://localhost:" + PORT + "/");
    public static final String MARKER = "andy-web-local-launcher";
    public static final String CSP = String.join("; ",
        "default-src 'self'",
        "base-uri 'none'",
        "object-src 'none'",
        "frame-ancestors 'none'",
        "script-src 'self' 'wasm-unsafe-eval'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob:",
        "media-src 'self' blob:",
        "worker-src 'self' blob:",
        "connect-src 'self' ws://127.0.0.1:8037"
    );

    private Main() {}

    public static void main(String[] args) throws Exception {
        try {
            HttpServer server = start(PORT);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "andy-web-shutdown"));
            openBrowser(ORIGIN);
            System.out.println("Andy Web is running at " + ORIGIN);
        } catch (java.net.BindException occupied) {
            if (isAndyRunning(ORIGIN.toURL())) {
                openBrowser(ORIGIN);
                System.out.println("Andy Web is already running at " + ORIGIN);
                return;
            }
            throw new IOException("Port 10000 is already in use by another application. Andy Web requires exactly http://localhost:10000.", occupied);
        }
    }

    public static HttpServer start(int port) throws IOException {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/", Main::serve);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), runnable -> {
            Thread thread = new Thread(runnable, "andy-web-http");
            thread.setDaemon(false);
            return thread;
        }));
        server.start();
        return server;
    }

    private static void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            if (path.contains("..") || path.endsWith("/")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            try (InputStream input = Main.class.getResourceAsStream("/web" + path)) {
                if (input == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = input.readAllBytes();
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", mimeType(path));
                headers.set("Content-Security-Policy", CSP);
                headers.set("Cross-Origin-Opener-Policy", "same-origin");
                headers.set("X-Content-Type-Options", "nosniff");
                headers.set("Referrer-Policy", "no-referrer");
                headers.set("Cache-Control", path.equals("/index.html") ? "no-cache" : "public, max-age=31536000, immutable");
                if (path.equals("/index.html")) headers.set("X-Andy-Web", MARKER);
                exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : bytes.length);
                if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(bytes);
            }
        }
    }

    public static String mimeType(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        if (value.endsWith(".html")) return "text/html; charset=utf-8";
        if (value.endsWith(".js") || value.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (value.endsWith(".wasm")) return "application/wasm";
        if (value.endsWith(".json")) return "application/json; charset=utf-8";
        if (value.endsWith(".css")) return "text/css; charset=utf-8";
        if (value.endsWith(".png")) return "image/png";
        if (value.endsWith(".svg")) return "image/svg+xml";
        if (value.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    static boolean isAndyRunning(URL url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(800);
            connection.setReadTimeout(800);
            connection.setInstanceFollowRedirects(false);
            boolean running = connection.getResponseCode() == 200 && MARKER.equals(connection.getHeaderField("X-Andy-Web"));
            if (running) {
                try (InputStream input = connection.getInputStream()) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
            }
            return running;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void openBrowser(URI uri) {
        if (!Desktop.isDesktopSupported()) return;
        try {
            Desktop.getDesktop().browse(uri);
        } catch (Exception error) {
            System.err.println("Open " + uri + " in Chrome or Edge. Browser launch failed: " + error.getMessage());
        }
    }
}
