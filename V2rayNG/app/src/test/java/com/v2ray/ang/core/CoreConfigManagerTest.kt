package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreConfigManagerTest {

    private fun socks(address: String, port: Int) = V2rayConfig.OutboundBean(
        protocol = "socks",
        settings = V2rayConfig.OutboundBean.OutSettingsBean(address = address, port = port),
    )

    @Test
    fun onlyTheAetherOutboundsMoveToTheTestTunnelPort() {
        val aether = socks(AppConfig.LOOPBACK, AetherCoreManager.socksPort)
        val otherLocalSocks = socks(AppConfig.LOOPBACK, 1080)
        val remoteSocks = socks("10.0.0.1", AetherCoreManager.socksPort)
        val vless = V2rayConfig.OutboundBean(
            protocol = "vless",
            settings = V2rayConfig.OutboundBean.OutSettingsBean(address = "1.2.3.4", port = 443),
        )
        val bare = V2rayConfig.OutboundBean(protocol = "freedom")

        CoreConfigManager.rebindAetherOutbounds(listOf(aether, otherLocalSocks, remoteSocks, vless, bare), from = AetherCoreManager.socksPort, port = 41234)

        assertEquals(41234, aether.settings?.port)
        assertEquals(1080, otherLocalSocks.settings?.port)
        assertEquals(AetherCoreManager.socksPort, remoteSocks.settings?.port)
        assertEquals(443, vless.settings?.port)
    }

    @Test
    fun anAetherOutboundOnAPortOfItsOwnMovesFromThatPort() {
        val aether = socks(AppConfig.LOOPBACK, 20808)
        val defaultPort = socks(AppConfig.LOOPBACK, AetherCoreManager.socksPort)

        CoreConfigManager.rebindAetherOutbounds(listOf(aether, defaultPort), from = 20808, port = 41234)

        assertEquals(41234, aether.settings?.port)
        assertEquals(AetherCoreManager.socksPort, defaultPort.settings?.port)
    }

    @Test
    fun whatTheAetherCoreSendsOutLeavesThroughXray() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks")),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819), V2rayConfig.OutboundBean(tag = "direct", protocol = "freedom")),
            routing = V2rayConfig.RoutingBean(
                domainStrategy = "AsIs",
                rules = arrayListOf(V2rayConfig.RoutingBean.RulesBean(domain = listOf("geosite:private"), outboundTag = "direct")),
            ),
        )
        val core = CoreConfigManager.routeAetherThroughXray(config, AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!)

        val inbound = config.inbounds.last()
        assertEquals(AppConfig.TAG_SECONDARY_SOCKS, inbound.tag)
        assertEquals(10821, inbound.port)
        assertEquals("mixed", inbound.protocol)
        assertEquals(AppConfig.LOOPBACK, inbound.listen)
        assertEquals(true, inbound.settings?.udp)
        assertNull(inbound.sniffing)

        val outbound = config.outbounds.last()
        assertEquals(AppConfig.TAG_EXIT_NODE, outbound.tag)
        assertEquals("freedom", outbound.protocol)
        assertNull(outbound.mux)

        // What comes in on that inbound goes out by that outbound, before any other rule is asked.
        assertEquals(2, config.routing.rules.size)
        assertEquals(listOf(AppConfig.TAG_SECONDARY_SOCKS), config.routing.rules.first().inboundTag)
        assertEquals(AppConfig.TAG_EXIT_NODE, config.routing.rules.first().outboundTag)

        assertEquals("socks5://127.0.0.1:10821", core.arguments.last())
    }

    @Test
    fun aCoreWithAnUpstreamOfItsOwnLeavesTheConfigurationAlone() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819)),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )
        val own = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --upstream socks5://127.0.0.1:1080")!!

        assertEquals(own, CoreConfigManager.routeAetherThroughXray(config, own))
        assertTrue(config.inbounds.isEmpty())
        assertEquals(1, config.outbounds.size)
        assertTrue(config.routing.rules.isEmpty())
    }
}
