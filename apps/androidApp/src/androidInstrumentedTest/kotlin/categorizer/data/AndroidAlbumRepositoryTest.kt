package categorizer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumErrorCode
import categorizer.domain.AlbumMutation
import categorizer.domain.AlbumObserver
import categorizer.domain.AlbumQuery
import categorizer.domain.AlbumResult
import categorizer.domain.AlbumSort
import categorizer.domain.CarIdentity
import categorizer.domain.IdentitySource
import categorizer.domain.ManagedImageRef
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAlbumRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "album-repository-test.db"
    private lateinit var repository: AndroidAlbumRepository

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        repository = AndroidAlbumRepository(context, databaseName)
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
        File(context.filesDir, "images").deleteRecursively()
    }

    @Test
    fun contractCrudQueryObservationAndDeleteCleanupReference() {
        val first = entry("one", "2026-07-10", false, "Árvíztűrő tükörfúrógép")
        val second = entry("two", "2026-07-12", true, "weekend spot")
        createManagedImage(first)
        createManagedImage(second)
        val emissions = mutableListOf<List<AlbumEntry>>()
        val subscription = repository.observe(AlbumQuery(favoritesOnly = true), object : AlbumObserver {
            override fun onChanged(entries: List<AlbumEntry>) { emissions += entries }
            override fun onError(error: categorizer.domain.AlbumError) = error(error.message)
        })

        assertIs<AlbumResult.Success<AlbumMutation.Created>>(runSuspend { repository.create(first) })
        assertIs<AlbumResult.Success<AlbumMutation.Created>>(runSuspend { repository.create(second) })
        val duplicate = assertIs<AlbumResult.Failure>(runSuspend { repository.create(second) })
        assertEquals(AlbumErrorCode.DUPLICATE_ID, duplicate.error.code)

        val newest = success(runSuspend { repository.query(AlbumQuery()) })
        assertEquals(listOf("two", "one"), newest.map { it.entryId })
        val unicode = success(runSuspend { repository.query(AlbumQuery(text = "TÜKÖRFÚRÓGÉP")) })
        assertEquals(listOf("one"), unicode.map { it.entryId })
        val favorite = success(runSuspend {
            repository.query(AlbumQuery(favoritesOnly = true, sort = AlbumSort.OLDEST_FIRST))
        })
        assertEquals(listOf("two"), favorite.map { it.entryId })

        val updated = first.copy(isFavorite = true, notes = "", updatedAtEpochMs = 2)
        assertIs<AlbumResult.Success<AlbumMutation.Updated>>(runSuspend { repository.update(updated) })
        assertEquals("", success(runSuspend { repository.get("one") }).notes)
        assertEquals(2, emissions.last().size)

        val deleted = success(runSuspend { repository.delete("one") })
        assertEquals(first.managedImage, deleted.removedImage)
        assertEquals(AlbumErrorCode.NOT_FOUND, assertIs<AlbumResult.Failure>(
            runSuspend { repository.get("one") }
        ).error.code)
        subscription.cancel()
    }

    @Test
    fun entriesSurviveRepositoryRecreationAndSchemaContainsNoExternalUri() {
        val original = entry("restart", "2026-07-11", true, "persist me")
        createManagedImage(original)
        success(runSuspend { repository.create(original) })
        repository.close()

        repository = AndroidAlbumRepository(context, databaseName)
        assertEquals(original, success(runSuspend { repository.get(original.entryId) }))
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(ALBUM_DATABASE_VERSION, cursor.getInt(0))
            }
            database.rawQuery("PRAGMA table_info($ALBUM_TABLE)", null).use { cursor ->
                val columns = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.none { it.contains("uri", ignoreCase = true) || it.contains("absolute", ignoreCase = true) })
                assertTrue("image_relative_path" in columns)
            }
        }
    }

    @Test
    fun editedIdentityNotesAndFavoriteSurviveRestartAndAffectQueries() {
        val original = entry("edited", "2026-07-11", false, "before")
        createManagedImage(original)
        success(runSuspend { repository.create(original) })
        val edited = original.copy(
            confirmedIdentity = original.confirmedIdentity.copy(
                classId = "user:porsche-911-992",
                scientificName = "Porsche 911",
                attributes = mapOf("generation_label" to "992"),
                displayName = "Porsche 911 (992)",
                source = IdentitySource.USER_CONFIRMED
            ),
            isFavorite = true,
            notes = "Edited after review",
            updatedAtEpochMs = 2
        )
        success(runSuspend { repository.update(edited) })
        repository.close()

        repository = AndroidAlbumRepository(context, databaseName)
        assertEquals(edited, success(runSuspend { repository.get(edited.entryId) }))
        assertEquals(
            listOf(edited.entryId),
            success(runSuspend { repository.query(AlbumQuery(text = "after", favoritesOnly = true)) })
                .map { it.entryId }
        )
        assertEquals(
            listOf(edited.entryId),
            success(runSuspend { repository.query(AlbumQuery(classId = "user:porsche-911-992")) })
                .map { it.entryId }
        )
    }

    @Test
    fun missingManagedImageIsRejectedWithoutDurableMutation() {
        val missing = entry("missing", "2026-07-11", false, "")
        val failure = assertIs<AlbumResult.Failure>(runSuspend { repository.create(missing) })
        assertEquals(AlbumErrorCode.IMAGE_MISSING, failure.error.code)
        assertTrue(success(runSuspend { repository.query(AlbumQuery()) }).isEmpty())
    }

    private fun entry(id: String, date: String, favorite: Boolean, notes: String) = AlbumEntry(
        entryId = id,
        managedImage = ManagedImageRef("image-$id", "images/image-$id.jpg"),
        confirmedIdentity = CarIdentity(
            classId = "mercedes-benz-c-class-w206",
            make = "Mercedes-Benz",
            model = "C-Class",
            generationLabel = "W206",
            approximateYearRange = "2021-present",
            displayName = "Mercedes-Benz C-Class (W206)",
            source = IdentitySource.MODEL_CATALOG
        ),
        albumDate = date,
        isFavorite = favorite,
        notes = notes,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
        schemaVersion = 1
    )

    private fun <T> success(result: AlbumResult<T>): T = assertIs<AlbumResult.Success<T>>(result).value

    private fun createManagedImage(entry: AlbumEntry) {
        File(context.filesDir, entry.managedImage.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return checkNotNull(completed) { "Repository operation did not complete synchronously" }.getOrThrow()
    }
}
