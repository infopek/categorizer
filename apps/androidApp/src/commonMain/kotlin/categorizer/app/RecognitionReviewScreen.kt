package categorizer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import categorizer.application.ManualIdentityInput
import categorizer.application.ManualIdentityValidation
import categorizer.application.RecognitionUiState
import categorizer.application.validate
import categorizer.domain.RecognitionCandidate

sealed class ReviewSaveState {
    data object Ready : ReviewSaveState()
    data object Saving : ReviewSaveState()
    data class Failed(val message: String, val recoverable: Boolean) : ReviewSaveState()
}

@Composable
internal fun RecognitionReviewScreen(
    state: RecognitionUiState,
    saveState: ReviewSaveState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onConfirmCandidate: (String) -> Unit,
    onConfirmManual: (ManualIdentityInput) -> Unit
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Scaffold { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Review recognition",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = onCancel) { Text("Cancel", maxLines = 1) }
                    }
                    Spacer(Modifier.height(16.dp))
                    when (state) {
                        RecognitionUiState.Idle -> ReviewMessage("Recognition canceled", "Choose another photo to try again.")
                        is RecognitionUiState.Running -> ReviewMessage(
                            "Identifying the butterfly or moth…",
                            "Recognition runs locally on this device."
                        )
                        is RecognitionUiState.Candidates -> CandidateReview(
                            heading = "What did you photograph?",
                            explanation = "Choose the best match. Common names are shown first, with scientific names underneath.",
                            candidates = state.candidates,
                            saveState = saveState,
                            onConfirmCandidate = onConfirmCandidate,
                            onConfirmManual = onConfirmManual
                        )
                        is RecognitionUiState.Uncertain -> CandidateReview(
                            heading = "We’re not certain",
                            explanation = "Review the ranked suggestions or enter the identity yourself.",
                            candidates = state.candidates,
                            saveState = saveState,
                            onConfirmCandidate = onConfirmCandidate,
                            onConfirmManual = onConfirmManual
                        )
                        is RecognitionUiState.Unsupported -> ManualReview(
                            heading = "This species isn’t supported yet",
                            explanation = "You can still add it by entering its genus and species.",
                            saveState = saveState,
                            onConfirmManual = onConfirmManual
                        )
                        is RecognitionUiState.Error -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ReviewMessage("Recognition couldn’t finish", state.error.message)
                                if (state.error.recoverable) {
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = onRetry) { Text("Try recognition again") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateReview(
    heading: String,
    explanation: String,
    candidates: List<RecognitionCandidate>,
    saveState: ReviewSaveState,
    onConfirmCandidate: (String) -> Unit,
    onConfirmManual: (ManualIdentityInput) -> Unit
) {
    var selectedClassId by remember(candidates) { mutableStateOf(candidates.firstOrNull()?.identity?.classId) }
    var manual by remember(candidates) { mutableStateOf(false) }
    if (manual || candidates.isEmpty()) {
        ManualReview(heading, explanation, saveState, onConfirmManual) { manual = false }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(heading, style = MaterialTheme.typography.titleLarge)
        Text(explanation)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(candidates, key = { "${it.identity.categoryId}:${it.identity.classId}" }) { candidate ->
                CandidateCard(
                    candidate = candidate,
                    selected = candidate.identity.classId == selectedClassId,
                    onClick = { selectedClassId = candidate.identity.classId }
                )
            }
        }
        saveError(saveState)
        Button(
            enabled = selectedClassId != null && saveState !is ReviewSaveState.Saving,
            onClick = { selectedClassId?.let(onConfirmCandidate) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saveState is ReviewSaveState.Saving) "Saving…" else "Confirm and save") }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) {
            Text("None of these — enter manually")
        }
    }
}

@Composable
private fun CandidateCard(candidate: RecognitionCandidate, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${candidate.rank}", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    candidate.identity.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                candidate.identity.scientificName?.takeIf { it != candidate.identity.displayName }?.let {
                    Text("Scientific name: $it", style = MaterialTheme.typography.bodySmall)
                }
                candidate.identity.alternateNames.takeIf { it.isNotEmpty() }?.let {
                    Text("Also known as: ${it.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(if (selected) "Selected" else "Select")
        }
    }
}

@Composable
private fun ManualReview(
    heading: String,
    explanation: String,
    saveState: ReviewSaveState,
    onConfirmManual: (ManualIdentityInput) -> Unit,
    onBackToCandidates: (() -> Unit)? = null
) {
    var input by remember { mutableStateOf(ManualIdentityInput()) }
    var attempted by remember { mutableStateOf(false) }
    val validation = input.validate()
    val invalid = validation as? ManualIdentityValidation.Invalid
    Column(Modifier.fillMaxSize()) {
        Text(heading, style = MaterialTheme.typography.titleLarge)
        Text(explanation)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            input.displayName, { input = input.copy(displayName = it) },
            label = { Text("Common or display name") },
            isError = attempted && invalid?.displayNameError != null,
            supportingText = { if (attempted) invalid?.displayNameError?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            input.scientificName, { input = input.copy(scientificName = it) },
            label = { Text("Scientific name (optional)") },
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
        Spacer(Modifier.weight(1f))
        saveError(saveState)
        Button(
            enabled = saveState !is ReviewSaveState.Saving,
            onClick = {
                attempted = true
                if (validation is ManualIdentityValidation.Valid) onConfirmManual(input)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saveState is ReviewSaveState.Saving) "Saving…" else "Confirm manual identity") }
        onBackToCandidates?.let {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Back to suggestions") }
        }
    }
}

@Composable
private fun ReviewMessage(heading: String, explanation: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(heading, style = MaterialTheme.typography.titleLarge)
        Text(explanation)
    }
}

@Composable
private fun saveError(state: ReviewSaveState) {
    if (state is ReviewSaveState.Failed) {
        Text(state.message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
    }
}
