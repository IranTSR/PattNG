package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.SstpFmt
import com.v2ray.ang.ui.compose.FormTextField

class ServerSstpActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.SSTP

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(
                initialConfig = initialConfig
            )
        }.apply {
            configType = serverConfigType
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)
            SstpProtocolFields(uiState)
            CommonDialModeField(uiState)
            CommonTargetStrategyField(uiState)
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        return SstpFmt.normalize(config)
    }

    @Composable
    private fun SstpProtocolFields(state: ServerUiState) {
        FormTextField(
            stringResource(R.string.server_lab_sstp_username),
            state.username,
            { state.username = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_sstp_password),
            state.password,
            { state.password = it }
        )
    }
}
