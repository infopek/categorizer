package categorizer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun AcquisitionScreen(
    state: AcquisitionScreenState,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onCropAndContinue: (CropSelection) -> Unit,
    onChooseAnother: () -> Unit
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
                        Text("Add a sighting", style = MaterialTheme.typography.headlineMedium)
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when (state) {
                            is AcquisitionScreenState.Choosing -> SourceChooser(state, onCamera, onGallery)
                            is AcquisitionScreenState.Launching -> Progress(
                                if (state.source == AcquisitionSource.CAMERA) "Opening camera…" else "Opening photos…"
                            )
                            is AcquisitionScreenState.Processing -> Progress("Preparing photo…")
                            is AcquisitionScreenState.Review -> RecognitionHandoff(
                                state, onContinue, onCropAndContinue, onChooseAnother
                            )
                            is AcquisitionScreenState.Error -> AcquisitionError(state, onRetry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChooser(
    state: AcquisitionScreenState.Choosing,
    onCamera: () -> Unit,
    onGallery: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Choose a photo source", style = MaterialTheme.typography.titleLarge)
        Text(
            "Take a new car photo or choose one already on this device.",
            textAlign = TextAlign.Center
        )
        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Text("Take photo") }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
            Text("Choose from photos")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Photos are copied into private app storage and stay on this device.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Progress(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Please keep Categorizer open.")
    }
}

@Composable
private fun RecognitionHandoff(
    state: AcquisitionScreenState.Review,
    onContinue: () -> Unit,
    onCropAndContinue: (CropSelection) -> Unit,
    onChooseAnother: () -> Unit
) {
    var selection by remember(state.image.imageId) { mutableStateOf(CropSelection()) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Photo ready", style = MaterialTheme.typography.titleLarge)
        Text("Drag a box around the butterfly or moth, or use the full photo.")
        Spacer(Modifier.height(20.dp))
        ManualCropEditor(
            image = state.image,
            selection = selection,
            onSelectionChanged = { selection = it },
            modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(18.dp))
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onCropAndContinue(selection) }, modifier = Modifier.fillMaxWidth()) {
            Text("Crop and recognize")
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Use full photo")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onChooseAnother, modifier = Modifier.fillMaxWidth()) {
            Text("Choose another photo")
        }
    }
}

internal data class CropSelection(
    val left: Float = 0.1f,
    val top: Float = 0.1f,
    val right: Float = 0.9f,
    val bottom: Float = 0.9f
)

@Composable
internal expect fun ManualCropEditor(
    image: categorizer.domain.ManagedImageRef,
    selection: CropSelection,
    onSelectionChanged: (CropSelection) -> Unit,
    modifier: Modifier = Modifier
)

@Composable
private fun AcquisitionError(state: AcquisitionScreenState.Error, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn’t prepare photo", style = MaterialTheme.typography.titleLarge)
        Text(state.message, textAlign = TextAlign.Center)
        if (state.recoverable) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("Choose another photo") }
        }
    }
}
