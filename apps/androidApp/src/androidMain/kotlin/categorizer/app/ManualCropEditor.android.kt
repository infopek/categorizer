package categorizer.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import categorizer.domain.ManagedImageRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun ManualCropEditor(
    image: ManagedImageRef,
    selection: CropSelection,
    onSelectionChanged: (CropSelection) -> Unit,
    modifier: Modifier
) {
    val root = LocalContext.current.applicationContext.filesDir
    val bitmap by produceState<android.graphics.Bitmap?>(null, image.relativePath) {
        value = withContext(Dispatchers.IO) {
            val file = File(root, image.relativePath).canonicalFile
            file.takeIf { it.path.startsWith(root.canonicalPath + File.separator) && it.isFile }
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
    }
    val loaded = bitmap
    if (loaded == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("No photo") }
        return
    }
    var start = Offset.Zero
    Box(modifier) {
        Image(loaded.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        Canvas(
            Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.pointerInput(image.imageId) {
                detectDragGestures(
                    onDragStart = { point -> start = point },
                    onDrag = { change, _ ->
                        val canvasRatio = size.width.toFloat() / size.height
                        val imageRatio = loaded.width.toFloat() / loaded.height
                        val displayWidth = if (canvasRatio > imageRatio) size.height * imageRatio else size.width.toFloat()
                        val displayHeight = if (canvasRatio > imageRatio) size.height.toFloat() else size.width / imageRatio
                        val offsetX = (size.width - displayWidth) / 2
                        val offsetY = (size.height - displayHeight) / 2
                        val left = ((minOf(start.x, change.position.x) - offsetX) / displayWidth).coerceIn(0f, 1f)
                        val top = ((minOf(start.y, change.position.y) - offsetY) / displayHeight).coerceIn(0f, 1f)
                        val right = ((maxOf(start.x, change.position.x) - offsetX) / displayWidth).coerceIn(0f, 1f)
                        val bottom = ((maxOf(start.y, change.position.y) - offsetY) / displayHeight).coerceIn(0f, 1f)
                        if (right - left >= 0.05f && bottom - top >= 0.05f) onSelectionChanged(CropSelection(left, top, right, bottom))
                    }
                )
            }
        ) {
            drawRect(Color.Black.copy(alpha = 0.35f))
            val canvasRatio = size.width / size.height
            val imageRatio = loaded.width.toFloat() / loaded.height
            val displayWidth = if (canvasRatio > imageRatio) size.height * imageRatio else size.width
            val displayHeight = if (canvasRatio > imageRatio) size.height else size.width / imageRatio
            val offsetX = (size.width - displayWidth) / 2
            val offsetY = (size.height - displayHeight) / 2
            val topLeft = Offset(offsetX + selection.left * displayWidth, offsetY + selection.top * displayHeight)
            val cropSize = Size((selection.right - selection.left) * displayWidth, (selection.bottom - selection.top) * displayHeight)
            drawRect(Color.Transparent, topLeft, cropSize, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            drawRect(Color(0xFF00E676), topLeft, cropSize, style = Stroke(width = 4f))
        }
    }
}
