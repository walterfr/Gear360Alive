package com.simoneluconi.revive360.media

import android.content.Context
import android.net.Uri
import com.simoneluconi.revive360.EventLog
import com.simoneluconi.revive360.link.TransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val current: Int, val total: Int) : ImportState
    data class Done(val result: MediaImporter.Result) : ImportState
    data object Failed : ImportState
}

/**
 * One import at a time, owned by the app and not by a screen: it goes on after the user leaves
 * Home, and [TransferService] keeps the process in the foreground meanwhile.
 */
class ImportController(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun importFiles(uris: List<Uri>) = start { uris }

    fun importFolder(tree: Uri) = start { MediaImporter.filesIn(context, tree) }

    /** On the main thread, as [TransferService.set] needs. */
    private fun start(files: () -> List<Uri>) {
        if (_state.value is ImportState.Running) return
        _state.value = ImportState.Running(0, 0)
        TransferService.set(context, true)
        scope.launch {
            try {
                _state.value = ImportState.Done(
                    withContext(Dispatchers.IO) {
                        val uris = files()
                        MediaImporter.import(context, uris) { _state.value = ImportState.Running(it, uris.size) }
                    },
                )
            } catch (e: Exception) {
                // A folder the system won't list, or a card pulled out while it was listed
                EventLog.log("Import failed: $e")
                _state.value = ImportState.Failed
            } finally {
                TransferService.set(context, false)
            }
        }
    }
}
