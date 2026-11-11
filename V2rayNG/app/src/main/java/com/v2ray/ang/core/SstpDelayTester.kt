package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import kittoku.osc.SharedBridge
import kittoku.osc.SstpEvents
import kittoku.osc.control.Controller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Measures SSTP profiles by running the tunnel negotiation without
 * establishing a TUN interface. The reported delay is the time from the
 * first byte sent to IPCP completion; -1 means the server is unreachable,
 * refused the handshake, or rejected the credentials.
 */
object SstpDelayTester {

    private const val TEST_TIMEOUT_MS = 30_000L

    suspend fun measure(context: Context, profile: ProfileItem): Long {
        val host = profile.server.orEmpty()
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
        if (host.isEmpty()) return -1L

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val done = CompletableDeferred<Long>()
        val startMs = System.currentTimeMillis()
        try {
            val bridge = SharedBridge(
                vpnService = null, // test mode: no TUN is established, no socket to protect
                scope = scope,
                host = host,
                port = port,
                username = profile.username ?: "vpn",
                password = profile.password ?: "vpn",
                events = object : SstpEvents {
                    override fun onError(header: String, detail: String?) {
                        LogUtil.w(AppConfig.TAG, "SstpDelayTester: $header")
                        done.complete(-1L)
                    }

                    override fun onConnected() {
                        done.complete(System.currentTimeMillis() - startMs)
                    }

                    override fun onDisconnected() {
                        done.complete(-1L)
                    }
                }
            )
            bridge.testMode = true
            bridge.handler = CoroutineExceptionHandler { _, throwable ->
                LogUtil.w(AppConfig.TAG, "SstpDelayTester: unexpected", throwable)
                done.complete(-1L)
            }
            val controller = Controller(bridge)
            controller.launchJobMain()
            val result = withTimeoutOrNull(TEST_TIMEOUT_MS) { done.await() } ?: -1L
            try {
                controller.disconnect()
            } catch (_: Exception) {
            }
            return result
        } finally {
            scope.cancel()
        }
    }
}
