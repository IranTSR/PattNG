package com.v2ray.ang.ui.zeptunroot

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.AppListItem
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import com.v2ray.ang.ui.compose.verticalScrollbar

/**
 * Per-app picker for the zeptun root mode. An empty selection means all device
 * traffic goes through the tunnel; the mode switch flips the selection between
 * "only these apps" and "all except these apps". Takes effect on service restart.
 */
class ZeptunRootAppsActivity : BaseComponentActivity() {

    private val viewModel: ZeptunRootAppsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadApps(this)
    }

    @Composable
    override fun ScreenContent() {
        val apps by viewModel.displayedApps.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val selected by viewModel.selected.collectAsStateWithLifecycle()
        val bypassMode by viewModel.bypassMode.collectAsStateWithLifecycle()

        ZeptunRootAppsScreen(
            apps = apps,
            isLoading = isLoading,
            selected = selected,
            bypassMode = bypassMode,
            onBackClick = { finish() },
            onBypassModeChanged = { viewModel.setBypassMode(it) },
            onToggleApp = { viewModel.toggle(it) },
            onSelectAll = { viewModel.selectAll() },
            onInvertSelection = { viewModel.invertSelection() },
        )
    }
}

@Composable
fun ZeptunRootAppsScreen(
    apps: List<AppInfo>,
    isLoading: Boolean,
    selected: Set<String>,
    bypassMode: Boolean,
    onBackClick: () -> Unit,
    onBypassModeChanged: (Boolean) -> Unit,
    onToggleApp: (String) -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
) {
    val listState = rememberLazyListState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.zeptun_root_apps_title),
                onBackClick = onBackClick,
                isLoading = isLoading,
                actions = {
                    TextButton(onClick = onSelectAll) {
                        Text(stringResource(R.string.menu_item_select_all))
                    }
                    TextButton(onClick = onInvertSelection) {
                        Text(stringResource(R.string.menu_item_invert_selection))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(
                            if (bypassMode) R.string.zeptun_root_apps_bypass_on
                            else R.string.zeptun_root_apps_bypass_off
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = bypassMode,
                        modifier = Modifier.scale(0.65f),
                        onCheckedChange = onBypassModeChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
                            checkedTrackColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
            }
            AppDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScrollbar(listState),
                contentPadding = NavigationBarsBottomPadding()
            ) {
                items(items = apps, key = { it.packageName }) { app ->
                    val checked = selected.contains(app.packageName)
                    AppListItem(
                        appName = app.appName,
                        packageName = app.packageName,
                        icon = null,
                        checked = checked,
                        onCheckedChange = { onToggleApp(app.packageName) }
                    )
                    ItemDivider()
                }
            }
        }
    }
}
