package categorizer.application

import categorizer.domain.CarIdentity
import categorizer.domain.IdentitySource
import categorizer.domain.ManagedImageRef
import categorizer.domain.RecognitionEngine
import categorizer.domain.RecognitionInput
import categorizer.domain.RecognitionOutcome
import categorizer.domain.RecognitionResult
import categorizer.domain.RecognitionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecognitionCoordinator(
    private val engine: RecognitionEngine,
    private val scope: CoroutineScope,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val mutableState = MutableStateFlow<RecognitionUiState>(RecognitionUiState.Idle)
    val state: StateFlow<RecognitionUiState> = mutableState.asStateFlow()

    private var activeJob: Job? = null
    private var requestToken = 0L
    private var lastSource: ManagedImageRef? = null
    private var disposed = false

    fun submit(sourceImage: ManagedImageRef) {
        if (disposed) return
        val current = mutableState.value
        if (current is RecognitionUiState.Running && current.sourceImage == sourceImage) return
        start(sourceImage)
    }

    fun retry() {
        if (disposed) return
        lastSource?.let(::start)
    }

    fun cancel() {
        requestToken += 1
        activeJob?.cancel()
        activeJob = null
        if (!disposed) mutableState.value = RecognitionUiState.Idle
    }

    fun dispose() {
        disposed = true
        cancel()
        mutableState.value = RecognitionUiState.Idle
    }

    fun confirmCandidate(classId: String): RecognitionSaveDraft? {
        val completed = mutableState.value.completedDetails() ?: return null
        val identity = completed.result.candidates
            .firstOrNull { it.carIdentity.classId == classId }
            ?.carIdentity ?: return null
        return completed.result.toSaveDraft(identity)
    }

    fun manualCorrection(identity: CarIdentity): RecognitionSaveDraft? {
        require(identity.source == IdentitySource.USER_CONFIRMED) {
            "Manual corrections must use USER_CONFIRMED identity source"
        }
        val completed = mutableState.value.completedDetails() ?: return null
        return completed.result.toSaveDraft(identity)
    }

    private fun start(sourceImage: ManagedImageRef) {
        activeJob?.cancel()
        requestToken += 1
        val token = requestToken
        lastSource = sourceImage
        mutableState.value = RecognitionUiState.Running(sourceImage)
        activeJob = scope.launch {
            val outcome = withContext(inferenceDispatcher) {
                engine.recognize(RecognitionInput(sourceImage))
            }
            if (!disposed && token == requestToken) {
                mutableState.value = outcome.toUiState(sourceImage)
                activeJob = null
            }
        }
    }

    private fun RecognitionOutcome.toUiState(sourceImage: ManagedImageRef): RecognitionUiState = when (this) {
        is RecognitionOutcome.Completed -> result.toUiState()
        is RecognitionOutcome.Failed -> RecognitionUiState.Error(sourceImage, error)
        RecognitionOutcome.Cancelled -> RecognitionUiState.Idle
    }

    private fun RecognitionResult.toUiState(): RecognitionUiState = when (status) {
        RecognitionStatus.CANDIDATES -> RecognitionUiState.Candidates(
            resultId, sourceImage, candidates, inferenceDurationMs, modelVersion
        )
        RecognitionStatus.UNCERTAIN -> RecognitionUiState.Uncertain(
            resultId, sourceImage, candidates, inferenceDurationMs, modelVersion
        )
        RecognitionStatus.UNSUPPORTED -> RecognitionUiState.Unsupported(
            resultId, sourceImage, inferenceDurationMs, modelVersion
        )
    }

    private fun RecognitionUiState.completedDetails(): CompletedDetails? = when (this) {
        is RecognitionUiState.Candidates -> CompletedDetails(
            RecognitionResult(resultId, sourceImage, candidates, RecognitionStatus.CANDIDATES, inferenceDurationMs, modelVersion)
        )
        is RecognitionUiState.Uncertain -> CompletedDetails(
            RecognitionResult(resultId, sourceImage, candidates, RecognitionStatus.UNCERTAIN, inferenceDurationMs, modelVersion)
        )
        is RecognitionUiState.Unsupported -> CompletedDetails(
            RecognitionResult(
                resultId, sourceImage, emptyList(), RecognitionStatus.UNSUPPORTED,
                inferenceDurationMs, modelVersion
            )
        )
        is RecognitionUiState.Error,
        RecognitionUiState.Idle,
        is RecognitionUiState.Running -> null
    }

    private fun RecognitionResult.toSaveDraft(identity: CarIdentity) = RecognitionSaveDraft(
        sourceImage, identity, resultId, modelVersion, inferenceDurationMs
    )

    private data class CompletedDetails(val result: RecognitionResult)
}
