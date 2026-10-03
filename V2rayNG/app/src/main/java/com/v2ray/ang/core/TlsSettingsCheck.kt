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
         * h2 ahead of http/1.1 in the alpn of WebSocket or HTTPUpgrade with the unsafe fingerprint: Go's crypto/tls
         * offers the alpn as written, and through a CDN such as Cloudflare these transports then do not connect. With
         * the other fingerprints the fork offers http/1.1 for them, unless alpn is h2,http/1.1 exactly.
         */
        H2_BEFORE_HTTP1,
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
        if (upgrade && unsafe && h2BeforeHttp1(profile.alpn)) return Error.H2_BEFORE_HTTP1
        return null
    }

    /** Whether [alpn], read as CoreOutboundBuilder reads it, offers h2 with no http/1.1 ahead of it. */
    private fun h2BeforeHttp1(alpn: String?): Boolean {
        val protocols = alpn?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val h2 = protocols.indexOf("h2")
        val http1 = protocols.indexOf("http/1.1")
        return h2 >= 0 && (http1 < 0 || h2 < http1)
    }
}
