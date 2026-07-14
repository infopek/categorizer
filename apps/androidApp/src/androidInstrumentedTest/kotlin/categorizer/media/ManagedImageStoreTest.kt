package categorizer.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import categorizer.app.MainActivity

@RunWith(AndroidJUnit4::class)
class ManagedImageStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: ManagedImageStore
    private val fixtures get() = File(context.cacheDir, "camera-capture")

    @Before
    fun setUp() {
        fixtures.mkdirs()
        store = ManagedImageStore(context)
    }

    @After
    fun tearDown() {
        fixtures.deleteRecursively()
        File(context.filesDir, "images").deleteRecursively()
    }

    @Test
    fun rotatedMetadataRichImageBecomesOrientedAndMetadataFree() {
        val source = jpegFixture("rotated.jpg", 120, 80)
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "47/1,29/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_MAKE, "Fixture Camera")
            saveAttributes()
        }

        val imported = store.import(uri(source), "rotated")
        val success = assertIs<ImageAcquisitionResult.Success>(imported, imported.toString())
        assertEquals(80, success.image.width)
        assertEquals(120, success.image.height)
        val output = assertNotNull(store.resolve(success.image.reference))
        val inspected = ExifInterface(output.absolutePath)
        assertEquals(null, inspected.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals(null, inspected.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, inspected.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
        ))
    }

    @Test
    fun largeImageUsesBoundedDecodeAndReportsMemoryObservation() {
        val source = jpegFixture("large.jpg", 6000, 4000)
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val imported = store.import(uri(source), "large")
        val success = assertIs<ImageAcquisitionResult.Success>(imported, imported.toString())
        val after = runtime.totalMemory() - runtime.freeMemory()

        assertTrue(success.image.decodeSampleSize >= 2)
        assertTrue(maxOf(success.image.width, success.image.height) <= 4096)
        assertTrue(success.image.width.toLong() * success.image.height <= 16_000_000L)
        assertTrue(after - before < 256L * 1024L * 1024L, "Observed heap delta: ${after - before}")
        println(
            "MEDIA_MEMORY source=${success.image.sourceWidth}x${success.image.sourceHeight} " +
                "managed=${success.image.width}x${success.image.height} " +
                "sample=${success.image.decodeSampleSize} heap_delta_bytes=${after - before}"
        )
    }

    @Test
    fun malformedInputFailsWithoutOrphanedManagedFile() {
        val source = File(fixtures, "malformed.jpg").apply { writeText("not an image") }
        val result = assertIs<ImageAcquisitionResult.Failure>(store.import(uri(source), "malformed"))
        assertEquals(ImageAcquisitionErrorCode.UNSUPPORTED_FORMAT, result.error.code, result.error.message)
        assertFalse(File(context.filesDir, "images/malformed.jpg").exists())
        assertTrue(File(context.filesDir, "images").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun managedDeleteIsIdempotent() {
        val source = jpegFixture("delete.jpg", 32, 32)
        val imported = store.import(uri(source), "delete")
        val success = assertIs<ImageAcquisitionResult.Success>(imported, imported.toString())
        assertTrue(store.delete(success.image.reference))
        assertTrue(store.delete(success.image.reference))
    }

    @Test
    fun canceledCameraCaptureRemovesRecoverablePendingFile() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val firstOwner = CameraCaptureFiles(activity)
                val created = firstOwner.create()
                val restored = assertNotNull(CameraCaptureFiles(activity).restore())
                assertEquals(created.fileName, restored.fileName)

                firstOwner.discard(restored)
                assertEquals(null, CameraCaptureFiles(activity).restore())
                assertFalse(File(activity.cacheDir, "camera-capture/${created.fileName}").exists())
            }
        }
    }

    private fun jpegFixture(name: String, width: Int, height: Int): File {
        val file = File(fixtures, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 80, 140))
        FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        bitmap.recycle()
        return file
    }

    private fun uri(file: File) = Uri.fromFile(file)
}
