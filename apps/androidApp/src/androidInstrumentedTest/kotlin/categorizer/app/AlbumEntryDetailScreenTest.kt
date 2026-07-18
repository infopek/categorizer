package categorizer.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import categorizer.application.AlbumEntryEditInput
import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumError
import categorizer.domain.AlbumErrorCode
import categorizer.domain.CategoryIdentity
import categorizer.domain.ManagedImageRef
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AlbumEntryDetailScreenTest {
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() = scenario.close()

    @Test
    fun detailSupportsFavoriteAndEditingWithoutExposingInternalIds() {
        var favoriteEntry: AlbumEntry? = null
        show(onToggleFavorite = { favoriteEntry = it })

        findText("Mercedes-Benz C-Class (W205)")
        findText("Spotted in Budapest")
        screenshot("detail")
        click(findText("☆ Add favorite"))
        assertEquals(entry.entryId, favoriteEntry?.entryId)

        click(findText("Edit details"))
        scrollToEnd()
        findText("Personal notes")
        screenshot("editing")
        click(findText("Cancel editing"))
        findText("Spotted in Budapest")
    }

    @Test
    fun blankFieldsAreRejectedAndSaveFailureIsVisible() {
        show(
            EntryDetailUiState.Ready(
                entry,
                error = AlbumError(AlbumErrorCode.PERSISTENCE_FAILED, "Could not save changes", true)
            )
        )
        click(findText("Edit details"))
        scrollToEnd()
        findText("Could not save changes")
        scrollToStart()
        val fields = waitForNodes(2) { it.className == "android.widget.EditText" }
        setText(fields[0], "")
        setText(fields[1], "")
        scrollToEnd()
        click(findText("Save changes"))
        scrollToStart()
        findText("Name is required")
    }

    @Test
    fun deletionRequiresExplicitConfirmation() {
        var deletes = 0
        show(onDelete = { deletes++ })
        click(findText("Delete sighting"))
        findText("Delete this sighting?")
        findText("This removes the album entry and its managed photo copy from this device. This cannot be undone.")
        screenshot("delete-confirmation")
        click(findText("Keep sighting"))
        assertEquals(0, deletes)
        click(findText("Delete sighting"))
        click(findText("Delete permanently"))
        assertEquals(1, deletes)
    }

    private fun show(
        state: EntryDetailUiState = EntryDetailUiState.Ready(entry),
        onToggleFavorite: (AlbumEntry) -> Unit = {},
        onDelete: () -> Unit = {}
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                AlbumEntryDetailScreen(state, {}, {}, onToggleFavorite, {}, onDelete)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun findText(text: String) = findNode { it.text?.toString() == text }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            findNodes(predicate).firstOrNull()?.let { return it }
            Thread.sleep(50)
        }
        return assertNotNull(null, "Expected accessibility node was not visible")
    }

    private fun findNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        return buildList { root.collect(predicate, this) }
    }

    private fun waitForNodes(count: Int, predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            findNodes(predicate).takeIf { it.size >= count }?.let { return it }
            Thread.sleep(50)
        }
        return findNodes(predicate)
    }

    private fun AccessibilityNodeInfo.collect(
        predicate: (AccessibilityNodeInfo) -> Boolean,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (predicate(this)) result += this
        repeat(childCount) { getChild(it)?.collect(predicate, result) }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun scrollToStart() = scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun scrollToEnd() = scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    private fun scroll(action: Int) {
        repeat(5) {
            findNodes { it.isScrollable }.firstOrNull()?.performAction(action)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        val context = instrumentation.targetContext
        val temporary = File(context.cacheDir, "ui-004-$name.png")
        FileOutputStream(temporary).use {
            check(checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ui-004-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Categorizer/UI-004")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri).use { output ->
            temporary.inputStream().use { it.copyTo(checkNotNull(output)) }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        temporary.delete()
    }

    private companion object {
        val entry = AlbumEntry(
            "entry-secret", ManagedImageRef("image-secret", "images/missing-fixture.jpg"),
            CategoryIdentity("cars", "mercedes-c-w205", "Mercedes-Benz C-Class", "Mercedes-Benz C-Class (W205)", attributes = mapOf("generation_label" to "W205", "approximate_year_range" to "2014-2021")),
            "2026-07-15", false, "Spotted in Budapest", 10, 10, 1
        )
    }
}
