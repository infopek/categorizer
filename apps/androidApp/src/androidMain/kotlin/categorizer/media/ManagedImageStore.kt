package categorizer.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.StatFs
import categorizer.domain.ManagedImageRef
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class ManagedImageStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val imageDirectory = File(applicationContext.filesDir, IMAGE_DIRECTORY)

    fun import(source: Uri, imageId: String = UUID.randomUUID().toString()): ImageAcquisitionResult {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var temporary: File? = null
        try {
            val bounds = readBounds(source)
            if (bounds.first <= 0 || bounds.second <= 0) return unsupported("Image dimensions are unavailable")
            if (bounds.first.toLong() * bounds.second.toLong() > MAX_SOURCE_PIXELS) {
                return failure(ImageAcquisitionErrorCode.IMAGE_TOO_LARGE, "Image exceeds the 100 megapixel safety limit")
            }
            val sample = calculateSampleSize(bounds.first, bounds.second)
            ensureStorageAvailable(bounds.first, bounds.second, sample)
            val orientation = readOrientation(source)
            decoded = decode(source, sample) ?: return unsupported("The selected image could not be decoded")
            oriented = applyOrientation(decoded, orientation)

            imageDirectory.mkdirs()
            check(imageDirectory.isDirectory) { "Managed image directory is unavailable" }
            val reference = ManagedImageRef(imageId, "$IMAGE_DIRECTORY/$imageId.jpg")
            val destination = File(applicationContext.filesDir, reference.relativePath)
            temporary = File(imageDirectory, ".$imageId-${UUID.randomUUID()}.tmp")
            FileOutputStream(temporary).use { output ->
                check(oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "JPEG encoder rejected the managed image"
                }
                output.flush()
                output.fd.sync()
            }
            moveAtomically(temporary, destination)
            temporary = null
            return ImageAcquisitionResult.Success(
                SanitizedImage(
                    reference = reference,
                    width = oriented.width,
                    height = oriented.height,
                    sourceWidth = bounds.first,
                    sourceHeight = bounds.second,
                    decodeSampleSize = sample
                )
            )
        } catch (_: SecurityException) {
            return failure(ImageAcquisitionErrorCode.UNREADABLE_INPUT, "Access to the selected image was revoked")
        } catch (error: OutOfMemoryError) {
            return failure(ImageAcquisitionErrorCode.OUT_OF_MEMORY, error.message ?: "Image requires too much memory")
        } catch (error: IOException) {
            return failure(ImageAcquisitionErrorCode.STORAGE_UNAVAILABLE, error.message ?: "Image storage is unavailable")
        } catch (error: IllegalStateException) {
            return failure(ImageAcquisitionErrorCode.WRITE_FAILED, error.message ?: "Managed image could not be written")
        } finally {
            temporary?.delete()
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
        }
    }

    fun delete(reference: ManagedImageRef): Boolean {
        val root = applicationContext.filesDir.canonicalFile
        val candidate = File(root, reference.relativePath).canonicalFile
        if (!candidate.path.startsWith(root.path + File.separator)) return false
        return !candidate.exists() || candidate.delete()
    }

    fun resolve(reference: ManagedImageRef): File? {
        val root = applicationContext.filesDir.canonicalFile
        val candidate = File(root, reference.relativePath).canonicalFile
        return candidate.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    private fun readBounds(source: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = applicationContext.contentResolver.openInputStream(source)
            ?: throw IOException("Selected image cannot be opened")
        input.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        return options.outWidth to options.outHeight
    }

    private fun readOrientation(source: Uri): Int = try {
        applicationContext.contentResolver.openInputStream(source)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun decode(source: Uri, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return applicationContext.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Selected image cannot be reopened")
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (
            maxOf(width, height) / sample > MAX_EDGE ||
            (width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODED_PIXELS
        ) sample *= 2
        return sample
    }

    private fun ensureStorageAvailable(width: Int, height: Int, sample: Int) {
        val estimatedPixels = (width.toLong() / sample) * (height.toLong() / sample)
        val minimumBytes = maxOf(MINIMUM_FREE_BYTES, estimatedPixels * 4L)
        if (StatFs(applicationContext.filesDir.path).availableBytes < minimumBytes) {
            throw IOException("Not enough private storage to process the image")
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun unsupported(message: String) = failure(ImageAcquisitionErrorCode.UNSUPPORTED_FORMAT, message)

    private fun failure(code: ImageAcquisitionErrorCode, message: String) =
        ImageAcquisitionResult.Failure(ImageAcquisitionError(code, message))

    companion object {
        private const val IMAGE_DIRECTORY = "images"
        private const val MAX_EDGE = 4096
        private const val MAX_DECODED_PIXELS = 16_000_000L
        private const val MAX_SOURCE_PIXELS = 100_000_000L
        private const val MINIMUM_FREE_BYTES = 16L * 1024L * 1024L
        private const val JPEG_QUALITY = 92
    }
}
