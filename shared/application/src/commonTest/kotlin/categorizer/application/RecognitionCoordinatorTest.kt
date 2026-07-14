package categorizer.application

import categorizer.domain.ManagedImageRef
import categorizer.domain.RecognitionEngine
import categorizer.domain.RecognitionInput
import categorizer.domain.RecognitionOutcome
import categorizer.domain.testing.ContractFixtures
import categorizer.domain.testing.DeterministicRecognitionEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionCoordinatorTest {
    @Test
    fun everyContractOutcomeMapsToExplicitPresentationState() = runTest {
        assertState(ContractFixtures.candidateImage, RecognitionUiState.Candidates::class)
        assertState(ContractFixtures.uncertainImage, RecognitionUiState.Uncertain::class)
        assertState(ContractFixtures.unsupportedImage, RecognitionUiState.Unsupported::class)
        assertState(ContractFixtures.failedImage, RecognitionUiState.Error::class)
        assertState(ContractFixtures.cancelledImage, RecognitionUiState.Idle::class)
    }

    @Test
    fun candidatesPreserveOrderScoresModelAndTimingAndProduceNoPersistence() = runTest {
        val coordinator = coordinator(DeterministicRecognitionEngine())
        coordinator.submit(ContractFixtures.candidateImage)
        advanceUntilIdle()

        val state = assertIs<RecognitionUiState.Candidates>(coordinator.state.value)
        assertEquals(listOf(1, 2), state.candidates.map { it.rank })
        assertEquals(listOf(0.5f, 0.5f), state.candidates.map { it.score })
        assertEquals(ContractFixtures.MODEL_VERSION, state.modelVersion)
        assertEquals(125, state.inferenceDurationMs)
        val draft = coordinator.confirmCandidate(ContractFixtures.bmwIdentity.classId)
        assertEquals(ContractFixtures.bmwIdentity, draft?.confirmedIdentity)
        assertEquals(ContractFixtures.candidateImage, draft?.sourceImage)
        assertNull(coordinator.confirmCandidate("missing-class"))
    }

    @Test
    fun manualCorrectionProducesUserConfirmedDraftOnlyAfterCompletion() = runTest {
        val coordinator = coordinator(DeterministicRecognitionEngine())
        assertNull(coordinator.manualCorrection(ContractFixtures.manualIdentity))
        coordinator.submit(ContractFixtures.uncertainImage)
        advanceUntilIdle()

        val draft = coordinator.manualCorrection(ContractFixtures.manualIdentity)
        assertEquals(ContractFixtures.manualIdentity, draft?.confirmedIdentity)
        assertEquals("result-uncertain", draft?.recognitionResultId)
    }

    @Test
    fun duplicateRunningSubmissionIsIgnoredAndRetryStartsOneNewRequest() = runTest {
        val gate = CompletableDeferred<RecognitionOutcome>()
        val engine = RecordingEngine { gate.await() }
        val coordinator = coordinator(engine)
        coordinator.submit(ContractFixtures.candidateImage)
        coordinator.submit(ContractFixtures.candidateImage)
        testScheduler.runCurrent()
        assertEquals(1, engine.inputs.size)

        gate.complete(ContractFixtures.candidatesOutcome)
        advanceUntilIdle()
        coordinator.retry()
        advanceUntilIdle()
        assertEquals(2, engine.inputs.size)
    }

    @Test
    fun cancellationAndDisposalPreventCompletionFromChangingState() = runTest {
        val gate = CompletableDeferred<RecognitionOutcome>()
        val engine = RecordingEngine { withContext(NonCancellable) { gate.await() } }
        val coordinator = coordinator(engine)
        coordinator.submit(ContractFixtures.candidateImage)
        testScheduler.runCurrent()
        coordinator.cancel()
        gate.complete(ContractFixtures.candidatesOutcome)
        advanceUntilIdle()
        assertSame(RecognitionUiState.Idle, coordinator.state.value)

        coordinator.submit(ContractFixtures.candidateImage)
        testScheduler.runCurrent()
        coordinator.dispose()
        advanceUntilIdle()
        assertSame(RecognitionUiState.Idle, coordinator.state.value)
    }

    @Test
    fun staleOutOfOrderCompletionCannotReplaceNewerResult() = runTest {
        val first = CompletableDeferred<RecognitionOutcome>()
        val second = CompletableDeferred<RecognitionOutcome>()
        val engine = RecordingEngine { input ->
            withContext(NonCancellable) {
                if (input.sourceImage == ContractFixtures.candidateImage) first.await() else second.await()
            }
        }
        val coordinator = coordinator(engine)
        coordinator.submit(ContractFixtures.candidateImage)
        testScheduler.runCurrent()
        coordinator.submit(ContractFixtures.uncertainImage)
        testScheduler.runCurrent()

        second.complete(ContractFixtures.uncertainOutcome)
        testScheduler.runCurrent()
        assertIs<RecognitionUiState.Uncertain>(coordinator.state.value)
        first.complete(ContractFixtures.candidatesOutcome)
        advanceUntilIdle()
        assertIs<RecognitionUiState.Uncertain>(coordinator.state.value)
    }

    private suspend fun TestScope.assertState(
        image: ManagedImageRef,
        expected: kotlin.reflect.KClass<out RecognitionUiState>
    ) {
        val coordinator = coordinator(DeterministicRecognitionEngine())
        coordinator.submit(image)
        advanceUntilIdle()
        assertEquals(expected, coordinator.state.value::class)
    }

    private fun TestScope.coordinator(engine: RecognitionEngine) = RecognitionCoordinator(
        engine = engine,
        scope = this,
        inferenceDispatcher = StandardTestDispatcher(testScheduler)
    )

    private class RecordingEngine(
        private val response: suspend (RecognitionInput) -> RecognitionOutcome
    ) : RecognitionEngine {
        val inputs = mutableListOf<RecognitionInput>()
        override suspend fun recognize(input: RecognitionInput): RecognitionOutcome {
            inputs += input
            return response(input)
        }
    }
}
