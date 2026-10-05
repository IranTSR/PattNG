package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Imports the public SSTP servers listed on VPNGate (https://www.vpngate.net/en/).
 *
 * The VPNGate CSV API carries no SSTP column, so the server-list page itself is parsed:
 * every "SSTP Hostname" cell names the host, with ":port" appended when the port is not
 * the default 443. All public servers use the documented vpn/vpn credentials.
 *
 * The import replaces the servers of the "VPNGate" group, so re-running it refreshes
 * a list whose volunteer servers come and go.
 */
object VpnGateImporter {

    const val VPNGATE_PAGE = "https://www.vpngate.net/en/"
    const val GROUP_NAME = "VPNGate"

    private const val FETCH_TIMEOUT_SEC = 30L

    /** host, port */
    private val sstpHostPattern =
        Regex("""SSTP Hostname\s*:?\s*([A-Za-z0-9.-]+\.opengw\.net)(?::(\d{1,5}))?""")

    data class ImportResult(val added: Int, val error: String? = null)

    suspend fun import(): ImportResult = withContext(Dispatchers.IO) {
        try {
            val html = fetchPage() ?: return@withContext ImportResult(0, "fetch_failed")
            val servers = parseSstpHosts(html)
            if (servers.isEmpty()) {
                return@withContext ImportResult(0, "no_servers")
            }
            val subId = ensureGroup()
            val links = servers.joinToString("\n") { (host, port) ->
                "sstp://vpn:vpn@$host:$port#${Utils.encodeURIComponent("$host:$port")}"
            }
            val (count, _) = AngConfigManager.importBatchConfig(links, subId, append = false)
            ImportResult(count)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "VPNGate import failed", e)
            ImportResult(0, "exception")
        }
    }

    private fun fetchPage(): String? {
        val client = OkHttpClient.Builder()
            .connectTimeout(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(VPNGATE_PAGE)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                LogUtil.e(AppConfig.TAG, "VPNGate page fetch failed: ${response.code}")
                return null
            }
            response.body?.string()
        }
    }

    /**
     * Extracts distinct (host, port) pairs from the SSTP column of the server table.
     * Tags are stripped first so the match does not depend on the cell markup.
     */
    internal fun parseSstpHosts(html: String): List<Pair<String, Int>> {
        val text = html.replace(Regex("<[^>]+>"), " ")
        return sstpHostPattern.findAll(text)
            .map { match ->
                val host = match.groupValues[1].lowercase()
                val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
                host to port
            }
            .distinct()
            .toList()
    }

    private fun ensureGroup(): String {
        val existing = MmkvManager.decodeSubscriptions()
            .firstOrNull { it.subscription.remarks == GROUP_NAME }
        if (existing != null) return existing.guid
        val subItem = SubscriptionItem(remarks = GROUP_NAME)
        val guid = Utils.getUuid()
        MmkvManager.encodeSubscription(guid, subItem)
        return guid
    }
}
