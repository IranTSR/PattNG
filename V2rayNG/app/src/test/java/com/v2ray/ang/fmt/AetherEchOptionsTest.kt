package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The resolvers and the domains the ECH fields of the Aether editor and of the WARP keys page offer, read from the
 * string arrays of the app's resources, as the unit tests run in the module's folder: each is a value the core takes,
 * and the default comes first.
 */
class AetherEchOptionsTest {

    private fun options(name: String): List<String> {
        val arrays = File("src/main/res/values/arrays.xml")
        assertTrue("${arrays.absolutePath} is where the unit tests run from", arrays.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(arrays)
        val nodes = document.getElementsByTagName("string-array")
        val array = (0 until nodes.length).map { nodes.item(it) }.single { it.attributes.getNamedItem("name").nodeValue == name }
        val items = array.childNodes
        return (0 until items.length).map { items.item(it) }.filter { it.nodeName == "item" }.map { it.textContent.trim() }
    }

    @Test
    fun everyResolverOnOfferIsOneTheCoreTakesWithTheDefaultFirst() {
        val resolvers = options("aether_ech_dns_options")
        assertEquals(
            listOf(
                "udp://1.1.1.1",
                "udp://8.8.8.8",
                "https://1.1.1.1/dns-query@sni=www.microsoft.com",
                "https://doq.dns4all.eu/dns-query@address=194.0.5.3",
                "tcp://1.1.1.1",
                "tcp://8.8.8.8",
            ),
            resolvers
        )
        assertEquals(AppConfig.AETHER_ECH_DNS, resolvers.first())
        resolvers.forEach { assertTrue(it, AetherFmt.isEchDns(it)) }
    }

    @Test
    fun everyDomainOnOfferIsOneTheCoreTakesWithTheDefaultFirst() {
        val domains = options("aether_ech_domain_options")
        assertEquals(
            listOf(
                "cloudflare-ech.com",
                "crypto.cloudflare.com",
                "ip.gs",
                "api.cloudflareclient.com",
                "consumer-masque.cloudflareclient.com",
                "consumer-masque-proxy.cloudflareclient.com",
            ),
            domains
        )
        assertEquals(AppConfig.AETHER_ECH_DOMAIN, domains.first())
        domains.forEach { assertTrue(it, AetherFmt.isEchDomain(it)) }
    }
}
