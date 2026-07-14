package categorizer.domain.testing

import categorizer.domain.RecognitionEngine
import categorizer.domain.RecognitionError
import categorizer.domain.RecognitionErrorCode
import categorizer.domain.RecognitionInput
import categorizer.domain.RecognitionOutcome

class DeterministicRecognitionEngine(
    outcomesByImageId: Map<String, RecognitionOutcome> = ContractFixtures.recognitionOutcomesByImageId
) : RecognitionEngine {
    private val configuredOutcomes = outcomesByImageId.toMap()
    private val receivedInputs = mutableListOf<RecognitionInput>()

    val inputs: List<RecognitionInput> get() = receivedInputs.toList()

    override suspend fun recognize(input: RecognitionInput): RecognitionOutcome {
        receivedInputs += input
        return configuredOutcomes[input.sourceImage.imageId] ?: RecognitionOutcome.Failed(
            RecognitionError(
                RecognitionErrorCode.IMAGE_UNREADABLE,
                "No deterministic outcome for image ID ${input.sourceImage.imageId}",
                recoverable = false
            )
        )
    }
}
