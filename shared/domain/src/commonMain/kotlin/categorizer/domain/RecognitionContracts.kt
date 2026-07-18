package categorizer.domain

data class CategoryIdentity(
    val categoryId: String,
    val classId: String,
    val scientificName: String?,
    val displayName: String,
    val alternateNames: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val source: IdentitySource = IdentitySource.MODEL_CATALOG
) {
    init {
        require(CATEGORY_ID.matches(categoryId)) { "categoryId must be a stable lowercase identifier" }
        require(classId.isNotBlank()) { "classId must not be blank" }
        require(source != IdentitySource.MODEL_CATALOG || !scientificName.isNullOrBlank()) {
            "model-catalog identities require a scientificName"
        }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(alternateNames.none(String::isBlank)) { "alternateNames must not contain blanks" }
        require(alternateNames.distinct().size == alternateNames.size) { "alternateNames must be unique" }
        require(attributes.keys.none(String::isBlank)) { "attribute keys must not be blank" }
    }

    /** Backward-compatible constructor for version-1 car records and archives. */
    constructor(
        classId: String,
        make: String,
        model: String,
        generationLabel: String? = null,
        approximateYearRange: String? = null,
        displayName: String,
        source: IdentitySource = IdentitySource.MODEL_CATALOG
    ) : this(
        categoryId = "cars",
        classId = classId,
        scientificName = "$make $model",
        displayName = displayName,
        alternateNames = emptyList(),
        attributes = listOfNotNull(
            generationLabel?.let { "generation_label" to it },
            approximateYearRange?.let { "approximate_year_range" to it }
        ).toMap(),
        source = source
    )

    @Deprecated("Use scientificName and category-neutral attributes")
    val make: String get() = scientificName.orEmpty().substringBefore(' ')
    @Deprecated("Use scientificName and category-neutral attributes")
    val model: String get() = scientificName.orEmpty().substringAfter(' ', scientificName.orEmpty())
    @Deprecated("Use attributes")
    val generationLabel: String? get() = attributes["generation_label"] ?: attributes["subspecies_or_form"]
    @Deprecated("Use attributes")
    val approximateYearRange: String? get() = attributes["approximate_year_range"] ?: attributes["notes"]

    companion object { private val CATEGORY_ID = Regex("^[a-z0-9][a-z0-9-]*$") }
}

@Deprecated("Use CategoryIdentity")
typealias CarIdentity = CategoryIdentity

enum class IdentitySource { MODEL_CATALOG, USER_CONFIRMED }

data class RecognitionCandidate(
    val identity: CategoryIdentity,
    val rank: Int,
    val score: Float,
    val modelVersion: String
) {
    init {
        require(rank > 0) { "rank must start at 1" }
        require(!score.isNaN() && !score.isInfinite()) { "score must be finite" }
        require(modelVersion.isNotBlank()) { "modelVersion must not be blank" }
    }

    @Deprecated("Use identity")
    val carIdentity: CategoryIdentity get() = identity
}

enum class RecognitionStatus { CANDIDATES, UNCERTAIN, UNSUPPORTED }

data class RecognitionResult(
    val resultId: String,
    val sourceImage: ManagedImageRef,
    val candidates: List<RecognitionCandidate>,
    val status: RecognitionStatus,
    val inferenceDurationMs: Long,
    val modelVersion: String
) {
    init {
        require(resultId.isNotBlank()) { "resultId must not be blank" }
        require(inferenceDurationMs >= 0) { "inferenceDurationMs must not be negative" }
        require(modelVersion.isNotBlank()) { "modelVersion must not be blank" }
        require(candidates.map { it.rank } == (1..candidates.size).toList()) {
            "candidate ranks must be contiguous and ordered from 1"
        }
        require(candidates.map { it.identity.categoryId to it.identity.classId }.distinct().size == candidates.size) {
            "candidate category/class identities must be unique"
        }
        require(candidates.all { it.modelVersion == modelVersion }) {
            "candidate model versions must match the result"
        }
        require(status != RecognitionStatus.CANDIDATES || candidates.isNotEmpty()) {
            "CANDIDATES status requires at least one candidate"
        }
        require(status != RecognitionStatus.UNSUPPORTED || candidates.isEmpty()) {
            "UNSUPPORTED status must not contain candidates"
        }
    }
}

data class ManagedImageRef(val imageId: String, val relativePath: String) {
    init {
        require(imageId.isNotBlank()) { "imageId must not be blank" }
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        require(!relativePath.startsWith("/") && !relativePath.startsWith("\\")) {
            "relativePath must not be absolute"
        }
        require(relativePath.split('/', '\\').none { it == ".." }) {
            "relativePath must not traverse parent directories"
        }
    }
}

data class RecognitionInput(val sourceImage: ManagedImageRef)

sealed class RecognitionOutcome {
    data class Completed(val result: RecognitionResult) : RecognitionOutcome()
    data class Failed(val error: RecognitionError) : RecognitionOutcome()
    object Cancelled : RecognitionOutcome()
}

data class RecognitionError(
    val code: RecognitionErrorCode,
    val message: String,
    val recoverable: Boolean
) {
    init { require(message.isNotBlank()) { "message must not be blank" } }
}

enum class RecognitionErrorCode {
    MODEL_UNAVAILABLE, INVALID_MODEL_BUNDLE, IMAGE_UNREADABLE, OUT_OF_MEMORY, INFERENCE_FAILED
}

interface RecognitionEngine {
    suspend fun recognize(input: RecognitionInput): RecognitionOutcome
}
