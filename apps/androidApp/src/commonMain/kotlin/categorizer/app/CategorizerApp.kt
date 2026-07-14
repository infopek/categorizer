package categorizer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import categorizer.application.AlbumBrowserController
import categorizer.application.AlbumBrowserState
import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumQuery
import categorizer.domain.AlbumRepository
import categorizer.domain.AlbumSort
import categorizer.domain.ManagedImageRef

@Composable
fun CategorizerApp(
    repository: AlbumRepository,
    onAddSighting: () -> Unit = {},
    onOpenEntry: (String) -> Unit = {}
) {
    var state by remember(repository) { mutableStateOf<AlbumBrowserState>(AlbumBrowserState.Loading) }
    val controller = remember(repository) { AlbumBrowserController(repository) { state = it } }
    DisposableEffect(controller) {
        controller.start()
        onDispose(controller::close)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AlbumBrowserScreen(
                state = state,
                query = controller.query,
                onSearchChanged = controller::setSearchText,
                onFavoritesChanged = controller::setFavoritesOnly,
                onIdentityChanged = controller::setIdentityFilter,
                onSortChanged = controller::setSort,
                onClearFilters = controller::clearFilters,
                onRetry = controller::retry,
                onAddSighting = onAddSighting,
                onOpenEntry = onOpenEntry
            )
        }
    }
}

@Composable
internal fun AlbumBrowserScreen(
    state: AlbumBrowserState,
    query: AlbumQuery,
    onSearchChanged: (String) -> Unit,
    onFavoritesChanged: (Boolean) -> Unit,
    onIdentityChanged: (String?) -> Unit,
    onSortChanged: (AlbumSort) -> Unit,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    onAddSighting: () -> Unit,
    onOpenEntry: (String) -> Unit
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("My album", style = MaterialTheme.typography.headlineMedium)
                    Text("Your car sightings, kept on this device", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onAddSighting) { Text("Add") }
            }

            if (state !is AlbumBrowserState.EmptyCollection) {
                SearchAndFilters(
                    state, query, onSearchChanged, onFavoritesChanged,
                    onIdentityChanged, onSortChanged
                )
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state) {
                    AlbumBrowserState.Loading -> Text(
                        "Loading your album…",
                        Modifier.semantics { contentDescription = "Album loading" }
                    )
                    AlbumBrowserState.EmptyCollection -> EmptyCollection(onAddSighting)
                    is AlbumBrowserState.NoMatches -> NoMatches(onClearFilters)
                    is AlbumBrowserState.Error -> ErrorState(state, onRetry)
                    is AlbumBrowserState.Content -> AlbumList(state.entries, onOpenEntry)
                }
            }
        }
    }
}

@Composable
private fun SearchAndFilters(
    state: AlbumBrowserState,
    query: AlbumQuery,
    onSearchChanged: (String) -> Unit,
    onFavoritesChanged: (Boolean) -> Unit,
    onIdentityChanged: (String?) -> Unit,
    onSortChanged: (AlbumSort) -> Unit
) {
    val identities = when (state) {
        is AlbumBrowserState.Content -> state.availableIdentities
        is AlbumBrowserState.NoMatches -> state.availableIdentities
        else -> emptyList()
    }
    OutlinedTextField(
        value = query.text,
        onValueChange = onSearchChanged,
        label = { Text("Search cars or notes") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = query.favoritesOnly,
                onClick = { onFavoritesChanged(!query.favoritesOnly) },
                label = { Text("Favorites") }
            )
        }
        item {
            FilterChip(
                selected = query.classId == null,
                onClick = { onIdentityChanged(null) },
                label = { Text("All cars") }
            )
        }
        items(identities, key = { it.classId }) { identity ->
            FilterChip(
                selected = query.classId == identity.classId,
                onClick = { onIdentityChanged(identity.classId) },
                label = { Text(identity.displayName, maxLines = 1) }
            )
        }
        item {
            AssistChip(
                onClick = { onSortChanged(query.sort.next()) },
                label = { Text(query.sort.label()) }
            )
        }
    }
}

@Composable
private fun AlbumList(entries: List<AlbumEntry>, onOpenEntry: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().semantics {
            contentDescription = "Album with ${entries.size} entries"
        },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(entries, key = { it.entryId }) { entry ->
            Card(
                Modifier.fillMaxWidth().clickable { onOpenEntry(entry.entryId) }
                    .semantics { contentDescription = "Open ${entry.confirmedIdentity.displayName}" }
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ManagedThumbnail(
                        image = entry.managedImage,
                        contentDescription = "Photo of ${entry.confirmedIdentity.displayName}",
                        modifier = Modifier.size(88.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                entry.confirmedIdentity.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (entry.isFavorite) Text("★", modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(entry.albumDate, style = MaterialTheme.typography.labelMedium)
                        if (entry.notes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.notes,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun EmptyCollection(onAddSighting: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your album is ready", style = MaterialTheme.typography.titleLarge)
        Text("Add a car photo to start your local collection.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddSighting) { Text("Add first sighting") }
    }
}

@Composable
private fun NoMatches(onClearFilters: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No matching cars", style = MaterialTheme.typography.titleLarge)
        Text("Try another search or clear the filters.")
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onClearFilters) { Text("Clear filters") }
    }
}

@Composable
private fun ErrorState(state: AlbumBrowserState.Error, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Album unavailable", style = MaterialTheme.typography.titleLarge)
        Text(state.error.message)
        if (state.error.recoverable) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

private fun AlbumSort.next() = when (this) {
    AlbumSort.NEWEST_FIRST -> AlbumSort.OLDEST_FIRST
    AlbumSort.OLDEST_FIRST -> AlbumSort.IDENTITY_ASCENDING
    AlbumSort.IDENTITY_ASCENDING -> AlbumSort.NEWEST_FIRST
}

private fun AlbumSort.label() = when (this) {
    AlbumSort.NEWEST_FIRST -> "Newest first"
    AlbumSort.OLDEST_FIRST -> "Oldest first"
    AlbumSort.IDENTITY_ASCENDING -> "Car name A–Z"
}

@Composable
internal expect fun ManagedThumbnail(
    image: ManagedImageRef,
    contentDescription: String,
    modifier: Modifier = Modifier
)
