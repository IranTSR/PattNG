package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import java.net.URI

/**
 * Link format for DNS-only profiles (Intra-style): the app runs no proxy, only the resolver
 * named here answers the device's DNS, and all other traffic goes direct.
 *
 * dns://<host>:<port>?transport=doh&path=/dns-query#<remarks>
 *
 * Transports are UDP, DoH and DoQ only. There is no DoT: Xray 26.7.28 has no DNS-over-TLS
 * nameserver (its address dispatch knows https, quic+local, tcp and plain UDP, and treats a
 * "tls://" address as a UDP host, which never answers), so a DoT entry would ship broken.
 * Verified against the real 26.7.28 binary: DoH answers, quic+local:// is accepted as a QUIC
 * client, and a "host:port" string server is rejected, so UDP on a non-53 port is emitted as
 * an {address, port} object instead.
 */
object DnsFmt : FmtBase() {

    const val TRANSPORT_UDP = "udp"
    const val TRANSPORT_DOH = "doh"
    const val TRANSPORT_DOQ = "doq"
    const val DEFAULT_TRANSPORT = TRANSPORT_DOH
    const val DEFAULT_DOH_PATH = "/dns-query"

    /** The transports the editor offers, in the order it offers them. */
    val TRANSPORTS = listOf(TRANSPORT_UDP, TRANSPORT_DOH, TRANSPORT_DOQ)

    fun defaultPort(transport: String): Int = when (transport) {
        TRANSPORT_UDP -> 53
        TRANSPORT_DOH -> 443
        TRANSPORT_DOQ -> 853
        else -> 443
    }

    /** The transport [transport] names, or the default when it names none the core supports. */
    fun normalizeTransport(transport: String?): String =
        transport?.trim()?.lowercase()?.takeIf { it in TRANSPORTS } ?: DEFAULT_TRANSPORT

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.DNS)
        val uri = try {
            URI(Utils.fixIllegalUrl(str))
        } catch (_: Exception) {
            return null
        }
        val queryParam = if (uri.rawQuery.isNullOrEmpty()) emptyMap() else getQueryParam(uri)
        // An explicit transport the core cannot run (notably "dot": Xray 26.7.28 has no DoT
        // nameserver) is a link this build must not import as something else.
        val rawTransport = queryParam["transport"]?.trim()?.lowercase()
        if (rawTransport != null && rawTransport !in TRANSPORTS) return null
        val transport = normalizeTransport(rawTransport)
        val host = uri.idnHost?.takeIf { it.isNotBlank() } ?: return null
        config.server = host
        config.dnsTransport = transport
        config.serverPort = uri.port.takeIf { it > 0 }?.toString() ?: defaultPort(transport).toString()
        config.path = if (transport == TRANSPORT_DOH) {
            queryParam["path"]?.takeIf { it.isNotBlank() } ?: DEFAULT_DOH_PATH
        } else {
            null
        }
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty())
            .ifEmpty { "$host:${config.serverPort}" }
        return config
    }

    /** The part after "dns://"; the caller in AngConfigManager prepends the scheme. */
    fun toUri(config: ProfileItem): String {
        val transport = normalizeTransport(config.dnsTransport)
        val host = Utils.getIpv6Address(HttpUtil.toIdnDomain(config.server.orEmpty()))
        val port = config.serverPort?.toIntOrNull() ?: defaultPort(transport)
        val query = if (transport == TRANSPORT_DOH) {
            "?transport=$transport&path=${Utils.encodeURIComponent(config.path?.takeIf { it.isNotBlank() } ?: DEFAULT_DOH_PATH)}"
        } else {
            "?transport=$transport"
        }
        return "$host:$port$query#${Utils.encodeURIComponent(config.remarks)}"
    }

    /** Normalizes a profile in place; returns false when the resolver address is unusable. */
    fun normalize(config: ProfileItem): Boolean {
        val host = config.server?.trim().orEmpty()
        if (!isUsableHost(host)) return false
        config.server = host
        val transport = normalizeTransport(config.dnsTransport)
        config.dnsTransport = transport
        val port = config.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort(transport)
        config.serverPort = port.toString()
        config.path = if (transport == TRANSPORT_DOH) {
            config.path?.trim()?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) it else "/$it" }
                ?: DEFAULT_DOH_PATH
        } else {
            null
        }
        return true
    }

    private fun isUsableHost(host: String): Boolean {
        if (host.isEmpty()) return false
        // No scheme, path or whitespace: the field holds a bare IP or hostname, never a URL.
        if (host.contains("://") || host.contains('/') || host.any { it.isWhitespace() }) return false
        // "localhost" would hand DNS to the OS resolver, which defeats the point of the profile.
        if (host.equals(AppConfig.DNS_DIRECT, ignoreCase = true)) return false
        // Pure Kotlin checks: Utils.isValidUrl reads android.util.Patterns, which the JVM
        // unit tests stub, so a hostname would fail validation there while passing on device.
        return Utils.isPureIpAddress(host) || DOMAIN_REGEX.matches(host)
    }

    private val DOMAIN_REGEX =
        Regex("^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")

    /**
     * The single entry for the "dns.servers" of the Xray configuration the app builds for
     * [config]: a string, except UDP on a non-53 port, which Xray only accepts as an
     * {address, port} object (a "host:port" string is rejected at load).
     */
    fun toXrayAddress(config: ProfileItem): Any {
        val transport = normalizeTransport(config.dnsTransport)
        val host = config.server?.trim().orEmpty()
        val port = config.serverPort?.toIntOrNull() ?: defaultPort(transport)
        return when (transport) {
            TRANSPORT_UDP -> if (port == 53) {
                host
            } else {
                V2rayConfig.DnsBean.ServersBean(address = host, port = port)
            }
            TRANSPORT_DOH -> {
                val path = config.path?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_DOH_PATH
                val withSlash = if (path.startsWith("/")) path else "/$path"
                "https://$host${if (port == 443) "" else ":$port"}$withSlash"
            }
            TRANSPORT_DOQ -> "quic+local://$host:$port"
            else -> host
        }
    }
}
