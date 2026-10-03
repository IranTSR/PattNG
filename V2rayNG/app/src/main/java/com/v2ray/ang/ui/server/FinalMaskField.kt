package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.ConfirmDialog
import com.v2ray.ang.ui.compose.FormTextField

/**
 * The finalMask field of the server editors and of the exit-node on the Aether pages: JSON written by hand, or one of
 * the finalMasks the arrow lists by name, put in the field and edited from there as need be. The field holds the
 * finalMask either way, so what is saved is the pick as edited. Only the arrow opens the list, so that a tap in the
 * JSON only moves the cursor, and a pick asks first before it takes the place of JSON of the user's own.
 */
@Composable
internal fun FinalMaskField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
) {
    val names = stringArrayResource(R.array.final_mask_preset_names).toList()
    val values = stringArrayResource(R.array.final_mask_preset_values).toList()
    val presets = remember(values) { FinalMaskPresets(values) }
    val held = remember(presets, value) { presets.indexOf(value) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The name of a pick that waits to be let take the place of JSON of the user's own.
    var replacing by rememberSaveable { mutableStateOf<String?>(null) }

    Box {
        FormTextField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            maxLines = 8,
            supportingText = names.getOrNull(held),
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }, enabled = enabled) {
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more_24dp),
                        contentDescription = stringResource(R.string.acc_final_mask_presets),
                        modifier = Modifier.rotate(if (expanded) 180f else 0f)
                    )
                }
            }
        )
        // Below the field, lined up with its edge inside the padding FormTextField gives it.
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            offset = DpOffset(16.dp, 0.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            names.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        if (presets.asksBeforeReplacing(value)) {
                            replacing = name
                        } else {
                            values.getOrNull(index)?.let(onValueChange)
                        }
                    }
                )
            }
        }
    }
    replacing?.let { name ->
        ConfirmDialog(
            title = stringResource(R.string.final_mask_replace_title),
            message = stringResource(R.string.final_mask_replace_message, name),
            confirmText = stringResource(R.string.final_mask_action_replace),
            onConfirm = { values.getOrNull(names.indexOf(name))?.let(onValueChange) },
            onDismiss = { replacing = null }
        )
    }
}
