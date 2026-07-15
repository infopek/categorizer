package categorizer.application

import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumError
import categorizer.domain.AlbumMutation
import categorizer.domain.AlbumRepository
import categorizer.domain.AlbumResult
import categorizer.domain.CarIdentity
import categorizer.domain.IdentitySource

data class AlbumEntryEditInput(
    val make: String,
    val model: String,
    val generation: String = "",
    val approximateYearRange: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false
) {
    companion object {
        fun from(entry: AlbumEntry) = AlbumEntryEditInput(
            make = entry.confirmedIdentity.make,
            model = entry.confirmedIdentity.model,
            generation = entry.confirmedIdentity.generationLabel.orEmpty(),
            approximateYearRange = entry.confirmedIdentity.approximateYearRange.orEmpty(),
            notes = entry.notes,
            isFavorite = entry.isFavorite
        )
    }
}

sealed class AlbumEntryEditValidation {
    data class Valid(val entry: AlbumEntry) : AlbumEntryEditValidation()
    data class Invalid(val makeError: String? = null, val modelError: String? = null) : AlbumEntryEditValidation()
}

fun AlbumEntryEditInput.validate(original: AlbumEntry, nowEpochMs: Long): AlbumEntryEditValidation {
    val cleanMake = make.trim()
    val cleanModel = model.trim()
    if (cleanMake.isEmpty() || cleanModel.isEmpty()) {
        return AlbumEntryEditValidation.Invalid(
            makeError = if (cleanMake.isEmpty()) "Make is required" else null,
            modelError = if (cleanModel.isEmpty()) "Model is required" else null
        )
    }
    val cleanGeneration = generation.trim().ifEmpty { null }
    val cleanYears = approximateYearRange.trim().ifEmpty { null }
    val displayName = listOfNotNull(cleanMake, cleanModel, cleanGeneration?.let { "($it)" }).joinToString(" ")
    val identity = CarIdentity(
        classId = userClassId(cleanMake, cleanModel, cleanGeneration),
        make = cleanMake,
        model = cleanModel,
        generationLabel = cleanGeneration,
        approximateYearRange = cleanYears,
        displayName = displayName,
        source = IdentitySource.USER_CONFIRMED
    )
    return AlbumEntryEditValidation.Valid(
        original.copy(
            confirmedIdentity = identity,
            notes = notes,
            isFavorite = isFavorite,
            updatedAtEpochMs = maxOf(nowEpochMs, original.updatedAtEpochMs, original.createdAtEpochMs)
        )
    )
}

sealed class AlbumEntryEditorResult<out T> {
    data class Success<T>(val value: T) : AlbumEntryEditorResult<T>()
    data class Failed(val error: AlbumError) : AlbumEntryEditorResult<Nothing>()
}

class AlbumEntryEditor(private val repository: AlbumRepository) {
    suspend fun load(entryId: String): AlbumEntryEditorResult<AlbumEntry> = repository.get(entryId).editorResult()

    suspend fun save(entry: AlbumEntry): AlbumEntryEditorResult<AlbumEntry> = when (val result = repository.update(entry)) {
        is AlbumResult.Success -> AlbumEntryEditorResult.Success(result.value.entry)
        is AlbumResult.Failure -> AlbumEntryEditorResult.Failed(result.error)
    }

    suspend fun delete(entryId: String): AlbumEntryEditorResult<AlbumMutation.Deleted> =
        repository.delete(entryId).editorResult()
}

private fun <T> AlbumResult<T>.editorResult(): AlbumEntryEditorResult<T> = when (this) {
    is AlbumResult.Success -> AlbumEntryEditorResult.Success(value)
    is AlbumResult.Failure -> AlbumEntryEditorResult.Failed(error)
}

private fun userClassId(make: String, model: String, generation: String?): String =
    "user:" + listOfNotNull(make, model, generation)
        .joinToString("-")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
