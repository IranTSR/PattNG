package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

object SmartFragmentManager {

    data class FragmentCandidate(
        val packets: String,
        val length: String,
        val interval: String,
        val maxSplit: String
    ) {
        fun label() = "$packets / $length / $interval / $maxSplit"
    }

    val CANDIDATES: List<FragmentCandidate> = listOf(
        FragmentCandidate("tlshello", "50-100", "10-20", "10"),
        FragmentCandidate("tlshello", "100-200", "10-20", "10"),
        FragmentCandidate("1-3", "100-200", "10-20", "20"),
        FragmentCandidate("1-2", "50-100", "5-10", "10"),
        FragmentCandidate("tlshello", "200-300", "20-30", "30"),
        FragmentCandidate("1-5", "100-200", "10-20", "50"),
        FragmentCandidate("1-1", "100-200", "10-20", "10"),
        FragmentCandidate("tlshello", "10-20", "10-20", "5")
    )

    private const val HEALTH_PROBE_INTERVAL_MS = 15 * 60 * 1000L
    private const val NETWORK_DEBOUNCE_MS = 10_000L
    private const val AUTO_RETEST_COOLDOWN_MS = 30 * 60 * 1000L
    private const val MAX_CONSECUTIVE_FAILURES = 3

    private val monitorLock = Any()
    private val testMutex = Mutex()
    private var monitorScope: CoroutineScope? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var debounceJob: Job? = null
    private var consecutiveFailures = 0
    private var lastAutoRetestMs = 0L

    fun isSmartEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_SMART_FRAGMENT, false)

    private fun canAutoTune(): Boolean =
        isSmartEnabled()
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
            && CoreServiceManager.isRunning()

    fun pickBest(results: List<Pair<FragmentCandidate, Long>>): FragmentCandidate? =
        results.filter { it.second >= 0 }.minByOrNull { it.second }?.first

    fun applyCandidate(candidate: FragmentCandidate) {
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_PACKETS, candidate.packets)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_LENGTH, candidate.length)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_INTERVAL, candidate.interval)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_MAXSPLIT, candidate.maxSplit)
    }

    suspend fun testNow(context: Context): FragmentCandidate? = withContext(Dispatchers.IO) {
        testMutex.withLock {
            val winner = findBest(context) ?: return@withLock null
            applyCandidate(winner)
            winner
        }
    }

    suspend fun findBest(context: Context): FragmentCandidate? = withContext(Dispatchers.IO) {
        val guid = MmkvManager.getSelectServer() ?: return@withContext null
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_FRAGMENT_ENABLED, false)) {
            return@withContext null
        }
        val original = FragmentCandidate(
            packets = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_PACKETS) ?: "tlshello",
            length = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_LENGTH) ?: "50-100",
            interval = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_INTERVAL) ?: "10-20",
            maxSplit = MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_MAXSPLIT) ?: "10"
        )
        val testUrl = SettingsManager.getDelayTestUrl()
        val batch = UUID.randomUUID().toString()
        val results = mutableListOf<Pair<FragmentCandidate, Long>>()
        for (candidate in CANDIDATES) {
            try {
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_PACKETS, candidate.packets)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_LENGTH, candidate.length)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_INTERVAL, candidate.interval)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_MAXSPLIT, candidate.maxSplit)
                val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
                val delayMs = if (configResult.status) {
                    CoreNativeManager.measureOutboundDelay(configResult.content, testUrl, batch)
                } else {
                    -1L
                }
                results.add(candidate to delayMs)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "SmartFragment: candidate test failed", e)
                results.add(candidate to -1L)
            } finally {
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_PACKETS, original.packets)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_LENGTH, original.length)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_INTERVAL, original.interval)
                MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_MAXSPLIT, original.maxSplit)
            }
        }
        pickBest(results)
    }

    fun startMonitoring() {
        synchronized(monitorLock) {
            if (monitorScope != null || !isSmartEnabled()) return
            val appContext = AngApplication.application
            val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("SmartFragment"))
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleDebouncedRetest()
                override fun onLost(network: Network) = scheduleDebouncedRetest()
            }
            try {
                connectivity.requestNetwork(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        .build(),
                    callback
                )
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "SmartFragment: failed to register network callback", e)
                return
            }
            monitorScope = scope
            networkCallback = callback
            scope.launch { healthProbeLoop() }
        }
    }

    fun stopMonitoring() {
        synchronized(monitorLock) {
            try {
                val connectivity = AngApplication.application
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                networkCallback?.let { connectivity?.unregisterNetworkCallback(it) }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "SmartFragment: failed to unregister network callback", e)
            }
            networkCallback = null
            debounceJob?.cancel()
            debounceJob = null
            monitorScope?.cancel()
            monitorScope = null
            consecutiveFailures = 0
        }
    }

    private fun scheduleDebouncedRetest() {
        synchronized(monitorLock) {
            val scope = monitorScope ?: return
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(NETWORK_DEBOUNCE_MS)
                autoRetest("network-change")
            }
        }
    }

    private suspend fun autoRetest(reason: String) {
        if (!canAutoTune()) return
        synchronized(monitorLock) {
            val now = System.currentTimeMillis()
            if (now - lastAutoRetestMs < AUTO_RETEST_COOLDOWN_MS) return
            lastAutoRetestMs = now
        }
        try {
            val winner = testNow(AngApplication.application)
            if (winner != null) {
                LogUtil.i(AppConfig.TAG, "SmartFragment: auto retest ($reason) applied ${winner.label()}")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "SmartFragment: auto retest failed", e)
        }
    }

    private suspend fun healthProbeLoop() {
        while (currentCoroutineContext().isActive) {
            delay(HEALTH_PROBE_INTERVAL_MS)
            if (!canAutoTune()) {
                consecutiveFailures = 0
                continue
            }
            val delayMs = probeCurrent()
            if (delayMs != null && delayMs >= 0) {
                consecutiveFailures = 0
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    consecutiveFailures = 0
                    autoRetest("health-probe")
                }
            }
        }
    }

    private suspend fun probeCurrent(): Long? = withContext(Dispatchers.IO) {
        try {
            val guid = MmkvManager.getSelectServer() ?: return@withContext null
            val configResult =
                CoreConfigManager.getV2rayConfig4Speedtest(AngApplication.application, guid)
            if (!configResult.status) return@withContext null
            CoreNativeManager.measureOutboundDelay(
                configResult.content,
                SettingsManager.getDelayTestUrl(),
                UUID.randomUUID().toString()
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "SmartFragment: health probe failed", e)
            null
        }
    }
}
