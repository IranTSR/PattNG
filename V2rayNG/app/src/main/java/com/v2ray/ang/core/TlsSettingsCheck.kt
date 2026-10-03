package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import java.util.Locale

/**
 * PattNG: TLS settings of a profile that the Xray-core fork would not apply, or with which it would not connect, so
 * that the editor does not save them. Read from the fork's transport/internet/tls and its WebSocket and HTTPUpgrade
 * dialers.
 */
object TlsSettingsCheck {

    /** The fingerprint with which the fork builds the ClientHello with Go's crypto/tls rather than with uTLS. */
    const val UNSAFE_FINGERPRINT = "unsafe"

    enum class Error {
        /**
         * cipherSuites with a fingerprint other than unsafe: uTLS then builds the ClientHello, and the fork passes it no
         * cipher suites. An empty fingerprint is Chrome's.
         */
        CIPHER_SUITES_NEED_UNSAFE,

        /**
         * An alpn other than http/1.1 alone for WebSocket or HTTPUpgrade, whatever the fingerprint. These transports
         * speak HTTP/1.1 after the handshake whatever it negotiated, and Cloudflare picks h2 whenever it is offered,
         * even after http/1.1; the unsafe fingerprint offers the alpn as written, the others offer h2,http/1.1 when it
         * is written so; and some Cloudflare hosts refuse h3 alone. With no alpn they offer http/1.1.
         */
        WEBSOCKET_ALPN_NOT_HTTP1,
    }

    /**
     * Whether the check applies to [profile], which is where the editor shows cipherSuites, alpn and the fingerprint:
     * under TLS on VMess, VLESS, Shadowsocks and Trojan.
     */
    fun appliesTo(profile: ProfileItem): Boolean = when (profile.configType) {
        EConfigType.VMESS, EConfigType.VLESS, EConfigType.SHADOWSOCKS, EConfigType.TROJAN ->
            profile.security == AppConfig.TLS
        else -> false
    }

    /** @return null when the check does not apply to [profile] or its TLS settings are ones the fork applies. */
    fun validate(profile: ProfileItem): Error? {
        if (!appliesTo(profile)) return null
        // The fork lowercases the fingerprint before it looks it up.
        val unsafe = profile.fingerPrint?.lowercase(Locale.ROOT) == UNSAFE_FINGERPRINT
        if (!profile.cipherSuites.isNullOrBlank() && !unsafe) return Error.CIPHER_SUITES_NEED_UNSAFE
        val upgrade = profile.network == NetworkType.WS.type || profile.network == NetworkType.HTTP_UPGRADE.type
        if (upgrade && !isHttp1OrNone(profile.alpn)) return Error.WEBSOCKET_ALPN_NOT_HTTP1
        return null
    }

    /** Whether [alpn], read as CoreOutboundBuilder reads it, is http/1.1 alone or nothing at all. */
    private fun isHttp1OrNone(alpn: String?): Boolean {
        val protocols = alpn?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return protocols.isEmpty() || protocols == listOf("http/1.1")
    }
}
