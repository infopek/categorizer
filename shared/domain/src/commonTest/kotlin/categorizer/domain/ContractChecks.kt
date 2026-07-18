package categorizer.domain

import kotlin.test.Test

class ContractChecks {
    @Test
    fun domainInvariants() {
    val image = ManagedImageRef("image-1", "images/image-1.jpg")
    val identity = CategoryIdentity(
        "lepidoptera", "vanessa-cardui", "Vanessa cardui", "Painted Lady",
        alternateNames = listOf("Cosmopolitan")
    )
    val candidate = RecognitionCandidate(identity, 1, 0.72f, "lepidoptera-1")
    val result = RecognitionResult(
        "result-1", image, listOf(candidate), RecognitionStatus.CANDIDATES, 250, "lepidoptera-1"
    )
    check(result.candidates.first().identity.scientificName == "Vanessa cardui")

    val manual = identity.copy(
        classId = "user:painted-lady",
        scientificName = null,
        displayName = "Painted lady",
        alternateNames = emptyList(),
        source = IdentitySource.USER_CONFIRMED
    )
    val entry = AlbumEntry(
        "entry-1", image, manual, "2026-07-12", true, "Seen downtown", 1, 1, 1
    )
    check(entry.confirmedIdentity.source == IdentitySource.USER_CONFIRMED)

    expectFailure("duplicate IDs") {
        RecognitionResult("r2", image, listOf(candidate, candidate.copy(rank = 2)), RecognitionStatus.CANDIDATES, 1, "lepidoptera-1")
    }
    expectFailure("rank gap") {
        RecognitionResult("r3", image, listOf(candidate.copy(rank = 2)), RecognitionStatus.CANDIDATES, 1, "lepidoptera-1")
    }
    expectFailure("unsupported candidates") {
        RecognitionResult("r4", image, listOf(candidate), RecognitionStatus.UNSUPPORTED, 1, "lepidoptera-1")
    }
    expectFailure("absolute path") { ManagedImageRef("bad", "/tmp/photo.jpg") }
    expectFailure("path traversal") { ManagedImageRef("bad", "images/../photo.jpg") }
    expectFailure("invalid date") { entry.copy(albumDate = "12/07/2026") }
    expectFailure("missing scientific name") {
        CategoryIdentity("lepidoptera", "missing-name", null, "Missing", source = IdentitySource.MODEL_CATALOG)
    }
        println("RESULT OK domain_contract_checks=10")
    }
}

private fun expectFailure(label: String, block: () -> Unit) {
    try {
        block()
        error("Expected failure: $label")
    } catch (_: IllegalArgumentException) {
        // Expected invariant rejection.
    }
}
