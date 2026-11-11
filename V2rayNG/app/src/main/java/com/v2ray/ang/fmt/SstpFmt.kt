package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.util.Utils
import java.net.URI

/**
 * Link format for SSTP profiles: sstp://username:password@host:port#remarks
 * The port defaults to 443 when absent. Credentials default to vpn/vpn
 * (the VPNGate public-server default) when absent.
 */
object SstpFmt : FmtBase() {

    const val DEFAULT_PORT = 443
    const val DEFAULT_USERNAME = "vpn"
    const val DEFAULT_PASSWORD = "vpn"

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.SSTP)
        val uri = try {
            URI(Utils.fixIllegalUrl(str))
        } catch (_: Exception) {
            return null
        }

        val userInfo = uri.rawUserInfo.orEmpty()
        val (rawUser, rawPass) = userInfo.split(":", limit = 2).let {
            Utils.decodeURIComponent(it.getOrElse(0) { "" }) to
                Utils.decodeURIComponent(it.getOrElse(1) { "" })
        }
        config.username = rawUser.ifEmpty { DEFAULT_USERNAME }
        config.password = rawPass.ifEmpty { DEFAULT_PASSWORD }

        val host = uri.idnHost?.takeIf { it.isNotBlank() } ?: return null
        config.server = host
        config.serverPort = uri.port.takeIf { it > 0 }?.toString() ?: DEFAULT_PORT.toString()

        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty())
            .ifEmpty { "$host:${config.serverPort}" }
        return config
    }

    fun toUri(config: ProfileItem): String {
        val user = Utils.encodeURIComponent(config.username.orEmpty())
        val pass = Utils.encodeURIComponent(config.password.orEmpty())
        val host = config.server.orEmpty()
        val port = config.serverPort?.toIntOrNull() ?: DEFAULT_PORT
        val remarks = Utils.encodeURIComponent(config.remarks)
        return "$user:$pass@$host:$port#$remarks"
    }

    /** Normalizes a profile in place; returns false when the server address is unusable. */
    fun normalize(config: ProfileItem): Boolean {
        val host = config.server?.trim().orEmpty()
        if (host.isEmpty()) return false
        config.server = host
        val port = config.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        config.serverPort = port.toString()
        if (config.username.isNullOrEmpty()) config.username = DEFAULT_USERNAME
        if (config.password.isNullOrEmpty()) config.password = DEFAULT_PASSWORD
        return true
    }
}
