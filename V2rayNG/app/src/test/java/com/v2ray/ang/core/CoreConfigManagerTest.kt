package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.util.JsonUtil
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
        val core = CoreConfigManager.routeAetherThroughXray(config, AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!, 10822)!!

        val inbound = config.inbounds.last()
        assertEquals(AppConfig.TAG_SECONDARY_SOCKS, inbound.tag)
        assertEquals(10822, inbound.port)
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

        assertEquals("socks5://127.0.0.1:10822", core.arguments.last())
    }

    @Test
    fun aConfigurationThatListensOnTheSecondarySocksPortItselfIsLeftAsItIs() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10822, protocol = "socks")),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819)),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )

        assertNull(CoreConfigManager.routeAetherThroughXray(config, AetherCore.ofCommand("aether --bind 127.0.0.1:10819")!!, 10822))
        assertEquals(1, config.inbounds.size)
        assertEquals(1, config.outbounds.size)
        assertTrue(config.routing.rules.isEmpty())
    }

    @Test
    fun theExitNodeCarriesTheFinalMaskAndDialModeOfTheAetherProfile() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819)),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )
        val mask = """{"tcp": [{"type": "fragment"}]}"""
        val core = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!.copy(exit = AetherExit(mask, "code-1"))

        val routed = CoreConfigManager.routeAetherThroughXray(config, core, 10822)!!

        val exitNode = config.outbounds.last()
        assertEquals(AppConfig.TAG_EXIT_NODE, exitNode.tag)
        assertEquals("freedom", exitNode.protocol)
        assertEquals(JsonUtil.parseString(mask), exitNode.streamSettings?.finalmask)
        assertEquals("code-1", exitNode.streamSettings?.sockopt?.dialMode)
        // The outbound to the core stays as it was: what it reaches is on the loopback address.
        assertNull(config.outbounds.first().streamSettings?.sockopt?.dialMode)
        assertEquals(core.exit, routed.exit)
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

        assertEquals(own, CoreConfigManager.routeAetherThroughXray(config, own, 10822))
        assertTrue(config.inbounds.isEmpty())
        assertEquals(1, config.outbounds.size)
        assertTrue(config.routing.rules.isEmpty())
    }
}
