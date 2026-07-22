package categorizer.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import categorizer.application.AlbumBrowserState
import categorizer.application.AlbumIdentityFilter
import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumError
import categorizer.domain.AlbumErrorCode
import categorizer.domain.AlbumQuery
import categorizer.domain.AlbumSort
import categorizer.domain.CategoryIdentity
import categorizer.domain.ManagedImageRef
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AlbumBrowserScreenTest {
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchActivity() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeActivity() {
        scenario.close()
    }

    @Test
    fun emptyAlbumExplainsHowToStartAndNavigatesToAcquisition() {
        var addCount = 0
        show(AlbumBrowserState.EmptyCollection, onAddSighting = { addCount++ })

        findText("Your album is ready")
        click(findText("Add first sighting"))
        waitForUi()
        assertEquals(1, addCount)
        screenshot("empty")
    }

    @Test
    fun albumHeaderShowsAndRunsAddSightingAction() {
        var addCount = 0
        show(contentState(), onAddSighting = { addCount++ })

        click(findText("Add sighting"))
        waitForUi()
        assertEquals(1, addCount)
    }

    @Test
    fun populatedAlbumUsesStableCardsAndNavigatesByEntryId() {
        var openedEntryId: String? = null
        show(contentState(), onOpenEntry = { openedEntryId = it })

        findText("Mercedes-Benz C-Class (W205)")
        click(findText("Porsche 911 (992)"))
        waitForUi()
        assertEquals("entry-porsche", openedEntryId)
        screenshot("populated")
    }

    @Test
    fun searchAndFiltersEmitExplicitQueryChanges() {
        var search = ""
        var favorites = false
        show(contentState(), onSearchChanged = { search = it }, onFavoritesChanged = { favorites = it })

        val searchField = findNode { it.className == "android.widget.EditText" }
        click(searchField)
        searchField.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "Porsche"
                )
            }
        )
        click(findText("Favorites"))
        waitForUi()
        assertEquals("Porsche", search)
        assertEquals(true, favorites)
    }

    @Test
    fun noMatchesCanBeCleared() {
        var cleared = false
        show(
            AlbumBrowserState.NoMatches(AlbumQuery(text = "bee"), identities),
            query = AlbumQuery(text = "bee"),
            onClearFilters = { cleared = true }
        )
        findText("No matching species")
        screenshot("filtered")
        click(findText("Clear filters"))
        waitForUi()
        assertEquals(true, cleared)
    }

    @Test
    fun recoverableErrorOffersRetry() {
        var retries = 0
        show(
            AlbumBrowserState.Error(
                AlbumError(AlbumErrorCode.PERSISTENCE_FAILED, "Album database is busy", true)
            ),
            onRetry = { retries++ }
        )
        findText("Album unavailable")
        click(findText("Try again"))
        waitForUi()
        assertEquals(1, retries)
    }

    private fun show(
        state: AlbumBrowserState,
        query: AlbumQuery = AlbumQuery(),
        onSearchChanged: (String) -> Unit = {},
        onFavoritesChanged: (Boolean) -> Unit = {},
        onClearFilters: () -> Unit = {},
        onRetry: () -> Unit = {},
        onAddSighting: () -> Unit = {},
        onOpenEntry: (String) -> Unit = {}
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                AlbumBrowserScreen(
                    state, query, onSearchChanged, onFavoritesChanged,
                    {}, {}, onClearFilters, onRetry, onAddSighting, onOpenEntry
                )
            }
        }
        waitForUi()
    }

    private fun findText(text: String) = findNode { it.text?.toString() == text }

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
        repeat(childCount) { index ->
            getChild(index)?.findDepthFirst(predicate)?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun waitForUi() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(
            context.getExternalFilesDir(null),
            "ui-001"
        ).apply { mkdirs() }
        val temporary = File(directory, "$name.png")
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ui-001-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Categorizer/UI-001")
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

    private fun contentState() = AlbumBrowserState.Content(
        entries = entries,
        totalEntryCount = entries.size,
        query = AlbumQuery(sort = AlbumSort.NEWEST_FIRST),
        availableIdentities = identities
    )

    private companion object {
        const val UI_TIMEOUT_MS = 5_000L
        val identities = listOf(
            AlbumIdentityFilter("mercedes-benz-c-class-w205", "Mercedes-Benz C-Class (W205)"),
            AlbumIdentityFilter("porsche-911-992", "Porsche 911 (992)")
        )
        val entries = listOf(
            entry("entry-mercedes", "mercedes-benz-c-class-w205", "Mercedes-Benz", "C-Class", "W205", false),
            entry("entry-porsche", "porsche-911-992", "Porsche", "911", "992", true)
        )

        fun entry(
            id: String,
            classId: String,
            make: String,
            model: String,
            generation: String,
            favorite: Boolean
        ) = AlbumEntry(
            entryId = id,
            managedImage = ManagedImageRef("image-$id", "images/image-$id.jpg"),
            confirmedIdentity = CategoryIdentity(
                "cars", classId, "$make $model", "$make $model ($generation)", attributes = mapOf("generation_label" to generation)
            ),
            albumDate = "2026-07-14",
            isFavorite = favorite,
            notes = if (favorite) "Favorite weekend sighting" else "City center",
            createdAtEpochMs = 1,
            updatedAtEpochMs = 1,
            schemaVersion = 1
        )
    }
}
