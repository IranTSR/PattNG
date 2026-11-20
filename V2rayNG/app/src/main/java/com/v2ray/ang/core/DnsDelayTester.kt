package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.DnsFmt
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Measures DNS profiles by sending a real query for example.com through the profile's
 * resolver and reporting the milliseconds it takes; -1 means the resolver did not answer.
 * DoQ has no JVM QUIC stack in the app, so it reports -1 rather than a made-up number.
 *
 * The sockets are not protected from the VPN: no VpnService instance is at hand in the
 * worker that runs the test, so a test run while a session is up can be answered by that
 * session instead of the profile under test, as with SpeedtestManager.socketConnectTime.
 * A test run while the service is stopped measures the profile alone.
 */
object DnsDelayTester {

    private const val TEST_DOMAIN = "example.com"
    private const val UDP_TIMEOUT_MS = 5_000
    private const val DOH_TIMEOUT_MS = 10_000

    suspend fun measure(context: Context, profile: ProfileItem): Long = withContext(Dispatchers.IO) {
        val transport = DnsFmt.normalizeTransport(profile.dnsTransport)
        val host = profile.server?.trim().orEmpty()
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DnsFmt.defaultPort(transport)
        if (host.isEmpty()) return@withContext -1L
        try {
            when (transport) {
                DnsFmt.TRANSPORT_UDP -> measureUdp(host, port)
                DnsFmt.TRANSPORT_DOH -> measureDoh(profile)
                // DoQ: no JVM QUIC stack; the row shows "—".
                else -> -1L
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "DnsDelayTester: $transport test failed", e)
            -1L
        }
    }

    private fun measureUdp(host: String, port: Int): Long {
        val query = buildQuery(TEST_DOMAIN, Random.nextInt(0, 0x10000))
        val id = queryId(query)
        val start = System.currentTimeMillis()
        DatagramSocket().use { socket ->
            socket.soTimeout = UDP_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port))
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(512)
            val reply = DatagramPacket(buffer, buffer.size)
            socket.receive(reply)
            val response = reply.data.copyOf(reply.length)
            if (!isAnswer(response, id)) return -1L
        }
        return System.currentTimeMillis() - start
    }

    private fun measureDoh(profile: ProfileItem): Long {
        val url = DnsFmt.toXrayAddress(profile) as? String ?: return -1L
        val client = OkHttpClient.Builder()
            .connectTimeout(DOH_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(DOH_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url("$url?name=$TEST_DOMAIN&type=A")
            .header("Accept", "application/dns-json")
            .build()
        val start = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return -1L
            val body = response.body?.string().orEmpty()
            if (!body.contains("\"Status\":0") && !body.contains("\"Status\": 0")) return -1L
        }
        return System.currentTimeMillis() - start
    }

    /** A minimal DNS query: a standard recursive query for one A record. */
    internal fun buildQuery(domain: String, id: Int): ByteArray {
        val labels = domain.split(".")
        val out = ByteArray(12 + labels.sumOf { it.length + 1 } + 5)
        out[0] = (id shr 8).toByte()
        out[1] = id.toByte()
        // Flags: recursion desired.
        out[2] = 0x01
        out[3] = 0x00
        // QDCOUNT = 1.
        out[5] = 0x01
        var pos = 12
        for (label in labels) {
            out[pos++] = label.length.toByte()
            label.toByteArray(Charsets.US_ASCII).copyInto(out, pos)
            pos += label.length
        }
        out[pos++] = 0
        // QTYPE = A (1), QCLASS = IN (1).
        out[pos++] = 0
        out[pos++] = 1
        out[pos++] = 0
        out[pos] = 1
        return out
    }

    internal fun queryId(query: ByteArray): Int =
        ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF)

    /** Whether [response] answers the query with [id]: same id, no error, at least one answer. */
    internal fun isAnswer(response: ByteArray, id: Int): Boolean {
        if (response.size < 12) return false
        if (queryId(response) != id) return false
        // RCODE, the low four bits of byte 3, must be 0 (NoError).
        if (response[3].toInt() and 0x0F != 0) return false
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        return anCount > 0
    }
}
