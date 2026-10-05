package com.v2ray.ang.core

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import kittoku.osc.SharedBridge
import kittoku.osc.SstpEvents
import kittoku.osc.control.Controller
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs an SSTP profile: the vendored SSTP+PPP stack negotiates the tunnel and
 * owns the TUN interface for the session. No Xray core is started for SSTP
 * profiles; the engine's packet pump moves IP packets between the TUN and the
 * PPP data channel.
 */
object SstpEngine {

    private var scope: CoroutineScope? = null
    private var controller: Controller? = null
    private var tunFd: ParcelFileDescriptor? = null

    fun isRunning(): Boolean = controller != null

    /** The TUN interface the engine established, if any. */
    fun tunInterface(): ParcelFileDescriptor? = tunFd

    /**
     * Starts the SSTP session asynchronously. [onConnected] fires once the TUN
     * is up and pumping; [onError] fires with a short reason on failure.
     */
    fun start(
        vpnService: VpnService,
        profile: ProfileItem,
        onConnected: () -> Unit,
        onError: (reason: String) -> Unit,
    ) {
        stop()
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = engineScope

        val host = profile.server.orEmpty()
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
        LogUtil.i(AppConfig.TAG, "SstpEngine: starting session to $host:$port")

        // Declared before construction: the events object references the bridge,
        // which does not exist yet while its own constructor arguments are evaluated.
        var bridge: SharedBridge? = null
        val events = object : SstpEvents {
            override fun onError(header: String, detail: String?) {
                LogUtil.e(AppConfig.TAG, "SstpEngine: $header${detail?.let { " $it" } ?: ""}")
                onError(header)
            }

            override fun onConnected() {
                tunFd = bridge?.ipTerminal?.parcelFd()
                LogUtil.i(AppConfig.TAG, "SstpEngine: tunnel connected")
                onConnected()
            }

            override fun onDisconnected() {
                LogUtil.i(AppConfig.TAG, "SstpEngine: disconnected")
            }
        }
        val sharedBridge = SharedBridge(
            vpnService = vpnService,
            scope = engineScope,
            host = host,
            port = port,
            username = profile.username ?: "vpn",
            password = profile.password ?: "vpn",
            events = events,
        )
        bridge = sharedBridge
        sharedBridge.handler = CoroutineExceptionHandler { _, throwable ->
            LogUtil.e(AppConfig.TAG, "SstpEngine: unexpected error", throwable)
            engineScope.launch { onError("unexpected") }
        }
        controller = Controller(sharedBridge).also { it.launchJobMain() }
    }

    fun stop() {
        try {
            controller?.disconnect()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "SstpEngine: error while disconnecting", e)
        }
        controller = null
        try {
            tunFd?.close()
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "SstpEngine: error while closing TUN", e)
        }
        tunFd = null
        scope?.cancel()
        scope = null
    }
}
