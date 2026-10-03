package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.TlsSettingsCheck.Error
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The TLS settings the editor refuses to save, as the Xray-core fork would not apply them or would not connect. */
class TlsSettingsCheckTest {

    private fun profile(
        fingerprint: String? = null,
        cipherSuites: String? = null,
        network: String? = "ws",
        alpn: String? = null,
        type: EConfigType = EConfigType.VLESS,
        security: String? = AppConfig.TLS,
    ) = ProfileItem.create(type).apply {
        this.security = security
        this.fingerPrint = fingerprint
        this.cipherSuites = cipherSuites
        this.network = network
        this.alpn = alpn
    }

    private val suites = "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"

    @Test
    fun cipherSuitesNeedTheUnsafeFingerprint() {
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "unsafe", cipherSuites = suites)))
        // The fork lowercases the fingerprint.
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "Unsafe", cipherSuites = suites)))
        assertEquals(Error.CIPHER_SUITES_NEED_UNSAFE, TlsSettingsCheck.validate(profile(fingerprint = "chrome", cipherSuites = suites)))
        // An empty fingerprint is Chrome's.
        assertEquals(Error.CIPHER_SUITES_NEED_UNSAFE, TlsSettingsCheck.validate(profile(fingerprint = "", cipherSuites = suites)))
        assertEquals(Error.CIPHER_SUITES_NEED_UNSAFE, TlsSettingsCheck.validate(profile(fingerprint = null, cipherSuites = suites)))
        assertEquals(Error.CIPHER_SUITES_NEED_UNSAFE, TlsSettingsCheck.validate(profile(fingerprint = "randomized", cipherSuites = suites, network = "tcp")))
        // No cipherSuites, nothing to refuse.
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "chrome", cipherSuites = "")))
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "chrome", cipherSuites = " \n")))
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "chrome")))
    }

    @Test
    fun webSocketAndHttpUpgradeWithTheUnsafeFingerprintTakeNoH2AheadOfHttp1() {
        for (network in listOf("ws", "httpupgrade")) {
            for (alpn in listOf("h2", "h2,http/1.1", "h3,h2,http/1.1", "h3,h2", " h2 , http/1.1 ")) {
                assertEquals("$network $alpn", Error.H2_BEFORE_HTTP1, TlsSettingsCheck.validate(profile(fingerprint = "unsafe", network = network, alpn = alpn)))
            }
            for (alpn in listOf(null, "", "http/1.1", "h3", "http/1.1,h2", "http/1.1, h2", "h3,http/1.1,h2")) {
                assertNull("$network $alpn", TlsSettingsCheck.validate(profile(fingerprint = "unsafe", network = network, alpn = alpn)))
            }
        }
    }

    @Test
    fun theAlpnRuleKeepsToWebSocketAndHttpUpgradeWithTheUnsafeFingerprint() {
        // Other transports keep h2 ahead.
        for (network in listOf("tcp", "grpc", "xhttp", null)) {
            assertNull("$network", TlsSettingsCheck.validate(profile(fingerprint = "unsafe", network = network, alpn = "h2,http/1.1")))
        }
        // With the other fingerprints the fork picks the alpn of these transports itself.
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "chrome", network = "ws", alpn = "h2")))
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "", network = "httpupgrade", alpn = "h3,h2")))
        // cipherSuites with the unsafe fingerprint still meet the alpn rule.
        assertEquals(Error.H2_BEFORE_HTTP1, TlsSettingsCheck.validate(profile(fingerprint = "unsafe", cipherSuites = suites, network = "ws", alpn = "h2")))
    }

    @Test
    fun theCheckKeepsToTheEditorsThatShowTheseSettingsUnderTls() {
        for (type in listOf(EConfigType.VMESS, EConfigType.VLESS, EConfigType.SHADOWSOCKS, EConfigType.TROJAN)) {
            assertEquals("$type", Error.CIPHER_SUITES_NEED_UNSAFE, TlsSettingsCheck.validate(profile(fingerprint = "chrome", cipherSuites = suites, type = type)))
        }
        // Hysteria2's editor shows neither cipherSuites nor alpn, so a value an imported link left there is not refused.
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "chrome", cipherSuites = suites, type = EConfigType.HYSTERIA2)))
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "unsafe", alpn = "h2", type = EConfigType.HYSTERIA2)))
        // Nor under REALITY or without security, where the editor hides them.
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "chrome", cipherSuites = suites, security = AppConfig.REALITY)))
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "unsafe", alpn = "h2", security = "")))
        assertNull(TlsSettingsCheck.validate(profile(fingerprint = "chrome", cipherSuites = suites, security = null)))
    }
}
