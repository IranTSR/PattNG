package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever

/**
 * JVM tests for the TUN engine selection decisions that drive tun2socks dispatch
 * in VPN mode ([CoreVpnService]), fd ownership ([CoreServiceManager]) and the
 * root-mode CLI path ([RootProxyManager]).
 */
class SettingsManagerTunEngineTest {

    private val strings = mutableMapOf<String, String>()

    @Before
    fun prepareStorage() {
        strings.clear()
        reset(settings)
        whenever(settings.decodeString(any())).thenAnswer {
            strings[it.getArgument<String>(0)]
        }
        whenever(settings.encode(any<String>(), any<String>())).thenAnswer {
            strings[it.getArgument(0)] = it.getArgument(1)
            true
        }
    }

    @Test
    fun defaultEngineIsHev() {
        assertEquals(AppConfig.TUN_ENGINE_HEV, SettingsManager.getTunEngine())
        assertTrue(SettingsManager.isUsingHevTun())
        assertFalse(SettingsManager.isUsingZeptunTun())
        assertFalse(SettingsManager.isUsingXrayTun())
        assertTrue(SettingsManager.isUsingTun2Socks())
    }

    @Test
    fun storedEnginesRoundTrip() {
        for (engine in listOf(
            AppConfig.TUN_ENGINE_HEV,
            AppConfig.TUN_ENGINE_ZEPTUN,
            AppConfig.TUN_ENGINE_XRAY
        )) {
            SettingsManager.setTunEngine(engine)
            assertEquals(engine, SettingsManager.getTunEngine())
        }
    }

    @Test
    fun garbageFallsBackToHev() {
        strings[AppConfig.PREF_TUN_ENGINE] = "not-an-engine"

        assertEquals(AppConfig.TUN_ENGINE_HEV, SettingsManager.getTunEngine())
        assertTrue(SettingsManager.isUsingHevTun())
    }

    @Test
    fun zeptunEngineFlags() {
        SettingsManager.setTunEngine(AppConfig.TUN_ENGINE_ZEPTUN)

        assertFalse(SettingsManager.isUsingHevTun())
        assertTrue(SettingsManager.isUsingZeptunTun())
        assertFalse(SettingsManager.isUsingXrayTun())
        assertTrue(SettingsManager.isUsingTun2Socks())
    }

    @Test
    fun xrayEngineMeansNoTun2Socks() {
        SettingsManager.setTunEngine(AppConfig.TUN_ENGINE_XRAY)

        assertFalse(SettingsManager.isUsingHevTun())
        assertFalse(SettingsManager.isUsingZeptunTun())
        assertTrue(SettingsManager.isUsingXrayTun())
        assertFalse(SettingsManager.isUsingTun2Socks())
    }

    companion object {
        // Shared mock: MmkvManager.settingsStorage is lazy and initialized once
        // per test JVM, so all test classes must use the same instance.
        private val settings: MMKV = MmkvTestFixtures.settings

        @BeforeClass
        @JvmStatic
        fun initializeHandles() {
            MmkvTestFixtures.installSettingsMock()
        }
    }
}
