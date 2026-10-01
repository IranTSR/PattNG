package com.v2ray.ang.service

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import dev.zeptun.Zeptun
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Tun2SocksControl] implementation backed by the Zeptun engine (libzeptun-jni.so).
 * Mirrors [TProxyService]: the TUN fd comes from VpnService.Builder.establish()
 * and packets are forwarded to Xray's local SOCKS proxy.
 */
class ZeptunTunService(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor,
) : Tun2SocksControl {

    private val running = AtomicBoolean(false)

    override fun startTun2Socks(): Boolean {
        return try {
            val config = ZeptunConfig.buildToml(
                ZeptunConfig.Params(
                    socksPort = SettingsManager.getSocksPort(),
                    socksUsername = SettingsManager.getSocksUsername(),
                    socksPassword = SettingsManager.getSocksPassword(),
                    mtu = SettingsManager.getVpnMtu(),
                    logLevel = MmkvManager.decodeSettingsString(
                        AppConfig.PREF_HEV_TUNNEL_LOGLEVEL,
                        AppConfig.DEFAULT_HEV_TUNNEL_LOGLEVEL
                    ) ?: AppConfig.DEFAULT_HEV_TUNNEL_LOGLEVEL,
                )
            )
            // Never log the config: it carries the SOCKS credentials.
            when (val rc = Zeptun.nativeStart(vpnService, vpnInterface.fd, config)) {
                Zeptun.OK -> {
                    running.set(true)
                    true
                }
                Zeptun.ERR_ALREADY_RUNNING -> {
                    // A previous instance is still alive; treat as running so the
                    // service stops it on teardown instead of leaking it.
                    LogUtil.w(AppConfig.TAG, "Zeptun already running")
                    running.set(true)
                    true
                }
                else -> {
                    LogUtil.e(AppConfig.TAG, "Zeptun nativeStart failed, rc=$rc")
                    false
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            LogUtil.e(AppConfig.TAG, "Zeptun native library missing", e)
            false
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Zeptun exception: ${e.message}", e)
            false
        }
    }

    override fun isTun2SocksRunning(): Boolean = running.get()

    override fun stopTun2Socks() {
        running.set(false)
        try {
            Zeptun.nativeStop()
        } catch (e: UnsatisfiedLinkError) {
            // Library was never loaded (start failed or never ran); nothing to stop.
            LogUtil.e(AppConfig.TAG, "Zeptun native library missing on stop", e)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to stop zeptun", e)
        }
    }
}
