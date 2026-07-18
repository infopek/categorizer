package categorizer.application

import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumRepository
import categorizer.domain.AlbumResult
import categorizer.domain.CategoryIdentity
import categorizer.domain.IdentitySource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ManualIdentityInput(
    val displayName: String = "",
    val scientificName: String = "",
    val alternateNames: String = "",
    val identityNotes: String = "",
    val categoryId: String = "lepidoptera"
)

sealed class ManualIdentityValidation {
    data class Valid(val identity: CategoryIdentity) : ManualIdentityValidation()
    data class Invalid(val displayNameError: String? = null) :
        ManualIdentityValidation()
}

fun ManualIdentityInput.validate(): ManualIdentityValidation {
    val cleanDisplayName = displayName.trim()
    if (cleanDisplayName.isEmpty()) return ManualIdentityValidation.Invalid("Name is required")
    val cleanScientificName = scientificName.trim().ifEmpty { null }
    val cleanAlternateNames = alternateNames.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    val cleanNotes = identityNotes.trim().ifEmpty { null }
    val slug = (cleanScientificName ?: cleanDisplayName)
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "manual-identity" }
    return ManualIdentityValidation.Valid(
        CategoryIdentity(
            categoryId = categoryId,
            classId = "user:$slug",
            scientificName = cleanScientificName,
            displayName = cleanDisplayName,
            alternateNames = cleanAlternateNames,
            attributes = cleanNotes?.let { mapOf("notes" to it) }.orEmpty(),
            source = IdentitySource.USER_CONFIRMED
        )
    )
}

sealed class RecognitionSaveResult {
    data class Saved(val entry: AlbumEntry) : RecognitionSaveResult()
    data object DuplicateIgnored : RecognitionSaveResult()
    data class Failed(val message: String, val recoverable: Boolean) : RecognitionSaveResult()
}

class RecognitionEntrySaver(private val repository: AlbumRepository) {
    private val mutex = Mutex()
    private val savedResultIds = mutableSetOf<String>()

    suspend fun save(
        draft: RecognitionSaveDraft,
        entryId: String,
        albumDate: String,
        nowEpochMs: Long
    ): RecognitionSaveResult = mutex.withLock {
        if (draft.recognitionResultId in savedResultIds) return@withLock RecognitionSaveResult.DuplicateIgnored
        val entry = AlbumEntry(
            entryId = entryId,
            managedImage = draft.sourceImage,
            confirmedIdentity = draft.confirmedIdentity,
            albumDate = albumDate,
            isFavorite = false,
            notes = "",
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            schemaVersion = 1
        )
        when (val result = repository.create(entry)) {
            is AlbumResult.Success -> {
                savedResultIds += draft.recognitionResultId
                RecognitionSaveResult.Saved(result.value.entry)
            }
            is AlbumResult.Failure -> RecognitionSaveResult.Failed(
                result.error.message, result.error.recoverable
            )
        }
    }
}
