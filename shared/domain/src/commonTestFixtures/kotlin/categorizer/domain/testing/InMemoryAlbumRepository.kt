package categorizer.domain.testing

import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumError
import categorizer.domain.AlbumErrorCode
import categorizer.domain.AlbumMutation
import categorizer.domain.AlbumObserver
import categorizer.domain.AlbumQuery
import categorizer.domain.AlbumRepository
import categorizer.domain.AlbumResult
import categorizer.domain.AlbumSort
import categorizer.domain.AlbumSubscription

class InMemoryAlbumRepository(initialEntries: List<AlbumEntry> = emptyList()) : AlbumRepository {
    private val entries = linkedMapOf<String, AlbumEntry>()
    private val observers = mutableListOf<ObserverRegistration>()

    init {
        initialEntries.forEach { entry ->
            require(entries.put(entry.entryId, entry) == null) {
                "initial entries must have unique entry IDs"
            }
        }
    }

    override suspend fun get(entryId: String): AlbumResult<AlbumEntry> =
        entries[entryId]?.let { AlbumResult.Success(it) } ?: notFound(entryId)

    override suspend fun query(query: AlbumQuery): AlbumResult<List<AlbumEntry>> =
        AlbumResult.Success(matchingEntries(query))

    override fun observe(query: AlbumQuery, observer: AlbumObserver): AlbumSubscription {
        val registration = ObserverRegistration(query, observer)
        observers += registration
        observer.onChanged(matchingEntries(query))
        return object : AlbumSubscription {
            override fun cancel() {
                registration.active = false
                observers.remove(registration)
            }
        }
    }

    override suspend fun create(entry: AlbumEntry): AlbumResult<AlbumMutation.Created> {
        if (entries.containsKey(entry.entryId)) {
            return AlbumResult.Failure(
                AlbumError(AlbumErrorCode.DUPLICATE_ID, "Entry ${entry.entryId} already exists", false)
            )
        }
        entries[entry.entryId] = entry
        notifyObservers()
        return AlbumResult.Success(AlbumMutation.Created(entry))
    }

    override suspend fun update(entry: AlbumEntry): AlbumResult<AlbumMutation.Updated> {
        if (!entries.containsKey(entry.entryId)) return notFound(entry.entryId)
        entries[entry.entryId] = entry
        notifyObservers()
        return AlbumResult.Success(AlbumMutation.Updated(entry))
    }

    override suspend fun delete(entryId: String): AlbumResult<AlbumMutation.Deleted> {
        val removed = entries.remove(entryId) ?: return notFound(entryId)
        notifyObservers()
        return AlbumResult.Success(AlbumMutation.Deleted(entryId, removed.managedImage))
    }

    private fun matchingEntries(query: AlbumQuery): List<AlbumEntry> {
        val needle = query.text.trim().lowercase()
        val filtered = entries.values.filter { entry ->
            (!query.favoritesOnly || entry.isFavorite) &&
                (query.classId == null || entry.confirmedIdentity.classId == query.classId) &&
                (needle.length == 0 || searchableText(entry).contains(needle))
        }
        return when (query.sort) {
            AlbumSort.NEWEST_FIRST -> filtered.sortedWith(
                compareByDescending<AlbumEntry> { it.albumDate }.thenBy { it.entryId }
            )
            AlbumSort.OLDEST_FIRST -> filtered.sortedWith(
                compareBy<AlbumEntry> { it.albumDate }.thenBy { it.entryId }
            )
            AlbumSort.IDENTITY_ASCENDING -> filtered.sortedWith(
                compareBy<AlbumEntry> { it.confirmedIdentity.displayName.lowercase() }
                    .thenBy { it.entryId }
            )
        }
    }

    private fun searchableText(entry: AlbumEntry): String = listOf(
        entry.confirmedIdentity.displayName,
        entry.confirmedIdentity.scientificName.orEmpty(),
        entry.confirmedIdentity.alternateNames.joinToString(" "),
        entry.confirmedIdentity.attributes.values.joinToString(" "),
        entry.notes
    ).joinToString(" ").lowercase()

    private fun notifyObservers() {
        observers.toList().filter { it.active }.forEach { registration ->
            registration.observer.onChanged(matchingEntries(registration.query))
        }
    }

    private fun <T> notFound(entryId: String): AlbumResult<T> = AlbumResult.Failure(
        AlbumError(AlbumErrorCode.NOT_FOUND, "Entry $entryId was not found", true)
    )

    private data class ObserverRegistration(
        val query: AlbumQuery,
        val observer: AlbumObserver,
        var active: Boolean = true
    )
}
