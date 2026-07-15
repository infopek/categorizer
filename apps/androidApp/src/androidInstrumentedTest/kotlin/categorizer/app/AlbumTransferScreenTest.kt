package categorizer.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test

class AlbumTransferScreenTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    @Before fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() = scenario.close()

    @Test fun validPreviewRequiresExplicitConfirmation() {
        var confirms = 0
        show(TransferUiState.Preview("archive-example", 3, 2, 4096, 1)) { confirms++ }
        findText("Valid backup")
        findText("Existing entries will be kept and conflicting backup entries skipped.")
        screenshot("valid-preview")
        assertEquals(0, confirms)
        click(findText("Confirm import"))
        assertEquals(1, confirms)
    }

    @Test fun invalidArchiveHasNoConfirmationAndIsRecoverable() {
        show(TransferUiState.Error("Invalid backup", listOf("UNSUPPORTED_VERSION: archive is newer")))
        findText("Invalid backup")
        findText("Your album was not changed. You can try again.")
        screenshot("invalid-archive")
        assertEquals(null, nodes().firstOrNull { it.text?.toString() == "Confirm import" })
        findText("Import backup")
    }

    private fun show(state: TransferUiState, confirm: () -> Unit = {}) { scenario.onActivity { it.setContent { AlbumTransferScreen(state, {}, {}, {}, confirm) } }; InstrumentationRegistry.getInstrumentation().waitForIdleSync() }
    private fun findText(value: String): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            nodes().firstOrNull { it.text?.toString() == value }?.let { return it }
            Thread.sleep(50)
        }
        return assertNotNull(null, "Missing $value")
    }
    private fun nodes(): List<AccessibilityNodeInfo> { val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList(); return buildList { root.collect(this) } }
    private fun AccessibilityNodeInfo.collect(out: MutableList<AccessibilityNodeInfo>) { out += this; repeat(childCount) { getChild(it)?.collect(out) } }
    private fun click(node: AccessibilityNodeInfo) { var current: AccessibilityNodeInfo? = node; while (current != null && !current.isClickable) current = current.parent; checkNotNull(current).performAction(AccessibilityNodeInfo.ACTION_CLICK); InstrumentationRegistry.getInstrumentation().waitForIdleSync() }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        val context = instrumentation.targetContext
        val temporary = File(context.cacheDir, "ui-005-$name.png")
        FileOutputStream(temporary).use { output ->
            check(checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ui-005-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Categorizer/UI-005")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri).use { output -> temporary.inputStream().use { it.copyTo(checkNotNull(output)) } }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        temporary.delete()
    }
}
