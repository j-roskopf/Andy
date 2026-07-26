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

    private fun inet4(host: String): Inet4Address =
        Inet4Address.getByName(host) as Inet4Address
}
