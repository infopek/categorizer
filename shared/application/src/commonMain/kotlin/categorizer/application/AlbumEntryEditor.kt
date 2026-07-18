package categorizer.application

import categorizer.domain.AlbumEntry
import categorizer.domain.AlbumError
import categorizer.domain.AlbumMutation
import categorizer.domain.AlbumRepository
import categorizer.domain.AlbumResult
import categorizer.domain.CategoryIdentity
import categorizer.domain.IdentitySource

data class AlbumEntryEditInput(
    val displayName: String,
    val scientificName: String = "",
    val alternateNames: String = "",
    val identityNotes: String = "",
    val notes: String = "",
    val isFavorite: Boolean = false,
    val categoryId: String = "lepidoptera",
    val retainedAttributes: Map<String, String> = emptyMap()
) {
    companion object {
        fun from(entry: AlbumEntry) = AlbumEntryEditInput(
            displayName = entry.confirmedIdentity.displayName,
            scientificName = entry.confirmedIdentity.scientificName.orEmpty(),
            alternateNames = entry.confirmedIdentity.alternateNames.joinToString(", "),
            identityNotes = entry.confirmedIdentity.attributes["notes"].orEmpty(),
            notes = entry.notes,
            isFavorite = entry.isFavorite,
            categoryId = entry.confirmedIdentity.categoryId,
            retainedAttributes = entry.confirmedIdentity.attributes - "notes"
        )
    }
}

sealed class AlbumEntryEditValidation {
    data class Valid(val entry: AlbumEntry) : AlbumEntryEditValidation()
    data class Invalid(val displayNameError: String? = null) : AlbumEntryEditValidation()
}

fun AlbumEntryEditInput.validate(original: AlbumEntry, nowEpochMs: Long): AlbumEntryEditValidation {
    val cleanDisplayName = displayName.trim()
    if (cleanDisplayName.isEmpty()) return AlbumEntryEditValidation.Invalid("Name is required")
    val cleanScientificName = scientificName.trim().ifEmpty { null }
    val cleanAlternateNames = alternateNames.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    val cleanIdentityNotes = identityNotes.trim().ifEmpty { null }
    val identity = CategoryIdentity(
        categoryId = categoryId,
        classId = userClassId(cleanScientificName ?: cleanDisplayName),
        scientificName = cleanScientificName,
        displayName = cleanDisplayName,
        alternateNames = cleanAlternateNames,
        attributes = retainedAttributes + listOfNotNull(cleanIdentityNotes?.let { "notes" to it }).toMap(),
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

private fun userClassId(name: String): String =
    "user:" + name
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
