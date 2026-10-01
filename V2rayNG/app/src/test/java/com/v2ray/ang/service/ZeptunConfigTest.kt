package com.v2ray.ang.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeptunConfigTest {

    private val base = ZeptunConfig.Params(
        socksPort = 10808,
        socksUsername = null,
        socksPassword = null,
        mtu = 1500,
        logLevel = "warn",
    )

    @Test
    fun `toml uses mobile preset and socks5 handler`() {
        val toml = ZeptunConfig.buildToml(base)
        assertTrue(toml.contains("preset = \"mobile\""))
        assertTrue(toml.contains("kind = \"socks5\""))
        assertTrue(toml.contains("server = \"127.0.0.1:10808\""))
        // UdpMode is an enum in zeptun's TOML schema; a bare boolean is rejected.
        assertTrue(toml.contains("udp = \"enabled\""))
    }

    @Test
    fun `toml does not configure the tun interface`() {
        // VpnService.Builder.establish() already created and addressed it.
        val toml = ZeptunConfig.buildToml(base)
        assertTrue(toml.contains("configure = false"))
    }

    @Test
    fun `toml carries mtu and log level`() {
        val toml = ZeptunConfig.buildToml(base.copy(mtu = 1400, logLevel = "debug"))
        assertTrue(toml.contains("mtu = 1400"))
        assertTrue(toml.contains("log_level = \"debug\""))
    }

    @Test
    fun `toml omits auth when credentials are absent`() {
        val toml = ZeptunConfig.buildToml(base)
        assertFalse(toml.contains("username"))
        assertFalse(toml.contains("password"))
    }

    @Test
    fun `toml includes auth when credentials are present`() {
        val toml = ZeptunConfig.buildToml(base.copy(socksUsername = "user", socksPassword = "pass"))
        assertTrue(toml.contains("username = \"user\""))
        assertTrue(toml.contains("password = \"pass\""))
    }

    @Test
    fun `toml escapes quotes in credentials`() {
        val toml = ZeptunConfig.buildToml(base.copy(socksUsername = "a\"b", socksPassword = "c\\d"))
        assertTrue(toml.contains("username = \"a\\\"b\""))
        assertTrue(toml.contains("password = \"c\\\\d\""))
    }

    @Test
    fun `dns hijack stays off`() {
        val toml = ZeptunConfig.buildToml(base)
        assertTrue(toml.contains("hijack = false"))
    }

    @Test
    fun `log level maps hev error to zeptun err`() {
        // zeptun's log.Level enum has "err", not "error"; the unmapped name
        // made zeptun reject the config with ConfigError (rc=-16).
        assertTrue(ZeptunConfig.buildToml(base.copy(logLevel = "error")).contains("log_level = \"err\""))
        assertTrue(ZeptunConfig.buildToml(base.copy(logLevel = "warn")).contains("log_level = \"warn\""))
        assertTrue(ZeptunConfig.buildToml(base.copy(logLevel = "info")).contains("log_level = \"info\""))
        assertTrue(ZeptunConfig.buildToml(base.copy(logLevel = "debug")).contains("log_level = \"debug\""))
    }

    @Test
    fun `log level falls back to warn for unknown values`() {
        assertTrue(ZeptunConfig.buildToml(base.copy(logLevel = "bogus")).contains("log_level = \"warn\""))
        assertTrue(ZeptunConfig.buildToml(base.copy(logLevel = "")).contains("log_level = \"warn\""))
    }

    @Test
    fun `root json maps hev error to zeptun err`() {
        val json = ZeptunConfig.buildRootJson(rootBase.copy(logLevel = "error"))
        assertTrue(json.contains("\"log_level\": \"err\""))
    }

    // ---------------------------------------------------------- root JSON

    private val rootBase = ZeptunConfig.RootParams(
        socksPort = 10808,
        socksUsername = null,
        socksPassword = null,
        mtu = 1500,
        logLevel = "warn",
        tunName = "zeptun0",
        tunAddressV4 = "198.18.0.1/15",
    )

    @Test
    fun `root json uses mobile preset and configures the tun`() {
        val json = ZeptunConfig.buildRootJson(rootBase)
        assertTrue(json.contains("\"preset\": \"mobile\""))
        assertTrue(json.contains("\"name\": \"zeptun0\""))
        assertTrue(json.contains("\"configure\": true"))
        assertTrue(json.contains("\"address\": [\"198.18.0.1/15\"]"))
        assertTrue(json.contains("\"server\": \"127.0.0.1:10808\""))
        // Without auto_route the CLI would create the TUN but capture no traffic.
        assertTrue(json.contains("\"route\": { \"auto_route\": true }"))
    }

    @Test
    fun `root json is ipv4 only`() {
        val json = ZeptunConfig.buildRootJson(rootBase)
        assertFalse(json.contains("::"))
        assertFalse(json.contains("fd"))
    }

    @Test
    fun `root json includes auth when present`() {
        val json = ZeptunConfig.buildRootJson(rootBase.copy(socksUsername = "u", socksPassword = "p"))
        assertTrue(json.contains("\"username\": \"u\""))
        assertTrue(json.contains("\"password\": \"p\""))
    }

    @Test
    fun `root json omits auth when absent`() {
        val json = ZeptunConfig.buildRootJson(rootBase)
        assertFalse(json.contains("username"))
    }

    @Test
    fun `root json uses boolean udp unlike toml`() {
        // The JSON parser declares the socks5 udp field as a bool; the TOML
        // parser wants the enum string "enabled". Both enable UDP.
        val json = ZeptunConfig.buildRootJson(rootBase)
        assertTrue(json.contains("\"udp\": true"))
    }
}
