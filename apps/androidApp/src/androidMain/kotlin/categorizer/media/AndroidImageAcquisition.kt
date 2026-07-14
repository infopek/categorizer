package categorizer.media

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class AndroidImageAcquisition(
    private val activity: ComponentActivity,
    private val onResult: (ImageAcquisitionResult) -> Unit
) : Closeable {
    private val store = ManagedImageStore(activity)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraFiles = CameraCaptureFiles(activity)
    private var pendingCapture: PendingCameraCapture? = cameraFiles.restore()

    private val galleryLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) onResult(ImageAcquisitionResult.Cancelled) else sanitize(uri)
    }

    private val cameraLauncher = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val pending = pendingCapture
        pendingCapture = null
        if (!captured || pending == null) {
            pending?.let(cameraFiles::discard)
            onResult(ImageAcquisitionResult.Cancelled)
        } else {
            sanitize(pending.fileUri) { cameraFiles.discard(pending) }
        }
    }

    fun pickFromGallery() {
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun takePhoto() {
        pendingCapture?.let(cameraFiles::discard)
        pendingCapture = cameraFiles.create()
        cameraLauncher.launch(checkNotNull(pendingCapture).fileUri)
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun sanitize(uri: Uri, after: () -> Unit = {}) {
        executor.execute {
            val result = store.import(uri)
            after()
            mainHandler.post { onResult(result) }
        }
    }

}

internal class CameraCaptureFiles(private val activity: ComponentActivity) {
    private val directory = File(activity.cacheDir, CAMERA_DIRECTORY)

    fun create(): PendingCameraCapture {
        directory.mkdirs()
        val fileName = "capture-${UUID.randomUUID()}.jpg"
        val file = File(directory, fileName)
        check(file.createNewFile()) { "Could not create camera capture file" }
        marker().writeText(fileName)
        return pending(fileName, file)
    }

    fun restore(): PendingCameraCapture? {
        val name = marker().takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (name.isBlank() || '/' in name || '\\' in name) return null
        val file = File(directory, name)
        if (!file.isFile) { marker().delete(); return null }
        return pending(name, file)
    }

    fun discard(capture: PendingCameraCapture) {
        File(directory, capture.fileName).delete()
        marker().delete()
    }

    private fun pending(name: String, file: File) = PendingCameraCapture(
        name,
        FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
    )

    private fun marker() = File(activity.cacheDir, "pending-camera-capture")

    companion object { private const val CAMERA_DIRECTORY = "camera-capture" }
}
