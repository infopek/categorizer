package categorizer.application

import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumError
import categorizer.domain.AlbumObserver
import categorizer.domain.AlbumQuery
import categorizer.domain.AlbumRepository
import categorizer.domain.AlbumSort
import categorizer.domain.AlbumSubscription

data class AlbumIdentityFilter(val classId: String, val displayName: String)

sealed class AlbumBrowserState {
    data object Loading : AlbumBrowserState()
    data object EmptyCollection : AlbumBrowserState()

    data class NoMatches(
        val query: AlbumQuery,
        val availableIdentities: List<AlbumIdentityFilter>
    ) : AlbumBrowserState()

    data class Content(
        val entries: List<AlbumEntry>,
        val totalEntryCount: Int,
        val query: AlbumQuery,
        val availableIdentities: List<AlbumIdentityFilter>
    ) : AlbumBrowserState()

    data class Error(val error: AlbumError) : AlbumBrowserState()
}

/** Owns observable album query state without coupling presentation to a platform lifecycle. */
class AlbumBrowserController(
    private val repository: AlbumRepository,
    private val onStateChanged: (AlbumBrowserState) -> Unit
) : AutoCloseable {
    var query: AlbumQuery = AlbumQuery()
        private set

    private var allEntries: List<AlbumEntry>? = null
    private var visibleEntries: List<AlbumEntry>? = null
    private var allSubscription: AlbumSubscription? = null
    private var querySubscription: AlbumSubscription? = null
    private var closed = false

    fun start() {
        if (closed || allSubscription != null) return
        onStateChanged(AlbumBrowserState.Loading)
        allSubscription = repository.observe(AlbumQuery(), observer(isAllEntries = true))
        subscribeToQuery()
    }

    fun setSearchText(text: String) = updateQuery(query.copy(text = text))

    fun setFavoritesOnly(enabled: Boolean) = updateQuery(query.copy(favoritesOnly = enabled))

    fun setIdentityFilter(classId: String?) = updateQuery(query.copy(classId = classId))

    fun setSort(sort: AlbumSort) = updateQuery(query.copy(sort = sort))

    fun clearFilters() = updateQuery(AlbumQuery())

    fun retry() {
        if (closed) return
        allSubscription?.cancel()
        querySubscription?.cancel()
        allSubscription = null
        querySubscription = null
        allEntries = null
        visibleEntries = null
        start()
    }

    override fun close() {
        closed = true
        allSubscription?.cancel()
        querySubscription?.cancel()
        allSubscription = null
        querySubscription = null
    }

    private fun updateQuery(updated: AlbumQuery) {
        if (closed || updated == query) return
        query = updated
        visibleEntries = null
        onStateChanged(AlbumBrowserState.Loading)
        querySubscription?.cancel()
        subscribeToQuery()
    }

    private fun subscribeToQuery() {
        if (closed) return
        querySubscription = repository.observe(query, observer(isAllEntries = false))
    }

    private fun observer(isAllEntries: Boolean) = object : AlbumObserver {
        override fun onChanged(entries: List<AlbumEntry>) {
            if (closed) return
            if (isAllEntries) allEntries = entries else visibleEntries = entries
            publishState()
        }

        override fun onError(error: AlbumError) {
            if (!closed) onStateChanged(AlbumBrowserState.Error(error))
        }
    }

    private fun publishState() {
        val all = allEntries ?: return
        val visible = visibleEntries ?: return
        if (all.isEmpty()) {
            onStateChanged(AlbumBrowserState.EmptyCollection)
            return
        }
        val identities = all
            .distinctBy { it.confirmedIdentity.classId }
            .map { AlbumIdentityFilter(it.confirmedIdentity.classId, it.confirmedIdentity.displayName) }
            .sortedBy { it.displayName.lowercase() }
        if (visible.isEmpty()) {
            onStateChanged(AlbumBrowserState.NoMatches(query, identities))
        } else {
            onStateChanged(AlbumBrowserState.Content(visible, all.size, query, identities))
        }
    }
}
