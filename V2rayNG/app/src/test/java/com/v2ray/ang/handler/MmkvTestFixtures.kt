package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock

/**
 * Shared MMKV mocks for JVM unit tests.
 *
 * MmkvManager's storage handles are `lazy` vals on a singleton object, so each
 * one is initialized exactly once per test JVM, by whichever test class touches
 * it first. Every test class that reads or writes the SETTINGS storage must
 * share the same mock instance: a second class-local mock would never be seen
 * by MmkvManager (its lazy is already initialized), and tests asserting
 * cross-instance persistence would silently read defaults instead of what the
 * test wrote.
 */
object MmkvTestFixtures {
    val settings: MMKV = mock()

    /**
     * Installs the static MMKV mock so `mmkvWithID("SETTING", ...)` returns the
     * shared [settings] handle, and forces MmkvManager's lazy settingsStorage
     * to capture it. Safe to call from several test classes: after the first
     * call the lazy is already initialized and later calls change nothing.
     */
    fun installSettingsMock() {
        mockStatic(MMKV::class.java).use {
            it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }
                .thenReturn(settings)
            // Force MmkvManager's lazy settingsStorage to capture the shared mock.
            MmkvManager.decodeSettingsString("test-initialize")
        }
    }
}
