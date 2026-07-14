package categorizer.application

import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumError
import categorizer.domain.AlbumErrorCode
import categorizer.domain.AlbumObserver
import categorizer.domain.AlbumQuery
import categorizer.domain.AlbumRepository
import categorizer.domain.AlbumResult
import categorizer.domain.AlbumSort
import categorizer.domain.AlbumSubscription
import categorizer.domain.testing.ContractFixtures
import categorizer.domain.testing.InMemoryAlbumRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlbumBrowserControllerTest {
    @Test
    fun distinguishesEmptyCollectionFromNoMatches() {
        val emptyStates = mutableListOf<AlbumBrowserState>()
        AlbumBrowserController(InMemoryAlbumRepository(), emptyStates::add).apply {
            start()
            setSearchText("Porsche")
        }
        assertIs<AlbumBrowserState.EmptyCollection>(emptyStates.last())

        val populatedStates = mutableListOf<AlbumBrowserState>()
        AlbumBrowserController(
            InMemoryAlbumRepository(ContractFixtures.albumEntries), populatedStates::add
        ).apply {
            start()
            setSearchText("Mercedes-Benz")
        }
        assertIs<AlbumBrowserState.NoMatches>(populatedStates.last())
    }

    @Test
    fun searchFavoriteIdentityAndSortQueriesAreExplicitAndPredictable() {
        val states = mutableListOf<AlbumBrowserState>()
        val controller = AlbumBrowserController(
            InMemoryAlbumRepository(ContractFixtures.albumEntries), states::add
        )
        controller.start()

        controller.setSearchText("equal-score")
        assertEquals(listOf("entry-porsche"), states.content().entries.map { it.entryId })
        controller.setSearchText("")
        controller.setFavoritesOnly(true)
        assertEquals(listOf("entry-porsche"), states.content().entries.map { it.entryId })
        controller.setFavoritesOnly(false)
        controller.setIdentityFilter(ContractFixtures.manualIdentity.classId)
        assertEquals(listOf("entry-manual"), states.content().entries.map { it.entryId })
        controller.setIdentityFilter(null)
        controller.setSort(AlbumSort.OLDEST_FIRST)
        assertEquals(listOf("entry-porsche", "entry-manual"), states.content().entries.map { it.entryId })
    }

    @Test
    fun repositoryUpdatesRefreshVisibleFavoriteAndEditedText() = runTest {
        val repository = InMemoryAlbumRepository(ContractFixtures.albumEntries)
        val states = mutableListOf<AlbumBrowserState>()
        val controller = AlbumBrowserController(repository, states::add)
        controller.start()
        controller.setFavoritesOnly(true)

        val updated = ContractFixtures.manualEntry.copy(
            isFavorite = true,
            notes = "Unicode favorite: Méh 🐝",
            updatedAtEpochMs = ContractFixtures.manualEntry.updatedAtEpochMs + 1
        )
        repository.update(updated)

        assertEquals(2, states.content().entries.size)
        assertTrue(states.content().entries.any { it.notes == "Unicode favorite: Méh 🐝" })
    }

    @Test
    fun recoverableErrorsCanBeRetried() {
        val repository = FailingThenSuccessfulRepository(ContractFixtures.albumEntries)
        val states = mutableListOf<AlbumBrowserState>()
        val controller = AlbumBrowserController(repository, states::add)
        controller.start()
        assertIs<AlbumBrowserState.Error>(states.last())

        repository.fail = false
        controller.retry()
        assertEquals(2, states.content().entries.size)
    }

    @Test
    fun closingCancelsObservers() {
        val repository = TrackingRepository()
        val controller = AlbumBrowserController(repository) {}
        controller.start()
        controller.close()
        assertEquals(2, repository.cancelCount)
    }

    @Test
    fun syntheticLargeAlbumPreservesEveryStableEntryId() {
        val entries = (0 until 1_000).map { index ->
            ContractFixtures.favoriteEntry.copy(
                entryId = "entry-$index",
                managedImage = ContractFixtures.favoriteEntry.managedImage.copy(imageId = "image-$index")
            )
        }
        val states = mutableListOf<AlbumBrowserState>()
        AlbumBrowserController(InMemoryAlbumRepository(entries), states::add).start()

        val content = states.content()
        assertEquals(1_000, content.entries.size)
        assertEquals(1_000, content.entries.map { it.entryId }.distinct().size)
    }

    private fun List<AlbumBrowserState>.content() = assertIs<AlbumBrowserState.Content>(last())

    private class FailingThenSuccessfulRepository(entries: List<AlbumEntry>) :
        AlbumRepository by InMemoryAlbumRepository(entries) {
        var fail = true
        private val delegate = InMemoryAlbumRepository(entries)

        override fun observe(query: AlbumQuery, observer: AlbumObserver): AlbumSubscription {
            if (fail) {
                observer.onError(AlbumError(AlbumErrorCode.PERSISTENCE_FAILED, "Try again", true))
                return object : AlbumSubscription { override fun cancel() = Unit }
            }
            return delegate.observe(query, observer)
        }
    }

    private class TrackingRepository : AlbumRepository {
        var cancelCount = 0
        override fun observe(query: AlbumQuery, observer: AlbumObserver): AlbumSubscription {
            observer.onChanged(emptyList())
            return object : AlbumSubscription { override fun cancel() { cancelCount++ } }
        }
        override suspend fun get(entryId: String) = unsupported<AlbumEntry>()
        override suspend fun query(query: AlbumQuery) = unsupported<List<AlbumEntry>>()
        override suspend fun create(entry: AlbumEntry) = unsupported<categorizer.domain.AlbumMutation.Created>()
        override suspend fun update(entry: AlbumEntry) = unsupported<categorizer.domain.AlbumMutation.Updated>()
        override suspend fun delete(entryId: String) = unsupported<categorizer.domain.AlbumMutation.Deleted>()
        private fun <T> unsupported(): AlbumResult<T> = AlbumResult.Failure(
            AlbumError(AlbumErrorCode.PERSISTENCE_FAILED, "Unused", false)
        )
    }
}
