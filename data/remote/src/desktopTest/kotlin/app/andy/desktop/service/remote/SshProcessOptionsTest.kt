package app.andy.desktop.service.remote

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshProcessOptionsTest {
    @Test
    fun masterOptionsRequestExclusiveControlMaster() {
        val path = File("/tmp/andy-test-mux")
        val opts = SshProcess.masterOptions(path)
        assertTrue(opts.contains("ControlMaster=yes"))
        assertTrue(opts.contains("ControlPersist=no"))
        assertTrue(opts.any { it.startsWith("ControlPath=") })
        assertFalse(opts.contains("ControlMaster=auto"))
    }

    @Test
    fun batchOptionsRefuseToPromptAndKeepTheProbeShort() {
        val opts = SshProcess.batchOptions(null)
        assertTrue(opts.contains("BatchMode=yes"))
        assertTrue(opts.contains("ConnectTimeout=8"))
        // ssh honours the first value for a repeated option — the probe timeout must win.
        assertFalse(opts.contains("ConnectTimeout=20"))
        assertFalse(opts.any { it.startsWith("ControlPath=") })
    }

    @Test
    fun credentialProbeOptionsAllowPasswordAuthButBoundTheAttempt() {
        val opts = SshProcess.credentialProbeOptions(null)
        // BatchMode would rule password auth out entirely, which is the whole point of this path.
        assertFalse(opts.contains("BatchMode=yes"))
        assertTrue(opts.contains("NumberOfPasswordPrompts=1"))
        assertTrue(opts.contains("ConnectTimeout=8"))
    }

    @Test
    fun probeOptionsReuseAnExistingMasterWhenGiven() {
        val path = File("/tmp/andy-test-mux")
        assertTrue(SshProcess.batchOptions(path).contains("ControlPath=${path.absolutePath}"))
        assertTrue(SshProcess.credentialProbeOptions(path).contains("ControlPath=${path.absolutePath}"))
        assertFalse(SshProcess.batchOptions(path).any { it.startsWith("ControlMaster=") })
    }

    @Test
    fun muxAndBaseOptionsNeverRequestControlMasterAuto() {
        val path = File("/tmp/andy-test-mux")
        assertFalse(SshProcess.muxOptions(path).any { it.startsWith("ControlMaster=") })
        assertFalse(SshProcess.baseOptions(path).any { it.startsWith("ControlMaster=") })
        assertFalse(SshProcess.baseOptions(null).any { it.startsWith("ControlMaster=") })
        assertFalse(SshProcess.baseOptions(null).any { it.startsWith("ControlPath=") })
    }
}
