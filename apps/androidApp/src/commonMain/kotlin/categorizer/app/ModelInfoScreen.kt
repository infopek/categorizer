package categorizer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ModelNotice(val name: String, val license: String, val acknowledgement: String)
data class ModelInfo(val modelVersion: String?, val catalogId: String, val classes: List<String>, val notices: List<ModelNotice>)
sealed interface ModelInfoUiState { data object Loading : ModelInfoUiState; data class Ready(val info: ModelInfo) : ModelInfoUiState; data class Invalid(val message: String) : ModelInfoUiState }

@Composable fun ModelInfoScreen(state: ModelInfoUiState, onBack: () -> Unit) {
    MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("About recognition", style = MaterialTheme.typography.headlineMedium)
        when (state) {
            ModelInfoUiState.Loading -> { CircularProgressIndicator(); Text("Reading bundled model information…") }
            is ModelInfoUiState.Invalid -> { Text("Model information unavailable", style = MaterialTheme.typography.titleLarge); Text(state.message); Text("Recognition stays disabled until a valid bundled manifest is installed. Your album remains available.") }
            is ModelInfoUiState.Ready -> {
                val info = state.info
                Text(if (info.modelVersion == null) "Model not installed in this build" else "Model version ${info.modelVersion}", style = MaterialTheme.typography.titleLarge)
                Text("Supported catalog: ${info.catalogId} · ${info.classes.size} car classes")
                Text("Private and offline", style = MaterialTheme.typography.titleMedium)
                Text("Photos, recognition processing, corrections, and album data remain on this device. The app has no account, telemetry, subscription, or remote recognition service.")
                Text("Recognition has limits", style = MaterialTheme.typography.titleMedium)
                Text("Results are ranked suggestions, not guaranteed exact identifications. Unsupported cars, unusual angles, poor lighting, modifications, or similar generations can produce uncertain or incorrect suggestions. Confirm or correct every result before saving.")
                Text("Supported cars", style = MaterialTheme.typography.titleMedium)
                Text(info.classes.joinToString(" • "))
                Text("Licenses and acknowledgements", style = MaterialTheme.typography.titleMedium)
                info.notices.forEach { notice -> Text(notice.name, style = MaterialTheme.typography.titleSmall); Text("${notice.license}\n${notice.acknowledgement}") }
            }
        }
        TextButton(onClick = onBack) { Text("Back to album") }
    } } }
}
