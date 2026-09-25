package com.v2ray.ang.snispoof

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SniSpoofManagerTest {

    @Test
    fun tcpProtocolsAreSupportedAndUdpOnesAreNot() {
        listOf(
            EConfigType.VMESS,
            EConfigType.VLESS,
            EConfigType.TROJAN,
            EConfigType.SHADOWSOCKS,
            EConfigType.SOCKS,
            EConfigType.HTTP,
        ).forEach { assertTrue(it.name, SniSpoofManager.isSupportedType(it)) }

        listOf(
            EConfigType.WIREGUARD,
            EConfigType.HYSTERIA2,
            EConfigType.AETHER,
            EConfigType.CUSTOM,
        ).forEach { assertFalse(it.name, SniSpoofManager.isSupportedType(it)) }
    }

    @Test
    fun enabledForNeedsTheFlagAndASupportedType() {
        val on = ProfileItem.create(EConfigType.TROJAN).apply { sniSpoofEnabled = true }
        assertTrue(SniSpoofManager.isEnabledFor(on))

        val off = ProfileItem.create(EConfigType.TROJAN).apply { sniSpoofEnabled = false }
        assertFalse(SniSpoofManager.isEnabledFor(off))

        val unset = ProfileItem.create(EConfigType.TROJAN)
        assertFalse(SniSpoofManager.isEnabledFor(unset))

        val wireguard = ProfileItem.create(EConfigType.WIREGUARD).apply { sniSpoofEnabled = true }
        assertFalse(SniSpoofManager.isEnabledFor(wireguard))
    }

    @Test
    fun buildArgsIncludesEveryFlag() {
        val profile = ProfileItem.create(EConfigType.TROJAN).apply {
            server = "203.0.113.7"
            serverPort = "443"
            sniSpoofFakeSni = "hcaptcha.com"
            sniSpoofUtls = "firefox"
            sniSpoofInjector = "passive"
        }

        assertEquals(
            listOf(
                "-listen", "127.0.0.1:40443",
                "-connect", "203.0.113.7:443",
                "-fake-sni", "hcaptcha.com",
                "-utls", "firefox",
                "-injector", "passive",
            ),
            SniSpoofManager.buildArgs(profile, 40443)
        )
    }

    @Test
    fun blankFakeSniOmitsTheFlagSoTheSidecarFallsBackToTheUpstreamHostname() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            server = "example.com"
            serverPort = "443"
            sniSpoofFakeSni = "   "
        }

        val args = SniSpoofManager.buildArgs(profile, 40443)
        assertFalse(args.contains("-fake-sni"))
        assertEquals(
            listOf("-utls", AppConfig.SNI_SPOOF_DEFAULT_UTLS, "-injector", AppConfig.SNI_SPOOF_DEFAULT_INJECTOR),
            args.takeLast(4)
        )
    }

    @Test
    fun utlsAndInjectorNormalizeToSafeValues() {
        assertEquals("chrome", SniSpoofManager.normalizeUtls(null))
        assertEquals("chrome", SniSpoofManager.normalizeUtls("  "))
        assertEquals("firefox", SniSpoofManager.normalizeUtls("firefox"))

        assertEquals("active", SniSpoofManager.normalizeInjector(null))
        assertEquals("active", SniSpoofManager.normalizeInjector("nonsense"))
        assertEquals("passive", SniSpoofManager.normalizeInjector("PASSIVE"))
        assertEquals("active", SniSpoofManager.normalizeInjector("ACTIVE"))
    }
}
