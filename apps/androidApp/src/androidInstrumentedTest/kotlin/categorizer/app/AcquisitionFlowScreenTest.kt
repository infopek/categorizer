package categorizer.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import categorizer.media.CameraCaptureFiles
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AcquisitionFlowScreenTest {
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario.close()
    }

    @Test
    fun albumAddOpensChooserAndSurvivesRecreation() {
        click(findText("Add"))
        findText("Choose a photo source")
        screenshot("chooser")

        scenario.recreate()
        findText("Choose a photo source")
        findText("Take photo")
        findText("Choose from photos")
    }

    @Test
    fun cancelingSystemPhotoPickerReturnsToRecoverableChooser() {
        click(findText("Add"))
        click(findText("Choose from photos"))
        Thread.sleep(500)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_BACK").close()

        findText("No photo was selected.")
        findText("Choose from photos")
    }

    @Test
    fun cameraCaptureContractHasHandlerAndCleansPendingFile() {
        scenario.onActivity { activity ->
            val files = CameraCaptureFiles(activity)
            val pending = files.create()
            val intent = ActivityResultContracts.TakePicture().createIntent(activity, pending.fileUri)
            assertNotNull(intent.resolveActivity(activity.packageManager))
            files.discard(pending)
        }
    }

    @Test
    fun recoverableAcquisitionErrorHasValidNextActions() {
        scenario.onActivity { activity ->
            activity.setContent {
                AcquisitionScreen(
                    state = AcquisitionScreenState.Error("Selected photo cannot be decoded.", true),
                    onCamera = {}, onGallery = {}, onRetry = {}, onCancel = {},
                    onContinue = {}, onChooseAnother = {}
                )
            }
        }
        findText("Couldn’t prepare photo")
        findText("Choose another photo")
        screenshot("recoverable-error")
    }

    @Test
    fun manifestRequestsNoCameraOrBroadMediaPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toSet()

        assertTrue("android.permission.CAMERA" !in permissions)
        assertTrue("android.permission.READ_MEDIA_IMAGES" !in permissions)
        assertTrue("android.permission.READ_EXTERNAL_STORAGE" !in permissions)
    }

    private fun findText(text: String): AccessibilityNodeInfo = findNode { it.text?.toString() == text }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            root?.findDepthFirst(predicate)?.let { return it }
            Thread.sleep(50)
        }
        return assertNotNull(null, "Expected accessibility node was not visible")
    }

    private fun AccessibilityNodeInfo.findDepthFirst(
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(this)) return this
        repeat(childCount) { index -> getChild(index)?.findDepthFirst(predicate)?.let { return it } }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        val context = instrumentation.targetContext
        val temporary = File(context.cacheDir, "ui-002-$name.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ui-002-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Categorizer/UI-002")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri).use { output ->
            checkNotNull(output)
            temporary.inputStream().use { input -> input.copyTo(output) }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        temporary.delete()
    }

    private companion object { const val UI_TIMEOUT_MS = 5_000L }
}
