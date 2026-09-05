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
    fun muxAndBaseOptionsNeverRequestControlMasterAuto() {
        val path = File("/tmp/andy-test-mux")
        assertFalse(SshProcess.muxOptions(path).any { it.startsWith("ControlMaster=") })
        assertFalse(SshProcess.baseOptions(path).any { it.startsWith("ControlMaster=") })
        assertFalse(SshProcess.baseOptions(null).any { it.startsWith("ControlMaster=") })
        assertFalse(SshProcess.baseOptions(null).any { it.startsWith("ControlPath=") })
    }
}
