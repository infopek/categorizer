package categorizer.domain

data class CarIdentity(
    val classId: String,
    val make: String,
    val model: String,
    val generationLabel: String? = null,
    val approximateYearRange: String? = null,
    val displayName: String,
    val source: IdentitySource = IdentitySource.MODEL_CATALOG
) {
    init {
        require(classId.isNotBlank()) { "classId must not be blank" }
        require(make.isNotBlank()) { "make must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
    }
}

enum class IdentitySource { MODEL_CATALOG, USER_CONFIRMED }

data class RecognitionCandidate(
    val carIdentity: CarIdentity,
    val rank: Int,
    val score: Float,
    val modelVersion: String
) {
    init {
        require(rank > 0) { "rank must start at 1" }
        require(!score.isNaN() && !score.isInfinite()) { "score must be finite" }
        require(modelVersion.isNotBlank()) { "modelVersion must not be blank" }
    }
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
        require(candidates.map { it.carIdentity.classId }.distinct().size == candidates.size) {
            "candidate class IDs must be unique"
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
