package com.v2ray.ang.ui.zeptunroot

import android.app.Application
import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.AppManagerUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * ViewModel for the zeptun root-mode per-app picker.
 * The package set is stored separately from the VPN-mode per-app list; an empty
 * set means all device traffic goes through the tunnel. Root mode picks the new
 * set up on the next service (re)start.
 */
class ZeptunRootAppsViewModel(application: Application) : BaseViewModel(application) {

    private val _selected = MutableStateFlow(loadSelected())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _displayedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayedApps: StateFlow<List<AppInfo>> = _displayedApps.asStateFlow()

    private val _bypassMode = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_ZEPTUN_ROOT_BYPASS_MODE, false)
    )
    val bypassMode: StateFlow<Boolean> = _bypassMode.asStateFlow()

    private var appsAll: List<AppInfo>? = null
    private var currentQuery = ""
    private var isAppListLoading = false

    fun toggle(packageName: String) {
        val newSelection = if (packageName in _selected.value) {
            _selected.value - packageName
        } else {
            _selected.value + packageName
        }
        replaceSelection(newSelection)
        SettingsChangeManager.makeRestartService()
    }

    fun setBypassMode(enabled: Boolean) {
        if (_bypassMode.value != enabled) {
            _bypassMode.value = enabled
            MmkvManager.encodeSettings(AppConfig.PREF_ZEPTUN_ROOT_BYPASS_MODE, enabled)
            SettingsChangeManager.makeRestartService()
        }
    }

    fun selectAll() {
        val displayed = _displayedApps.value
        val allSelected = displayed.all { it.packageName in _selected.value }
        val newSelection = _selected.value.toMutableSet().apply {
            displayed.forEach { app ->
                if (allSelected) remove(app.packageName) else add(app.packageName)
            }
        }
        replaceSelection(newSelection)
        SettingsChangeManager.makeRestartService()
    }

    fun invertSelection() {
        val packageNames = _displayedApps.value.map { it.packageName }.toSet()
        replaceSelection(
            _selected.value.filter { it !in packageNames }.toSet() +
                    packageNames.filter { it !in _selected.value }
        )
        SettingsChangeManager.makeRestartService()
    }

    fun loadApps(context: Context) {
        if (appsAll != null || isAppListLoading) return

        val applicationContext = context.applicationContext
        isAppListLoading = true
        launchLoading {
            try {
                val apps = withContext(Dispatchers.IO) {
                    AppManagerUtil.loadNetworkAppList(applicationContext)
                }
                val sorted = withContext(Dispatchers.Default) { sortApps(apps) }
                appsAll = sorted
                _displayedApps.value = applyFilter(currentQuery)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Error loading apps", e)
            } finally {
                isAppListLoading = false
            }
        }
    }

    fun filterApps(query: String) {
        currentQuery = query
        _displayedApps.value = applyFilter(query)
    }

    private fun loadSelected(): Set<String> {
        return MmkvManager.decodeSettingsStringSet(AppConfig.PREF_ZEPTUN_ROOT_APP_SET)?.toSet()
            ?: emptySet()
    }

    private fun replaceSelection(newSelection: Set<String>) {
        if (newSelection == _selected.value) return
        _selected.value = newSelection
        MmkvManager.encodeSettings(AppConfig.PREF_ZEPTUN_ROOT_APP_SET, newSelection.toMutableSet())
    }

    private fun applyFilter(query: String): List<AppInfo> {
        val apps = appsAll ?: return emptyList()
        if (query.isEmpty()) return apps
        return apps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
        }
    }

    private fun sortApps(apps: List<AppInfo>): List<AppInfo> {
        val collator = Collator.getInstance()
        val selectedPackages = _selected.value
        return apps.sortedWith { p1, p2 ->
            val s1 = p1.packageName in selectedPackages
            val s2 = p2.packageName in selectedPackages
            when {
                s1 && !s2 -> -1
                !s1 && s2 -> 1
                p1.isSystemApp && !p2.isSystemApp -> 1
                !p1.isSystemApp && p2.isSystemApp -> -1
                else -> collator.compare(p1.appName, p2.appName)
            }
        }
    }
}
