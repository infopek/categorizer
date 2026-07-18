package categorizer.application

import categorizer.domain.AlbumEntry
import categorizer.domain.CarIdentity
import categorizer.domain.IdentitySource
import categorizer.domain.ManagedImageRef
import categorizer.domain.testing.InMemoryAlbumRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AlbumEntryEditorTest {
    @Test
    fun validatesRequiredIdentityWithoutMutatingStoredEntry() = runTest {
        val original = entry()
        val repository = InMemoryAlbumRepository(listOf(original))

        val invalid = AlbumEntryEditInput("", notes = "changed").validate(original, 20)

        assertIs<AlbumEntryEditValidation.Invalid>(invalid)
        assertEquals(original, assertIs<AlbumEntryEditorResult.Success<AlbumEntry>>(
            AlbumEntryEditor(repository).load(original.entryId)
        ).value)
    }

    @Test
    fun savesFavoriteNotesAndUserConfirmedIdentity() = runTest {
        val original = entry()
        val editor = AlbumEntryEditor(InMemoryAlbumRepository(listOf(original)))
        val valid = assertIs<AlbumEntryEditValidation.Valid>(
            AlbumEntryEditInput(" Eros blue ", " Polyommatus eros ", " Eros blue butterfly ", "male form", "Spotted downtown", true)
                .validate(original, 30)
        )

        val saved = assertIs<AlbumEntryEditorResult.Success<AlbumEntry>>(editor.save(valid.entry)).value

        assertEquals("Eros blue", saved.confirmedIdentity.displayName)
        assertEquals("Polyommatus eros", saved.confirmedIdentity.scientificName)
        assertEquals(listOf("Eros blue butterfly"), saved.confirmedIdentity.alternateNames)
        assertEquals(IdentitySource.USER_CONFIRMED, saved.confirmedIdentity.source)
        assertEquals("Spotted downtown", saved.notes)
        assertEquals(true, saved.isFavorite)
        assertEquals(30, saved.updatedAtEpochMs)
    }

    @Test
    fun deletionReturnsManagedImageForPlatformCleanup() = runTest {
        val original = entry()
        val editor = AlbumEntryEditor(InMemoryAlbumRepository(listOf(original)))

        val deleted = assertIs<AlbumEntryEditorResult.Success<categorizer.domain.AlbumMutation.Deleted>>(
            editor.delete(original.entryId)
        ).value

        assertEquals(original.managedImage, deleted.removedImage)
        assertIs<AlbumEntryEditorResult.Failed>(editor.load(original.entryId))
    }

    @Test
    fun concurrentDeletionTurnsSaveIntoRecoverableFailure() = runTest {
        val original = entry()
        val repository = InMemoryAlbumRepository(listOf(original))
        val editor = AlbumEntryEditor(repository)
        editor.delete(original.entryId)

        val result = assertIs<AlbumEntryEditorResult.Failed>(
            editor.save(original.copy(notes = "unsaved", updatedAtEpochMs = 20))
        )

        assertEquals(categorizer.domain.AlbumErrorCode.NOT_FOUND, result.error.code)
        assertEquals(true, result.error.recoverable)
    }

    private fun entry() = AlbumEntry(
        entryId = "entry-1",
        managedImage = ManagedImageRef("image-1", "images/image-1.jpg"),
        confirmedIdentity = CarIdentity("bmw-3", "BMW", "3 Series", displayName = "BMW 3 Series"),
        albumDate = "2026-07-15",
        isFavorite = false,
        notes = "",
        createdAtEpochMs = 10,
        updatedAtEpochMs = 10,
        schemaVersion = 1
    )
}
