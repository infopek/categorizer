package categorizer.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import categorizer.domain.ManagedImageRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun ManagedThumbnail(
    image: ManagedImageRef,
    contentDescription: String,
    modifier: Modifier
) {
    val filesDir = LocalContext.current.applicationContext.filesDir
    val bitmap by produceState<android.graphics.Bitmap?>(null, image.relativePath) {
        value = withContext(Dispatchers.IO) { decodeBounded(filesDir, image.relativePath) }
    }
    val loaded = bitmap
    if (loaded == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("No photo", style = MaterialTheme.typography.labelSmall)
        }
    } else {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

private fun decodeBounded(filesDir: File, relativePath: String): android.graphics.Bitmap? {
    val root = filesDir.canonicalFile
    val image = File(root, relativePath).canonicalFile
    if (!image.path.startsWith(root.path + File.separator) || !image.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(image.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > MAX_THUMBNAIL_PX ||
        bounds.outHeight / sampleSize > MAX_THUMBNAIL_PX
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}

private const val MAX_THUMBNAIL_PX = 512
