package categorizer.application

import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumRepository
import categorizer.domain.AlbumResult
import categorizer.domain.CategoryIdentity
import categorizer.domain.IdentitySource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ManualIdentityInput(
    val make: String = "",
    val model: String = "",
    val generation: String = "",
    val approximateYearRange: String = "",
    val categoryId: String = "lepidoptera"
)

sealed class ManualIdentityValidation {
    data class Valid(val identity: CategoryIdentity) : ManualIdentityValidation()
    data class Invalid(val makeError: String? = null, val modelError: String? = null) :
        ManualIdentityValidation()
}

fun ManualIdentityInput.validate(): ManualIdentityValidation {
    val cleanMake = make.trim()
    val cleanModel = model.trim()
    val makeError = if (cleanMake.isEmpty()) "Make is required" else null
    val modelError = if (cleanModel.isEmpty()) "Model is required" else null
    if (makeError != null || modelError != null) {
        return ManualIdentityValidation.Invalid(makeError, modelError)
    }
    val cleanGeneration = generation.trim().ifEmpty { null }
    val cleanYears = approximateYearRange.trim().ifEmpty { null }
    val displayName = buildString {
        append(cleanMake)
        append(' ')
        append(cleanModel)
        cleanGeneration?.let { append(" ($it)") }
    }
    val slug = listOfNotNull(cleanMake, cleanModel, cleanGeneration)
        .joinToString("-")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "manual-car" }
    return ManualIdentityValidation.Valid(
        CategoryIdentity(
            categoryId = categoryId,
            classId = "user:$slug",
            scientificName = "$cleanMake $cleanModel",
            displayName = displayName,
            attributes = listOfNotNull(
                cleanGeneration?.let { "subspecies_or_form" to it },
                cleanYears?.let { "notes" to it }
            ).toMap(),
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
