package categorizer.domain

data class AlbumEntry(
    val entryId: String,
    val managedImage: ManagedImageRef,
    val confirmedIdentity: CategoryIdentity,
    val albumDate: String,
    val isFavorite: Boolean,
    val notes: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val schemaVersion: Int
) {
    init {
        require(entryId.isNotBlank()) { "entryId must not be blank" }
        require(ISO_DATE.matches(albumDate)) { "albumDate must use YYYY-MM-DD" }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs must not be negative" }
        require(updatedAtEpochMs >= createdAtEpochMs) { "updatedAtEpochMs must not precede creation" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
    }

    companion object { private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}") }
}

data class AlbumQuery(
    val text: String = "",
    val favoritesOnly: Boolean = false,
    val classId: String? = null,
    val sort: AlbumSort = AlbumSort.NEWEST_FIRST
)

enum class AlbumSort { NEWEST_FIRST, OLDEST_FIRST, IDENTITY_ASCENDING }

sealed class AlbumMutation {
    data class Created(val entry: AlbumEntry) : AlbumMutation()
    data class Updated(val entry: AlbumEntry) : AlbumMutation()
    data class Deleted(val entryId: String, val removedImage: ManagedImageRef) : AlbumMutation()
}

sealed class AlbumResult<out T> {
    data class Success<T>(val value: T) : AlbumResult<T>()
    data class Failure(val error: AlbumError) : AlbumResult<Nothing>()
}

data class AlbumError(val code: AlbumErrorCode, val message: String, val recoverable: Boolean) {
    init { require(message.isNotBlank()) { "message must not be blank" } }
}

enum class AlbumErrorCode {
    NOT_FOUND, DUPLICATE_ID, IMAGE_MISSING, VALIDATION_FAILED, STORAGE_UNAVAILABLE, PERSISTENCE_FAILED
}

interface AlbumObserver {
    fun onChanged(entries: List<AlbumEntry>)
    fun onError(error: AlbumError)
}

interface AlbumSubscription { fun cancel() }

/** Platform-neutral, restart-safe persistence boundary. */
interface AlbumRepository {
    suspend fun get(entryId: String): AlbumResult<AlbumEntry>
    suspend fun query(query: AlbumQuery): AlbumResult<List<AlbumEntry>>
    fun observe(query: AlbumQuery, observer: AlbumObserver): AlbumSubscription
    suspend fun create(entry: AlbumEntry): AlbumResult<AlbumMutation.Created>
    suspend fun update(entry: AlbumEntry): AlbumResult<AlbumMutation.Updated>
    suspend fun delete(entryId: String): AlbumResult<AlbumMutation.Deleted>
}
