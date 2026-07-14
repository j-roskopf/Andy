package app.andy.weblauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void servesBundledWebAppWithSecurityHeaders() throws Exception {
        HttpServer server = Main.start(0);
        try {
            int port = server.getAddress().getPort();
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            assertEquals(200, connection.getResponseCode());
            assertEquals(Main.MARKER, connection.getHeaderField("X-Andy-Web"));
            assertEquals(Main.CSP, connection.getHeaderField("Content-Security-Policy"));
            assertTrue(new String(connection.getInputStream().readAllBytes()).contains("Andy"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bindsOnlyToLoopbackAndDoesNotFallbackFromAnOccupiedPort() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            assertTrue(occupied.getLocalSocketAddress().toString().contains("127.0.0.1"));
            boolean failed = false;
            try {
                Main.start(occupied.getLocalPort());
            } catch (java.net.BindException expected) {
                failed = true;
            }
            assertTrue(failed);
        }
    }

    @Test
    void declaresCorrectWasmAndModuleMimeTypes() {
        assertEquals("application/wasm", Main.mimeType("andy.wasm"));
        assertEquals("text/javascript; charset=utf-8", Main.mimeType("andy.mjs"));
        assertFalse(Main.CSP.contains("https:"));
        assertFalse(Main.CSP.contains("wss:"));
    }

    @Test
    void bundledClientContainsLockedBridgeAndApiContracts() throws Exception {
        try (var input = Main.class.getResourceAsStream("/web/andy-web-adb.mjs")) {
            assertTrue(input != null);
            String bundle = new String(input.readAllBytes());
            assertTrue(bundle.contains("const BRIDGE_URL = \"ws://127.0.0.1:8037/adb\""));
            assertTrue(bundle.contains("`Failed to connect ${BRIDGE_URL}. Instructions:`"));
            assertFalse(bundle.contains("Failed to connect to ws://127.0.0.1:8037/adb"));
            assertTrue(bundle.contains("adb start-server"));
            assertTrue(bundle.contains("github.com/j-roskopf/Andy/releases/latest/download/andy-tracebox"));
            assertTrue(bundle.contains("chmod +x ./andy-tracebox"));
            assertTrue(bundle.contains("./andy-tracebox"));
            assertFalse(bundle.contains("get.perfetto.dev/tracebox"));
            assertTrue(bundle.contains("requires Android API 30 or newer"));
            assertTrue(bundle.contains("WebUSB could not claim the Android USB interface"));
            assertTrue(bundle.contains("adb kill-server"));
            assertTrue(bundle.contains("Keep adb stopped while using WebUSB"));
            assertTrue(bundle.contains("requestedMaxSize === 0 ? 720"));
        }
    }
}
