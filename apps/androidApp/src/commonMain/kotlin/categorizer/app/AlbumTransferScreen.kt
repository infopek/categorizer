package categorizer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

sealed interface TransferUiState {
    data object Ready : TransferUiState
    data class Working(val message: String) : TransferUiState
    data class Exported(val entries: Int, val images: Int) : TransferUiState
    data class Preview(val archiveId: String, val entries: Int, val importing: Int, val bytes: Long, val conflicts: Int) : TransferUiState
    data class Imported(val entries: Int, val bytes: Long) : TransferUiState
    data class Error(val title: String, val messages: List<String>) : TransferUiState
}

@Composable fun AlbumTransferScreen(state: TransferUiState, onBack: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onConfirm: () -> Unit) {
    MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Album transfer", style = MaterialTheme.typography.headlineMedium)
        Text("Create a local backup or restore one through Android's document picker. No broad storage access is requested.")
        when (state) {
            TransferUiState.Ready -> Text("Import validation does not change your album.")
            is TransferUiState.Working -> { CircularProgressIndicator(); Text(state.message) }
            is TransferUiState.Exported -> Text("Backup saved successfully: ${state.entries} entries and ${state.images} images.")
            is TransferUiState.Preview -> { Text("Valid backup", style = MaterialTheme.typography.titleLarge); Text("Archive: ${state.archiveId}\nEntries: ${state.entries}\nTo import: ${state.importing}\nImage data: ${state.bytes} bytes\nConflicts: ${state.conflicts}"); if (state.conflicts > 0) Text("Existing entries will be kept and conflicting backup entries skipped."); Button(onClick = onConfirm) { Text("Confirm import") } }
            is TransferUiState.Imported -> Text("Import complete: ${state.entries} entries (${state.bytes} bytes).")
            is TransferUiState.Error -> { Text(state.title, style = MaterialTheme.typography.titleLarge); state.messages.forEach { Text(it) }; Text("Your album was not changed. You can try again.") }
        }
        if (state !is TransferUiState.Working && state !is TransferUiState.Preview) { Button(onClick = onExport, Modifier.fillMaxWidth()) { Text("Export backup") }; FilledTonalButton(onClick = onImport, Modifier.fillMaxWidth()) { Text("Import backup") } }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text("Back to album") }
    } } }
}
