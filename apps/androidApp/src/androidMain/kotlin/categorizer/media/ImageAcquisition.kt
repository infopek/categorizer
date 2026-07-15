package categorizer.media

import android.net.Uri
import categorizer.domain.ManagedImageRef

data class SanitizedImage(
    val reference: ManagedImageRef,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val decodeSampleSize: Int
)

sealed class ImageAcquisitionResult {
    data class Success(val image: SanitizedImage) : ImageAcquisitionResult()
    data class Failure(val error: ImageAcquisitionError) : ImageAcquisitionResult()
    data object Cancelled : ImageAcquisitionResult()
}

data class ImageAcquisitionError(
    val code: ImageAcquisitionErrorCode,
    val message: String,
    val recoverable: Boolean = true
)

enum class ImageAcquisitionErrorCode {
    SOURCE_UNAVAILABLE,
    UNREADABLE_INPUT,
    UNSUPPORTED_FORMAT,
    IMAGE_TOO_LARGE,
    STORAGE_UNAVAILABLE,
    OUT_OF_MEMORY,
    WRITE_FAILED
}

internal data class PendingCameraCapture(val fileName: String, val fileUri: Uri)
