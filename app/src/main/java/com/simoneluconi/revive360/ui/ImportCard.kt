package com.simoneluconi.revive360.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.link.TransferService
import com.simoneluconi.revive360.media.MediaImporter
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Brings files in from a card reader or a folder, and opens what is already on the device. */
@Composable
fun ImportCard() {
    val nav = LocalActions.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var total by remember { mutableIntStateOf(0) }
    var current by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        total = uris.size
        current = 0
        running = true
        TransferService.set(context, true)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { MediaImporter.import(context, uris) { current = it } }
                nav.message(
                    resources.getString(R.string.import_done, result.imported, result.alreadyHere, result.skipped, result.failed),
                    if (result.failed == 0) MessageKind.GOOD else MessageKind.BAD,
                )
            } finally {
                running = false
                TransferService.set(context, false)
            }
        }
    }

    Card {
        Label(stringResource(R.string.import_text), color = colors.text2)
        Spacer(Modifier.height(14.dp))
        PillButton(
            if (running) stringResource(R.string.import_running, current, total) else stringResource(R.string.import_action),
            Modifier.fillMaxWidth(),
            icon = AppIcons.SdCard,
            style = ButtonStyle.PRIMARY,
            enabled = !running,
        ) { picker.launch(arrayOf("image/jpeg", "video/mp4")) }
    }
    Card(padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) {
        SettingRow(
            title = stringResource(R.string.stock_open),
            icon = AppIcons.Wifi,
            onClick = { nav.navigate(Screen.StockCamera) },
        )
        Hairline()
        SettingRow(
            title = stringResource(R.string.gallery_tab_phone),
            subtitle = stringResource(R.string.phone_subtitle),
            icon = AppIcons.Gallery,
            onClick = { nav.navigate(Screen.Gallery) },
        )
    }
}
