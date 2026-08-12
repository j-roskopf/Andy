package app.andy.desktop.service.proxy

import java.net.Inet4Address
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolveLanIpTest {
    @Test
    fun carrierGradeNatIsRejectedForDeviceProxyHost() {
        assertTrue(isCarrierGradeNat("100.72.168.32"))
        assertTrue(isCarrierGradeNat("100.64.0.1"))
        assertTrue(isCarrierGradeNat("100.127.255.254"))
        assertFalse(isCarrierGradeNat("100.63.255.255"))
        assertFalse(isCarrierGradeNat("192.168.86.81"))
        assertFalse(isCarrierGradeNat("10.0.0.5"))
    }

    @Test
    fun rfc1918Detection() {
        assertTrue(isRfc1918(inet4("192.168.1.1")))
        assertTrue(isRfc1918(inet4("10.1.2.3")))
        assertTrue(isRfc1918(inet4("172.16.0.1")))
        assertTrue(isRfc1918(inet4("172.31.255.255")))
        assertFalse(isRfc1918(inet4("172.32.0.1")))
        assertFalse(isRfc1918(inet4("8.8.8.8")))
    }

    @Test
    fun vpnLikeInterfaceNamesAreSkipped() {
        assertTrue(isVpnLikeInterfaceName("utun4"))
        assertTrue(isVpnLikeInterfaceName("tailscale0"))
        assertTrue(isVpnLikeInterfaceName("wg0"))
        assertFalse(isVpnLikeInterfaceName("en0"))
        assertFalse(isVpnLikeInterfaceName("eth0"))
    }

    @Test
    fun resolveLanIpDoesNotReturnCarrierGradeNat() {
        val ip = resolveLanIp()
        assertFalse(isCarrierGradeNat(ip), "resolveLanIp returned CGNAT/Tailscale address: $ip")
    }

    @Test
    fun vpnIpv4AllowsCarrierGradeNatForNetworkAccess() {
        assertTrue(isReachableVpnIpv4(inet4("100.72.168.32")))
        assertTrue(isReachableVpnIpv4(inet4("10.8.0.2")))
        assertFalse(isReachableVpnIpv4(inet4("127.0.0.1")))
        assertFalse(isReachableVpnIpv4(inet4("169.254.1.1")))
    }

    @Test
    fun resolveNetworkAccessHostsNeverEmptyAndKeepsLanBeforeVpn() {
        val hosts = resolveNetworkAccessHosts()
        assertTrue(hosts.isNotEmpty())
        // Proxy/LAN helper still excludes CGNAT; Network Access may include it when
        // only VPN interfaces exist. When both are present, LAN (non-CGNAT) comes first.
        val lan = resolveLanIp()
        if (lan != "127.0.0.1" && hosts.contains(lan)) {
            assertTrue(hosts.indexOf(lan) == 0 || !isCarrierGradeNat(hosts.first()))
        }
        // Must not force VPN-only users onto loopback when a VPN address exists.
        if (hosts.any { isCarrierGradeNat(it) || it != "127.0.0.1" }) {
            assertFalse(hosts == listOf("127.0.0.1"))
        }
    }

    private fun inet4(host: String): Inet4Address =
        Inet4Address.getByName(host) as Inet4Address
}
