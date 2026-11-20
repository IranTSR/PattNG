package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.DnsFmt
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

/**
 * A DNS-only profile (Intra-style): only the resolver named here answers the device's DNS,
 * and all other traffic goes direct. No proxy, no routing rules, no TLS/stream settings.
 */
class ServerDnsActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.DNS

    @Composable
    override fun EditorContent() {
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
            DnsTransportField(uiState)
            if (uiState.dnsTransport == DnsFmt.TRANSPORT_DOH) {
                FormTextField(
                    stringResource(R.string.server_lab_dns_path),
                    uiState.path,
                    { uiState.path = it }
                )
            }
            DnsPresetField(uiState)
            Text(
                text = stringResource(R.string.dns_editor_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        return DnsFmt.normalize(config)
    }

    @Composable
    private fun DnsTransportField(state: ServerUiState) {
        FormDropdownField(
            stringResource(R.string.server_lab_dns_transport),
            displayTransport(state.dnsTransport),
            listOf(DISPLAY_UDP, DISPLAY_DOH, DISPLAY_DOQ),
            {
                val transport = DnsFmt.normalizeTransport(it)
                state.dnsTransport = transport
                // The port follows the transport unless the user edits it after.
                state.port = DnsFmt.defaultPort(transport).toString()
                if (transport == DnsFmt.TRANSPORT_DOH && state.path.isBlank()) {
                    state.path = DnsFmt.DEFAULT_DOH_PATH
                }
            }
        )
    }

    @Composable
    private fun DnsPresetField(state: ServerUiState) {
        val presetNames = DNS_PRESETS.map { stringResource(it.nameRes) }
        val matched = DnsPreset.matching(state.address, state.port, state.dnsTransport, state.path)
        FormDropdownField(
            stringResource(R.string.server_lab_dns_preset),
            matched?.let { presetNames[DNS_PRESETS.indexOf(it)] }.orEmpty(),
            presetNames,
            { picked ->
                DNS_PRESETS.getOrNull(presetNames.indexOf(picked))?.let { preset ->
                    state.address = preset.host
                    state.port = preset.port.toString()
                    state.dnsTransport = preset.transport
                    state.path = preset.path ?: ""
                }
            }
        )
    }

    private fun displayTransport(transport: String): String = when (transport) {
        DnsFmt.TRANSPORT_UDP -> DISPLAY_UDP
        DnsFmt.TRANSPORT_DOQ -> DISPLAY_DOQ
        else -> DISPLAY_DOH
    }

    private class DnsPreset(
        val nameRes: Int,
        val transport: String,
        val host: String,
        val port: Int,
        val path: String?,
    ) {
        companion object {
            fun matching(address: String, port: String, transport: String, path: String): DnsPreset? =
                DNS_PRESETS.firstOrNull {
                    it.host.equals(address.trim(), ignoreCase = true)
                        && it.port.toString() == port.trim()
                        && it.transport == DnsFmt.normalizeTransport(transport)
                        && (it.transport != DnsFmt.TRANSPORT_DOH || (it.path ?: "") == path.trim())
                }
        }
    }

    companion object {
        private const val DISPLAY_UDP = "UDP"
        private const val DISPLAY_DOH = "DoH"
        private const val DISPLAY_DOQ = "DoQ"

        /**
         * IP literals wherever the provider supports them: a DoH/DoT hostname must itself be
         * resolved before the first query, and a hostname resolver can loop on its own lookup.
         * No DoT preset: Xray 26.7.28 has no DNS-over-TLS nameserver.
         */
        private val DNS_PRESETS = listOf(
            DnsPreset(R.string.dns_preset_cloudflare_doh, DnsFmt.TRANSPORT_DOH, "1.1.1.1", 443, DnsFmt.DEFAULT_DOH_PATH),
            DnsPreset(R.string.dns_preset_cloudflare_udp, DnsFmt.TRANSPORT_UDP, "1.1.1.1", 53, null),
            DnsPreset(R.string.dns_preset_google_doh, DnsFmt.TRANSPORT_DOH, "8.8.8.8", 443, DnsFmt.DEFAULT_DOH_PATH),
            DnsPreset(R.string.dns_preset_quad9_doh, DnsFmt.TRANSPORT_DOH, "9.9.9.9", 443, DnsFmt.DEFAULT_DOH_PATH),
            DnsPreset(R.string.dns_preset_adguard_doq, DnsFmt.TRANSPORT_DOQ, "dns.adguard-dns.com", 853, null),
        )
    }
}
