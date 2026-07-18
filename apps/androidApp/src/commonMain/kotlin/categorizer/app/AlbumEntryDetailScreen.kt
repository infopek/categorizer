package categorizer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import categorizer.application.AlbumEntryEditInput
import categorizer.application.AlbumEntryEditValidation
import categorizer.application.validate
import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumError

sealed class EntryDetailUiState {
    data object Loading : EntryDetailUiState()
    data class Ready(val entry: AlbumEntry, val saving: Boolean = false, val error: AlbumError? = null) : EntryDetailUiState()
    data class Unavailable(val error: AlbumError) : EntryDetailUiState()
}

@Composable
internal fun AlbumEntryDetailScreen(
    state: EntryDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: (AlbumEntry) -> Unit,
    onSave: (AlbumEntryEditInput) -> Unit,
    onDelete: () -> Unit
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when (state) {
                EntryDetailUiState.Loading -> DetailMessage("Loading sighting…", null, onBack)
                is EntryDetailUiState.Unavailable -> DetailMessage(
                    "This sighting is unavailable",
                    state.error.message,
                    onBack,
                    if (state.error.recoverable) onRetry else null
                )
                is EntryDetailUiState.Ready -> EntryDetail(
                    state, onBack, onToggleFavorite, onSave, onDelete
                )
            }
        }
    }
}

@Composable
private fun EntryDetail(
    state: EntryDetailUiState.Ready,
    onBack: () -> Unit,
    onToggleFavorite: (AlbumEntry) -> Unit,
    onSave: (AlbumEntryEditInput) -> Unit,
    onDelete: () -> Unit
) {
    var editing by remember(state.entry.entryId, state.entry.updatedAtEpochMs) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var input by remember(state.entry) { mutableStateOf(AlbumEntryEditInput.from(state.entry)) }
    var attempted by remember { mutableStateOf(false) }
    val validation = input.validate(state.entry, state.entry.updatedAtEpochMs)
    val invalid = validation as? AlbumEntryEditValidation.Invalid
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sighting details", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onBack) { Text("Back") }
            }
            ManagedThumbnail(
                state.entry.managedImage,
                "Photo of ${state.entry.confirmedIdentity.displayName}",
                Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))
            )
            if (editing) {
                Text("Changes are not saved until you tap Save changes.")
                OutlinedTextField(
                    input.displayName, { input = input.copy(displayName = it) }, label = { Text("Display name") },
                    isError = attempted && invalid?.displayNameError != null,
                    supportingText = { if (attempted) invalid?.displayNameError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    input.scientificName, { input = input.copy(scientificName = it) }, label = { Text("Scientific name (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    input.alternateNames, { input = input.copy(alternateNames = it) },
                    label = { Text("Other names, comma-separated (optional)") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    input.identityNotes, { input = input.copy(identityNotes = it) },
                    label = { Text("Identity notes (optional)") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    input.notes, { input = input.copy(notes = it) }, label = { Text("Personal notes") },
                    minLines = 3, modifier = Modifier.fillMaxWidth()
                )
                state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = !state.saving,
                    onClick = {
                        attempted = true
                        if (validation is AlbumEntryEditValidation.Valid) onSave(input)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.saving) "Saving…" else "Save changes") }
                OutlinedButton(
                    enabled = !state.saving,
                    onClick = {
                        input = AlbumEntryEditInput.from(state.entry)
                        attempted = false
                        editing = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel editing") }
            } else {
                Text(state.entry.confirmedIdentity.displayName, style = MaterialTheme.typography.headlineMedium)
                if (state.entry.confirmedIdentity.categoryId == "lepidoptera") {
                    state.entry.confirmedIdentity.scientificName?.takeIf { it != state.entry.confirmedIdentity.displayName }?.let { Text(it) }
                    state.entry.confirmedIdentity.alternateNames.takeIf { it.isNotEmpty() }?.let { Text("Also known as: ${it.joinToString()}") }
                } else {
                    state.entry.confirmedIdentity.attributes["approximate_year_range"]?.let { Text(it) }
                }
                Text("Added ${state.entry.albumDate}")
                Text(if (state.entry.notes.isBlank()) "No personal notes" else state.entry.notes)
                state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                FilledTonalButton(
                    enabled = !state.saving,
                    onClick = { onToggleFavorite(state.entry) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.entry.isFavorite) "★ Remove favorite" else "☆ Add favorite") }
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit details") }
                Spacer(Modifier.size(4.dp))
                OutlinedButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete sighting")
                }
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this sighting?") },
            text = { Text("This removes the album entry and its managed photo copy from this device. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text("Delete permanently") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Keep sighting") } }
        )
    }
}

@Composable
private fun DetailMessage(heading: String, message: String?, onBack: () -> Unit, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(heading, style = MaterialTheme.typography.titleLarge)
        message?.let { Text(it) }
        Spacer(Modifier.height(16.dp))
        onRetry?.let { Button(onClick = it) { Text("Try again") } }
        TextButton(onClick = onBack) { Text("Back to album") }
    }
}
