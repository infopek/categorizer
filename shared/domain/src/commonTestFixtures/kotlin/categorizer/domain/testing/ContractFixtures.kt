package categorizer.domain.testing

import categorizer.domain.AlbumEntry
import categorizer.domain.CategoryIdentity
import categorizer.domain.IdentitySource
import categorizer.domain.ManagedImageRef
import categorizer.domain.RecognitionCandidate
import categorizer.domain.RecognitionError
import categorizer.domain.RecognitionErrorCode
import categorizer.domain.RecognitionOutcome
import categorizer.domain.RecognitionResult
import categorizer.domain.RecognitionStatus

object ContractFixtures {
    const val MODEL_VERSION = "lepidoptera-fixture-1"

    val porscheIdentity = CategoryIdentity(
        categoryId = "lepidoptera", classId = "vanessa-cardui",
        scientificName = "Vanessa cardui", displayName = "Painted Lady"
    )
    val bmwIdentity = CategoryIdentity(
        categoryId = "lepidoptera", classId = "acherontia-atropos",
        scientificName = "Acherontia atropos", displayName = "Death's Head Hawkmoth"
    )
    val manualIdentity = CategoryIdentity(
        categoryId = "lepidoptera", classId = "user:unknown-moth", scientificName = null,
        displayName = "Unknown moth", source = IdentitySource.USER_CONFIRMED
    )

    val candidateImage = ManagedImageRef("image-candidates", "images/image-candidates.jpg")
    val uncertainImage = ManagedImageRef("image-uncertain", "images/image-uncertain.jpg")
    val unsupportedImage = ManagedImageRef("image-unsupported", "images/image-unsupported.jpg")
    val failedImage = ManagedImageRef("image-failed", "images/image-failed.jpg")
    val cancelledImage = ManagedImageRef("image-cancelled", "images/image-cancelled.jpg")

    val candidatesOutcome = completed(
        "result-candidates", candidateImage, RecognitionStatus.CANDIDATES,
        listOf(
            RecognitionCandidate(porscheIdentity, 1, 0.5f, MODEL_VERSION),
            RecognitionCandidate(bmwIdentity, 2, 0.5f, MODEL_VERSION)
        ), 125
    )
    val uncertainOutcome = completed(
        "result-uncertain", uncertainImage, RecognitionStatus.UNCERTAIN,
        listOf(
            RecognitionCandidate(porscheIdentity, 1, 0.31f, MODEL_VERSION),
            RecognitionCandidate(bmwIdentity, 2, 0.29f, MODEL_VERSION)
        ), 127
    )
    val unsupportedOutcome = completed(
        "result-unsupported", unsupportedImage, RecognitionStatus.UNSUPPORTED, emptyList(), 12
    )
    val failedOutcome = RecognitionOutcome.Failed(
        RecognitionError(
            RecognitionErrorCode.IMAGE_UNREADABLE,
            "Fixture image cannot be decoded",
            recoverable = true
        )
    )
    val cancelledOutcome = RecognitionOutcome.Cancelled

    val recognitionOutcomesByImageId = linkedMapOf(
        candidateImage.imageId to candidatesOutcome,
        uncertainImage.imageId to uncertainOutcome,
        unsupportedImage.imageId to unsupportedOutcome,
        failedImage.imageId to failedOutcome,
        cancelledImage.imageId to cancelledOutcome
    )

    val favoriteEntry = AlbumEntry(
        "entry-porsche", candidateImage, porscheIdentity, "2026-07-10", true,
        "Equal-score tie keeps class-map order.", 1_720_569_600_000, 1_720_569_600_000, 1
    )
    val manualEntry = AlbumEntry(
        "entry-manual", ManagedImageRef("image-manual", "images/image-manual.png"),
        manualIdentity, "2026-07-11", false, "Manual label outside the installed model catalog.",
        1_720_656_000_000, 1_720_656_000_000, 1
    )
    val albumEntries = listOf(favoriteEntry, manualEntry)

    private fun completed(
        resultId: String,
        image: ManagedImageRef,
        status: RecognitionStatus,
        candidates: List<RecognitionCandidate>,
        durationMs: Long
    ) = RecognitionOutcome.Completed(
        RecognitionResult(resultId, image, candidates, status, durationMs, MODEL_VERSION)
    )
}
