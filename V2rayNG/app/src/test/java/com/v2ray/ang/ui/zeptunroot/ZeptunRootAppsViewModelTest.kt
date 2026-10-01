package com.v2ray.ang.ui.zeptunroot

import android.app.Application
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * JVM tests for the zeptun root-mode per-app picker ViewModel.
 * MMKV is replaced with in-memory maps, following the SubscriptionIndexTest pattern.
 */
class ZeptunRootAppsViewModelTest {

    private val bools = mutableMapOf<String, Boolean>()
    private val sets = mutableMapOf<String, MutableSet<String>>()

    @Before
    fun prepareStorage() {
        bools.clear()
        sets.clear()
        reset(settings)
        whenever(settings.decodeBool(any(), any())).thenAnswer {
            bools[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1)
        }
        whenever(settings.decodeStringSet(any())).thenAnswer {
            sets[it.getArgument<String>(0)]?.toMutableSet()
        }
        whenever(settings.encode(any<String>(), any<Boolean>())).thenAnswer {
            bools[it.getArgument(0)] = it.getArgument(1)
            true
        }
        whenever(settings.encode(any<String>(), any<Set<String>>())).thenAnswer {
            val key: String = it.getArgument(0)
            val value: Set<String> = it.getArgument(1)
            sets[key] = value.toMutableSet()
            true
        }
    }

    private fun viewModel() = ZeptunRootAppsViewModel(mock<Application>())

    @Test
    fun initialState_isEmptySelectionAndBypassOff() {
        val vm = viewModel()

        assertEquals(emptySet<String>(), vm.selected.value)
        assertFalse(vm.bypassMode.value)
    }

    @Test
    fun toggle_addsAndRemovesPackage() {
        val vm = viewModel()

        vm.toggle("com.example.one")
        assertEquals(setOf("com.example.one"), vm.selected.value)

        vm.toggle("com.example.two")
        assertEquals(setOf("com.example.one", "com.example.two"), vm.selected.value)

        vm.toggle("com.example.one")
        assertEquals(setOf("com.example.two"), vm.selected.value)
    }

    @Test
    fun toggle_persistsSelectionAcrossInstances() {
        viewModel().toggle("com.example.one")

        val reloaded = viewModel()
        assertEquals(setOf("com.example.one"), reloaded.selected.value)
    }

    @Test
    fun setBypassMode_persistsAcrossInstances() {
        val vm = viewModel()
        vm.setBypassMode(true)

        assertTrue(vm.bypassMode.value)
        assertTrue(viewModel().bypassMode.value)
    }

    @Test
    fun setBypassMode_sameValueDoesNotRewriteStorage() {
        val vm = viewModel()
        vm.setBypassMode(true)
        reset(settings)

        vm.setBypassMode(true)

        verify(settings, times(0)).encode(any<String>(), any<Boolean>())
    }

    @Test
    fun storedSelection_restoresBypassDefault() {
        // Pre-seeded storage (e.g. from an earlier session) is honored.
        sets[AppConfig.PREF_ZEPTUN_ROOT_APP_SET] = mutableSetOf("com.example.one")
        bools[AppConfig.PREF_ZEPTUN_ROOT_BYPASS_MODE] = true

        val vm = viewModel()

        assertEquals(setOf("com.example.one"), vm.selected.value)
        assertTrue(vm.bypassMode.value)
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
