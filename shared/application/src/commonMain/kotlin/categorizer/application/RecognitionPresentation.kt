package categorizer.application

import categorizer.domain.CategoryIdentity
import categorizer.domain.ManagedImageRef
import categorizer.domain.RecognitionCandidate
import categorizer.domain.RecognitionError

sealed interface RecognitionUiState {
    data object Idle : RecognitionUiState

    data class Running(val sourceImage: ManagedImageRef) : RecognitionUiState

    data class Candidates(
        val resultId: String,
        val sourceImage: ManagedImageRef,
        val candidates: List<RecognitionCandidate>,
        val inferenceDurationMs: Long,
        val modelVersion: String
    ) : RecognitionUiState

    data class Uncertain(
        val resultId: String,
        val sourceImage: ManagedImageRef,
        val candidates: List<RecognitionCandidate>,
        val inferenceDurationMs: Long,
        val modelVersion: String
    ) : RecognitionUiState

    data class Unsupported(
        val resultId: String,
        val sourceImage: ManagedImageRef,
        val inferenceDurationMs: Long,
        val modelVersion: String
    ) : RecognitionUiState

    data class Error(
        val sourceImage: ManagedImageRef,
        val error: RecognitionError
    ) : RecognitionUiState
}

data class RecognitionSaveDraft(
    val sourceImage: ManagedImageRef,
    val confirmedIdentity: CategoryIdentity,
    val recognitionResultId: String,
    val modelVersion: String,
    val inferenceDurationMs: Long
)
