package com.simoneluconi.revive360.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.media.ImportState
import com.simoneluconi.revive360.ui.theme.LocalAppColors

/** Brings files in from a card reader or a folder, and opens what is already on the device. */
@Composable
fun ImportCard() {
    val nav = LocalActions.current
    val colors = LocalAppColors.current
    val imports = nav.app.imports
    val state by imports.state.collectAsStateWithLifecycle()
    val running = state is ImportState.Running

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) imports.importFiles(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) imports.importFolder(tree)
    }

    Card {
        Label(stringResource(R.string.import_text), color = colors.text2)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                stringResource(R.string.import_action),
                Modifier.weight(1f),
                icon = AppIcons.Photo,
                style = ButtonStyle.PRIMARY,
                enabled = !running,
            ) { filePicker.launch(arrayOf("image/jpeg", "video/mp4")) }
            PillButton(
                stringResource(R.string.import_folder),
                Modifier.weight(1f),
                icon = AppIcons.SdCard,
                style = ButtonStyle.PRIMARY,
                enabled = !running,
            ) { folderPicker.launch(null) }
        }
        val current = state
        val status = when (current) {
            ImportState.Idle -> null
            is ImportState.Running ->
                if (current.total == 0) stringResource(R.string.import_listing)
                else stringResource(R.string.import_running, current.current, current.total)
            is ImportState.Done -> with(current.result) {
                stringResource(R.string.import_done, imported, alreadyHere, skipped, failed)
            }
            ImportState.Failed -> stringResource(R.string.import_failed)
        }
        if (status != null) {
            Spacer(Modifier.height(12.dp))
            val bad = current is ImportState.Failed || (current as? ImportState.Done)?.result?.failed?.let { it > 0 } == true
            Label(status, size = 14.sp, color = if (bad) colors.bad else colors.text2)
        }
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
