package com.v2ray.ang.service

/**
 * Pure builder for the zeptun TOML configuration used in the VpnService run
 * mode. Kept free of Android dependencies so it can be unit-tested on the JVM.
 */
object ZeptunConfig {

    data class Params(
        val socksPort: Int,
        val socksUsername: String?,
        val socksPassword: String?,
        val mtu: Int,
        val logLevel: String,
    )

    fun buildToml(params: Params): String = buildString {
        appendLine("preset = \"mobile\"")
        // The stored level comes from the hev tunnel setting, whose names
        // ("error", "warn", "info", "debug") do not match zeptun's log.Level
        // enum ("err", "warn", "info", "debug", "trace"). An unmapped name
        // makes the TOML parser fail with ConfigError (rc=-16).
        appendLine("log_level = \"${mapLogLevel(params.logLevel)}\"")
        appendLine()
        appendLine("[tun]")
        // The interface already exists: VpnService.Builder.establish() created
        // and addressed it, so zeptun must not configure it. The fd is handed
        // over separately through the JNI call.
        appendLine("configure = false")
        appendLine("mtu = ${params.mtu}")
        appendLine()
        appendLine("[handler]")
        appendLine("kind = \"socks5\"")
        appendLine()
        appendLine("[handler.socks5]")
        appendLine("server = \"127.0.0.1:${params.socksPort}\"")
        // UdpMode is an enum ("enabled"/"disabled"); a bare boolean is rejected
        // by the TOML parser.
        appendLine("udp = \"enabled\"")
        if (!params.socksUsername.isNullOrEmpty() && params.socksPassword != null) {
            appendLine("username = \"${tomlEscape(params.socksUsername)}\"")
            appendLine("password = \"${tomlEscape(params.socksPassword)}\"")
        }
        appendLine()
        appendLine("[dns]")
        // DNS flows through the tunnel as ordinary UDP, like the hev engine.
        appendLine("hijack = false")
    }

    internal fun tomlEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Maps a stored tunnel log-level name to a value of zeptun's `log.Level`
     * enum (`err`, `warn`, `info`, `debug`, `trace`). The setting is shared
     * with the hev engine, whose level names differ ("error" vs "err"); passing
     * one through unmapped makes zeptun reject the whole config with
     * ConfigError. Unknown values fall back to "warn".
     */
    fun mapLogLevel(storedLevel: String): String = when (storedLevel.lowercase()) {
        "err", "error", "fatal" -> "err"
        "warn", "warning" -> "warn"
        "info" -> "info"
        "debug" -> "debug"
        "trace", "verbose" -> "trace"
        else -> "warn"
    }

    /**
     * Root-mode parameters. Unlike [Params] (VPN mode, fd handed over), the CLI
     * creates and configures the TUN interface itself from [tunName]/[tunAddressV4].
     */
    data class RootParams(
        val socksPort: Int,
        val socksUsername: String?,
        val socksPassword: String?,
        val mtu: Int,
        val logLevel: String,
        val tunName: String,
        val tunAddressV4: String,
    )

    /**
     * JSON configuration for the zeptun CLI (`-c` file) in root mode.
     * Per-app routing is passed as CLI flags, not here. IPv4-only: no v6 address
     * is assigned, matching the networks this app targets.
     *
     * `route.auto_route` is what makes the CLI install its Android policy
     * routing (UID-range rules at priority 9000..9009 into table 2022);
     * without it the TUN interface would come up but capture no traffic.
     */
    fun buildRootJson(params: RootParams): String {
        val socksFields = mutableListOf(
            "\"server\": \"127.0.0.1:${params.socksPort}\"",
            "\"udp\": true",
        )
        if (!params.socksUsername.isNullOrEmpty() && params.socksPassword != null) {
            socksFields += "\"username\": \"${jsonEscape(params.socksUsername)}\""
            socksFields += "\"password\": \"${jsonEscape(params.socksPassword)}\""
        }
        return buildString {
            appendLine("{")
            appendLine("  \"preset\": \"mobile\",")
            // Same level-name mapping as the TOML builder: the JSON parser
            // fills the same log.Level enum, so "error" would be rejected here too.
            appendLine("  \"log_level\": \"${mapLogLevel(params.logLevel)}\",")
            appendLine("  \"tun\": {")
            appendLine("    \"name\": \"${params.tunName}\",")
            appendLine("    \"mtu\": ${params.mtu},")
            appendLine("    \"configure\": true,")
            appendLine("    \"address\": [\"${params.tunAddressV4}\"]")
            appendLine("  },")
            appendLine("  \"handler\": {")
            appendLine("    \"kind\": \"socks5\",")
            appendLine("    \"socks5\": { ${socksFields.joinToString(", ")} }")
            appendLine("  },")
            appendLine("  \"route\": { \"auto_route\": true },")
            appendLine("  \"dns\": { \"hijack\": false }")
            appendLine("}")
        }
    }

    internal fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
