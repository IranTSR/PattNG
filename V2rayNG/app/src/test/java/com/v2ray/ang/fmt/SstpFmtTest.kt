package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.VpnGateImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SstpFmtTest {

    @Test
    fun test_parse_full_link() {
        val config = SstpFmt.parse("sstp://user1:pass1@example.com:443#My%20Server")
        assertNotNull(config)
        assertEquals(EConfigType.SSTP, config!!.configType)
        assertEquals("user1", config.username)
        assertEquals("pass1", config.password)
        assertEquals("example.com", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("My Server", config.remarks)
    }

    @Test
    fun test_parse_defaults_port_and_credentials() {
        val config = SstpFmt.parse("sstp://example.com#NoPort")
        assertNotNull(config)
        assertEquals("443", config!!.serverPort)
        assertEquals("vpn", config.username)
        assertEquals("vpn", config.password)
    }

    @Test
    fun test_parse_custom_port() {
        val config = SstpFmt.parse("sstp://vpn:vpn@vpn858819988.opengw.net:1257#VPNGate")
        assertNotNull(config)
        assertEquals("vpn858819988.opengw.net", config!!.server)
        assertEquals("1257", config.serverPort)
    }

    @Test
    fun test_parse_invalid_returns_null() {
        assertNull(SstpFmt.parse("sstp://"))
        assertNull(SstpFmt.parse("not a link"))
    }

    @Test
    fun test_toUri_roundtrip() {
        val config = ProfileItem.create(EConfigType.SSTP).apply {
            remarks = "Test"
            server = "example.com"
            serverPort = "443"
            username = "vpn"
            password = "vpn"
        }
        val uri = SstpFmt.toUri(config)
        val link = "sstp://$uri"
        assertTrue(link.startsWith("sstp://"))
        val parsed = SstpFmt.parse(link)
        assertNotNull(parsed)
        assertEquals("example.com", parsed!!.server)
        assertEquals("vpn", parsed.username)
    }

    @Test
    fun test_normalize_fixes_port_and_defaults() {
        val config = ProfileItem.create(EConfigType.SSTP).apply {
            server = " example.com "
            serverPort = "99999"
            username = ""
            password = ""
        }
        assertTrue(SstpFmt.normalize(config))
        assertEquals("example.com", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("vpn", config.username)
        assertEquals("vpn", config.password)
    }

    @Test
    fun test_normalize_rejects_empty_host() {
        val config = ProfileItem.create(EConfigType.SSTP).apply { server = "  " }
        assertFalse(SstpFmt.normalize(config))
    }
}

class VpnGateImporterTest {

    @Test
    fun test_parseSstpHosts_extracts_default_and_custom_ports() {
        val html = """
            <html><body><table>
            <tr><td>SSTP Hostname :<br><span>public-vpn-153.opengw.net</span></td></tr>
            <tr><td>SSTP Hostname : vpn858819988.opengw.net:1257</td></tr>
            <tr><td>SSTP Hostname :vpn827401311.opengw.net:1302</td></tr>
            <tr><td>No SSTP here</td></tr>
            </table></body></html>
        """.trimIndent()
        val hosts = VpnGateImporter.parseSstpHosts(html)
        assertEquals(3, hosts.size)
        assertTrue(hosts.contains("public-vpn-153.opengw.net" to 443))
        assertTrue(hosts.contains("vpn858819988.opengw.net" to 1257))
        assertTrue(hosts.contains("vpn827401311.opengw.net" to 1302))
    }

    @Test
    fun test_parseSstpHosts_dedupes_and_ignores_invalid_ports() {
        val html = """
            SSTP Hostname : public-vpn-153.opengw.net
            SSTP Hostname : public-vpn-153.opengw.net
            SSTP Hostname : bad.opengw.net:99999
        """.trimIndent()
        val hosts = VpnGateImporter.parseSstpHosts(html)
        assertEquals(2, hosts.size)
        assertTrue(hosts.contains("public-vpn-153.opengw.net" to 443))
        // Out-of-range port falls back to 443
        assertTrue(hosts.contains("bad.opengw.net" to 443))
    }

    @Test
    fun test_parseSstpHosts_empty() {
        assertTrue(VpnGateImporter.parseSstpHosts("<html></html>").isEmpty())
    }
}
