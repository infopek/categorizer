package categorizer.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
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
import categorizer.domain.ManagedImageRef
import java.io.File
import java.io.IOException
import java.util.Locale

class AndroidAlbumRepository(
    context: Context,
    databaseName: String = "categorizer-album.db"
) : AlbumRepository, AutoCloseable {
    private val database = AlbumDatabase(context, databaseName)
    private val privateFilesDirectory = context.applicationContext.filesDir
    private val lock = Any()
    private val observers = mutableListOf<ObserverRegistration>()

    override suspend fun get(entryId: String): AlbumResult<AlbumEntry> = guarded {
        synchronized(lock) { findEntry(entryId) }?.let { AlbumResult.Success(it) } ?: notFound(entryId)
    }

    override suspend fun query(query: AlbumQuery): AlbumResult<List<AlbumEntry>> = guarded {
        AlbumResult.Success(synchronized(lock) { matchingEntries(query) })
    }

    override fun observe(query: AlbumQuery, observer: AlbumObserver): AlbumSubscription {
        val registration = ObserverRegistration(query, observer)
        synchronized(lock) { observers += registration }
        publish(registration)
        return object : AlbumSubscription {
            override fun cancel() {
                synchronized(lock) {
                    registration.active = false
                    observers.remove(registration)
                }
            }
        }
    }

    override suspend fun create(entry: AlbumEntry): AlbumResult<AlbumMutation.Created> = guarded {
        if (!managedImageExists(entry.managedImage)) return@guarded imageMissing(entry.managedImage)
        synchronized(lock) {
            try {
                database.writableDatabase.insertOrThrow(ALBUM_TABLE, null, entry.toValues())
            } catch (_: SQLiteConstraintException) {
                return@guarded duplicate(entry.entryId)
            }
        }
        notifyObservers()
        AlbumResult.Success(AlbumMutation.Created(entry))
    }

    override suspend fun update(entry: AlbumEntry): AlbumResult<AlbumMutation.Updated> = guarded {
        if (!managedImageExists(entry.managedImage)) return@guarded imageMissing(entry.managedImage)
        val count = synchronized(lock) {
            database.writableDatabase.update(
                ALBUM_TABLE, entry.toValues(), "entry_id = ?", arrayOf(entry.entryId)
            )
        }
        if (count == 0) return@guarded notFound(entry.entryId)
        notifyObservers()
        AlbumResult.Success(AlbumMutation.Updated(entry))
    }

    override suspend fun delete(entryId: String): AlbumResult<AlbumMutation.Deleted> = guarded {
        val removed = synchronized(lock) {
            val writable = database.writableDatabase
            writable.beginTransaction()
            try {
                val entry = findEntry(entryId, writable) ?: return@synchronized null
                val deleted = writable.delete(ALBUM_TABLE, "entry_id = ?", arrayOf(entryId))
                check(deleted == 1) { "Expected one deleted album entry, got $deleted" }
                writable.setTransactionSuccessful()
                entry
            } finally {
                writable.endTransaction()
            }
        } ?: return@guarded notFound(entryId)
        notifyObservers()
        AlbumResult.Success(AlbumMutation.Deleted(entryId, removed.managedImage))
    }

    override fun close() {
        synchronized(lock) {
            observers.clear()
            database.close()
        }
    }

    internal fun entryIdsByImageId(): Map<String, String> = synchronized(lock) {
        database.readableDatabase.query(
            ALBUM_TABLE, arrayOf("entry_id", "image_id"), null, null, null, null, null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(0))
            }
        }
    }

    internal fun importAtomically(entries: List<AlbumEntry>) {
        if (entries.any { !managedImageExists(it.managedImage) }) {
            throw IOException("An imported managed image is missing")
        }
        synchronized(lock) {
            val writable = database.writableDatabase
            writable.beginTransaction()
            try {
                entries.forEach { writable.insertOrThrow(ALBUM_TABLE, null, it.toValues()) }
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
        }
        notifyObservers()
    }

    private fun findEntry(
        entryId: String,
        readableDatabase: android.database.sqlite.SQLiteDatabase = database.readableDatabase
    ): AlbumEntry? = readableDatabase.query(
        ALBUM_TABLE, null, "entry_id = ?", arrayOf(entryId), null, null, null, "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toAlbumEntry() else null }

    private fun matchingEntries(query: AlbumQuery): List<AlbumEntry> {
        val entries = database.readableDatabase.query(
            ALBUM_TABLE, null, null, null, null, null, null
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toAlbumEntry()) } }
        val needle = query.text.trim().lowercase(Locale.ROOT)
        val filtered = entries.filter { entry ->
            (!query.favoritesOnly || entry.isFavorite) &&
                (query.classId == null || entry.confirmedIdentity.classId == query.classId) &&
                (needle.isEmpty() || searchableText(entry).contains(needle))
        }
        return when (query.sort) {
            AlbumSort.NEWEST_FIRST -> filtered.sortedWith(
                compareByDescending<AlbumEntry> { it.albumDate }.thenBy { it.entryId }
            )
            AlbumSort.OLDEST_FIRST -> filtered.sortedWith(
                compareBy<AlbumEntry> { it.albumDate }.thenBy { it.entryId }
            )
            AlbumSort.IDENTITY_ASCENDING -> filtered.sortedWith(
                compareBy<AlbumEntry> { it.confirmedIdentity.displayName.lowercase(Locale.ROOT) }
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
    ).joinToString(" ").lowercase(Locale.ROOT)

    private fun managedImageExists(image: ManagedImageRef): Boolean {
        val root = privateFilesDirectory.canonicalFile
        val candidate = File(root, image.relativePath).canonicalFile
        return candidate.path.startsWith(root.path + File.separator) && candidate.isFile
    }

    private fun notifyObservers() {
        val snapshot = synchronized(lock) { observers.filter { it.active }.toList() }
        snapshot.forEach(::publish)
    }

    private fun publish(registration: ObserverRegistration) {
        if (!registration.active) return
        try {
            val entries = synchronized(lock) { matchingEntries(registration.query) }
            if (registration.active) registration.observer.onChanged(entries)
        } catch (error: Exception) {
            if (registration.active) registration.observer.onError(error.toAlbumError())
        }
    }

    private inline fun <T> guarded(block: () -> AlbumResult<T>): AlbumResult<T> = try {
        block()
    } catch (error: SQLiteException) {
        AlbumResult.Failure(error.toAlbumError())
    } catch (error: IllegalArgumentException) {
        AlbumResult.Failure(AlbumError(AlbumErrorCode.VALIDATION_FAILED, error.message ?: "Invalid album data", true))
    } catch (error: IllegalStateException) {
        AlbumResult.Failure(AlbumError(AlbumErrorCode.PERSISTENCE_FAILED, error.message ?: "Album mutation failed", true))
    } catch (error: IOException) {
        AlbumResult.Failure(AlbumError(AlbumErrorCode.STORAGE_UNAVAILABLE, error.message ?: "Private storage is unavailable", true))
    }

    private fun Throwable.toAlbumError() = AlbumError(
        AlbumErrorCode.PERSISTENCE_FAILED,
        message ?: "Album persistence failed",
        true
    )

    private fun <T> notFound(entryId: String): AlbumResult<T> = AlbumResult.Failure(
        AlbumError(AlbumErrorCode.NOT_FOUND, "Entry $entryId was not found", true)
    )

    private fun <T> duplicate(entryId: String): AlbumResult<T> = AlbumResult.Failure(
        AlbumError(AlbumErrorCode.DUPLICATE_ID, "Entry $entryId already exists", false)
    )

    private fun <T> imageMissing(image: ManagedImageRef): AlbumResult<T> = AlbumResult.Failure(
        AlbumError(
            AlbumErrorCode.IMAGE_MISSING,
            "Managed image ${image.imageId} is missing from private storage",
            true
        )
    )

    private data class ObserverRegistration(
        val query: AlbumQuery,
        val observer: AlbumObserver,
        var active: Boolean = true
    )
}
