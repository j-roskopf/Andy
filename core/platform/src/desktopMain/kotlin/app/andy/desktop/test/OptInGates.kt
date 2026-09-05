package app.andy.desktop.test

import org.junit.Assume.assumeTrue

/**
 * Shared gates for opt-in / hardware-backed desktop tests.
 *
 * Prefer [assumeTrue] over silent `return` so CI reports show **skipped** rather than
 * a false green pass when the environment is missing.
 */
object OptInGates {
    fun requireProxyConformance() {
        assumeTrue(
            "Set ANDY_PROXY_CONFORMANCE=1 to run proxy conformance (enabled on PR CI)",
            envTruthy("ANDY_PROXY_CONFORMANCE"),
        )
    }

    fun requireDeviceSmoke() {
        assumeTrue(
            "Set ANDY_DEVICE_SMOKE=1 with an online Android device/emulator (not available on PR CI)",
            envTruthy("ANDY_DEVICE_SMOKE"),
        )
    }

    fun requireDeviceNativeSmoke() {
        assumeTrue(
            "Set ANDY_DEVICE_NATIVE_SMOKE=1 with an online Android device (not available on PR CI)",
            envTruthy("ANDY_DEVICE_NATIVE_SMOKE"),
        )
    }

    fun requireAgentE2E() {
        assumeTrue(
            "Set ANDY_AGENT_E2E=1 to run live vendor CLI agent smoke (costs usage; not on PR CI)",
            envTruthy("ANDY_AGENT_E2E"),
        )
    }

    fun requireSshLoopback() {
        assumeTrue(
            "Set ANDY_SSH_LOOPBACK=1 to run SSH ControlMaster loopback forward tests " +
                "(needs working `ssh 127.0.0.1` without a password prompt; not on PR CI)",
            envTruthy("ANDY_SSH_LOOPBACK"),
        )
    }

    fun requireIosSimSmokeUdid(udid: String?) {
        assumeTrue(
            "iOS sim smoke needs ANDY_IOS_SIM_SMOKE=1 and a Booted simulator (not on PR CI; can hang macOS runners)",
            !udid.isNullOrBlank(),
        )
    }

    private fun envTruthy(name: String): Boolean =
        when (System.getenv(name)?.lowercase()) {
            "1", "true", "yes" -> true
            else -> false
        }

    /** CI runners are slower for agent/workflow harness tests; macOS CI is slowest. */
    fun harnessTimeoutMillis(
        localMillis: Long,
        ciMillis: Long,
        ciMacMillis: Long = ciMillis,
    ): Long =
        when {
            System.getenv("CI") == null -> localMillis
            isMacOs() -> ciMacMillis
            else -> ciMillis
        }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")
}
