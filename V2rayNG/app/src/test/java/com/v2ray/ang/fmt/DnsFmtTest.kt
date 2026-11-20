package com.v2ray.ang.fmt

import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsFmtTest {

    private fun profile(transport: String, host: String, port: String, path: String? = null) =
        ProfileItem.create(EConfigType.DNS).apply {
            remarks = "Test"
            server = host
            serverPort = port
            dnsTransport = transport
            this.path = path
        }

    @Test
    fun test_parse_doh_full_link() {
        val config = DnsFmt.parse("dns://1.1.1.1:443?transport=doh&path=/dns-query#Cloudflare")
        assertNotNull(config)
        assertEquals(EConfigType.DNS, config!!.configType)
        assertEquals("1.1.1.1", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("doh", config.dnsTransport)
        assertEquals("/dns-query", config.path)
        assertEquals("Cloudflare", config.remarks)
    }

    @Test
    fun test_parse_defaults_to_doh() {
        val config = DnsFmt.parse("dns://8.8.8.8")
        assertNotNull(config)
        assertEquals("doh", config!!.dnsTransport)
        assertEquals("443", config.serverPort)
        assertEquals("/dns-query", config.path)
        assertEquals("8.8.8.8:443", config.remarks)
    }

    @Test
    fun test_parse_udp_and_doq() {
        val udp = DnsFmt.parse("dns://1.1.1.1:53?transport=udp#CF%20UDP")
        assertNotNull(udp)
        assertEquals("udp", udp!!.dnsTransport)
        assertEquals("53", udp.serverPort)
        assertNull(udp.path)
        assertEquals("CF UDP", udp.remarks)

        val doq = DnsFmt.parse("dns://dns.adguard-dns.com:853?transport=doq#AdGuard")
        assertNotNull(doq)
        assertEquals("doq", doq!!.dnsTransport)
        assertEquals("dns.adguard-dns.com", doq.server)
        assertEquals("853", doq.serverPort)
    }

    @Test
    fun test_parse_invalid_returns_null() {
        assertNull(DnsFmt.parse("dns://"))
        assertNull(DnsFmt.parse("not a link"))
        // No DoT: Xray 26.7.28 has no DNS-over-TLS nameserver, so a DoT link is not imported as DoH.
        assertNull(DnsFmt.parse("dns://1.1.1.1:853?transport=dot#Cloudflare"))
        assertNull(DnsFmt.parse("dns://1.1.1.1:853?transport=tls#Cloudflare"))
    }

    @Test
    fun test_toUri_roundtrip() {
        for ((triple, path) in listOf(
            Triple("doh", "1.1.1.1", "443") to "/dns-query",
            Triple("udp", "1.1.1.1", "53") to null,
            Triple("doq", "dns.adguard-dns.com", "853") to null,
        )) {
            val (transport, host, port) = triple
            val config = profile(transport, host, port, path)
            val parsed = DnsFmt.parse("dns://${DnsFmt.toUri(config)}")
            assertNotNull(parsed)
            assertEquals(host, parsed!!.server)
            assertEquals(port, parsed.serverPort)
            assertEquals(transport, parsed.dnsTransport)
            assertEquals(path, parsed.path)
            assertEquals("Test", parsed.remarks)
        }
    }

    @Test
    fun test_normalize_fixes_port_and_path() {
        val config = profile("doh", " 1.1.1.1 ", "99999", "dns-query")
        assertTrue(DnsFmt.normalize(config))
        assertEquals("1.1.1.1", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("/dns-query", config.path)

        val udp = profile("udp", "1.1.1.1", "53", "/dns-query")
        assertTrue(DnsFmt.normalize(udp))
        assertNull(udp.path)
    }

    @Test
    fun test_normalize_rejects_unusable_hosts() {
        assertFalse(DnsFmt.normalize(profile("doh", "", "443")))
        assertFalse(DnsFmt.normalize(profile("doh", "localhost", "443")))
        assertFalse(DnsFmt.normalize(profile("doh", "https://1.1.1.1/dns-query", "443")))
        assertFalse(DnsFmt.normalize(profile("doh", "has space", "443")))
    }

    @Test
    fun test_toXrayAddress_doh_and_doq() {
        assertEquals("https://1.1.1.1/dns-query", DnsFmt.toXrayAddress(profile("doh", "1.1.1.1", "443", "/dns-query")))
        assertEquals("https://1.1.1.1:8443/custom", DnsFmt.toXrayAddress(profile("doh", "1.1.1.1", "8443", "/custom")))
        assertEquals("quic+local://dns.adguard-dns.com:853", DnsFmt.toXrayAddress(profile("doq", "dns.adguard-dns.com", "853")))
        assertEquals("quic+local://dns.adguard-dns.com:8853", DnsFmt.toXrayAddress(profile("doq", "dns.adguard-dns.com", "8853")))
    }

    @Test
    fun test_toXrayAddress_udp() {
        assertEquals("1.1.1.1", DnsFmt.toXrayAddress(profile("udp", "1.1.1.1", "53")))
        // Xray rejects a "host:port" string server, so a non-53 UDP port is an object.
        val nonDefault = DnsFmt.toXrayAddress(profile("udp", "1.1.1.1", "5353"))
        assertTrue(nonDefault is V2rayConfig.DnsBean.ServersBean)
        val bean = nonDefault as V2rayConfig.DnsBean.ServersBean
        assertEquals("1.1.1.1", bean.address)
        assertEquals(5353, bean.port)
    }
}
