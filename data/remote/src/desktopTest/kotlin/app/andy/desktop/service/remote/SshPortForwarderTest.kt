package app.andy.desktop.service.remote

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshPortForwarderTest {
    @Test
    fun prefersSameLocalPortWhenFree() {
        val calls = mutableListOf<List<String>>()
        val forwarder = SshPortForwarder(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            sshControl = { extra -> calls += extra; true },
            isLocalPortFree = { true },
            allocateLocalPort = { error("should not allocate") },
        )
        assertEquals(8080, forwarder.forward(8080))
        assertEquals(mapOf(8080 to 8080), forwarder.mapping())
        assertTrue(calls.single().contains("-L"))
        assertTrue(calls.single().any { it == "8080:127.0.0.1:8080" })
    }

    @Test
    fun allocatesFallbackWhenPreferredPortBusy() {
        val forwarder = SshPortForwarder(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            sshControl = { true },
            isLocalPortFree = { false },
            allocateLocalPort = { 15_001 },
        )
        assertEquals(15_001, forwarder.forward(8080))
        assertEquals(mapOf(8080 to 15_001), forwarder.mapping())
        assertEquals(15_001, forwarder.localPortFor(8080))
    }

    @Test
    fun forwardIsIdempotentForSameRemotePort() {
        val calls = AtomicInteger(0)
        val forwarder = SshPortForwarder(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            sshControl = { calls.incrementAndGet(); true },
            isLocalPortFree = { true },
        )
        assertEquals(3000, forwarder.forward(3000))
        assertEquals(3000, forwarder.forward(3000))
        assertEquals(1, calls.get())
    }

    @Test
    fun releaseCancelsAndClearsMapping() {
        val cancelled = ConcurrentHashMap.newKeySet<String>()
        val forwarder = SshPortForwarder(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            sshControl = { extra ->
                if (extra.contains("cancel")) {
                    cancelled += extra.first { it.contains(':') }
                }
                true
            },
            isLocalPortFree = { false },
            allocateLocalPort = { 15_042 },
        )
        forwarder.forward(5900)
        forwarder.release(5900)
        assertTrue(forwarder.mapping().isEmpty())
        assertTrue(cancelled.contains("15042:127.0.0.1:5900"))
        // Second release is a no-op.
        forwarder.release(5900)
        assertEquals(1, cancelled.size)
    }

    @Test
    fun releaseAllClearsEveryForward() {
        val cancelled = mutableListOf<String>()
        val ports = AtomicInteger(15_100)
        val forwarder = SshPortForwarder(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            sshControl = { extra ->
                if (extra.contains("cancel")) {
                    cancelled += extra.first { it.contains(':') }
                }
                true
            },
            isLocalPortFree = { false },
            allocateLocalPort = { ports.getAndIncrement() },
        )
        forwarder.forward(8080)
        forwarder.forward(5173)
        forwarder.releaseAll()
        assertTrue(forwarder.mapping().isEmpty())
        assertEquals(2, cancelled.size)
    }

    @Test
    fun forwardExactDoesNotFallback() {
        val forwarder = SshPortForwarder(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            sshControl = { false },
            isLocalPortFree = { true },
            allocateLocalPort = { 15_999 },
        )
        assertFalse(forwarder.forwardExact(27183))
        assertTrue(forwarder.mapping().isEmpty())
    }

    @Test
    fun forwardFailsWhenAllocatedBindAlsoFails() {
        val forwarder = SshPortForwarder(
            target = "user@host",
            controlPath = File("/tmp/andy-test-mux"),
            sshControl = { false },
            isLocalPortFree = { false },
            allocateLocalPort = { 15_777 },
        )
        assertFailsWith<IllegalStateException> { forwarder.forward(9000) }
    }
}
