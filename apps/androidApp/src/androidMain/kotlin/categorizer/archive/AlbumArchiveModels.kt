package categorizer.archive

import categorizer.domain.AlbumEntry
import java.io.File

enum class ArchiveConflictDecision { ABORT_ARCHIVE, KEEP_EXISTING }
enum class ArchiveErrorCode { UNREADABLE_ARCHIVE, INVALID_MANIFEST, UNSUPPORTED_VERSION, UNSAFE_PATH, RESOURCE_LIMIT, CHECKSUM_MISMATCH, MISSING_MEMBER, EXTRA_MEMBER, DUPLICATE_VALUE, CONFLICT, STALE_PLAN, STORAGE_UNAVAILABLE, COMMIT_FAILED }
data class ArchiveError(val code: ArchiveErrorCode, val message: String)
sealed class ArchiveResult<out T> {
    data class Success<T>(val value: T) : ArchiveResult<T>()
    data class Failure(val errors: List<ArchiveError>) : ArchiveResult<Nothing>()
}
data class ArchiveConflict(val entryId: String, val imageId: String, val entryExists: Boolean, val imageExists: Boolean)
data class ArchivePreview(val archiveId: String, val totalEntries: Int, val importCount: Int, val requiredBytes: Long, val conflicts: List<ArchiveConflict>)
data class ValidatedImportPlan internal constructor(
    val archiveHash: String,
    val localRevision: String,
    val preview: ArchivePreview,
    internal val archive: File,
    internal val entries: List<AlbumEntry>,
    internal val images: List<ArchiveImage>
)
internal data class ArchiveImage(val imageId: String, val archivePath: String, val mediaType: String, val sizeBytes: Long, val sha256: String)
data class ArchiveExportInfo(val archiveId: String, val entryCount: Int, val imageCount: Int, val archiveSha256: String, val output: File)
data class ArchiveImportInfo(val importedEntryIds: List<String>, val importedBytes: Long)
internal enum class ImportFailurePoint { NONE, AFTER_FIRST_FILE, BEFORE_METADATA_COMMIT }
