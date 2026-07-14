package categorizer.domain

import categorizer.domain.testing.ContractFixtures
import categorizer.domain.testing.DeterministicRecognitionEngine
import categorizer.domain.testing.InMemoryAlbumRepository
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

fun main() {
    val engine = DeterministicRecognitionEngine()
    val recognitionOutcomes = listOf(
        ContractFixtures.candidateImage,
        ContractFixtures.uncertainImage,
        ContractFixtures.unsupportedImage,
        ContractFixtures.failedImage,
        ContractFixtures.cancelledImage
    ).map { image -> runSuspend { engine.recognize(RecognitionInput(image)) } }

    check((recognitionOutcomes[0] as RecognitionOutcome.Completed).result.status == RecognitionStatus.CANDIDATES)
    check((recognitionOutcomes[1] as RecognitionOutcome.Completed).result.status == RecognitionStatus.UNCERTAIN)
    check((recognitionOutcomes[2] as RecognitionOutcome.Completed).result.status == RecognitionStatus.UNSUPPORTED)
    check(recognitionOutcomes[3] is RecognitionOutcome.Failed)
    check(recognitionOutcomes[4] is RecognitionOutcome.Cancelled)
    val equalScoreCandidates = (recognitionOutcomes[0] as RecognitionOutcome.Completed).result.candidates
    check(equalScoreCandidates.map { it.rank } == listOf(1, 2))
    check(engine.inputs.size == 5)

    val repository = InMemoryAlbumRepository(ContractFixtures.albumEntries)
    val favoriteEvents = mutableListOf<List<AlbumEntry>>()
    val subscription = repository.observe(
        AlbumQuery(favoritesOnly = true),
        object : AlbumObserver {
            override fun onChanged(entries: List<AlbumEntry>) { favoriteEvents += entries }
            override fun onError(error: AlbumError) { error("Unexpected observer error: $error") }
        }
    )
    check(favoriteEvents.single().map { it.entryId } == listOf("entry-porsche"))

    val manualSearch = successValue<List<AlbumEntry>>(
        runSuspend { repository.query(AlbumQuery(text = "classic coupe")) }
    )
    check(manualSearch.single().confirmedIdentity.source == IdentitySource.USER_CONFIRMED)

    val created = ContractFixtures.manualEntry.copy(
        entryId = "entry-created",
        managedImage = ManagedImageRef("image-created", "images/image-created.webp"),
        albumDate = "2026-07-12",
        isFavorite = true,
        createdAtEpochMs = 1_720_742_400_000,
        updatedAtEpochMs = 1_720_742_400_000
    )
    check(runSuspend { repository.create(created) } is AlbumResult.Success)
    check(favoriteEvents.last().map { it.entryId } == listOf("entry-created", "entry-porsche"))
    check(runSuspend { repository.create(created) } is AlbumResult.Failure)

    val updated = created.copy(notes = "updated", updatedAtEpochMs = created.updatedAtEpochMs + 1)
    check(runSuspend { repository.update(updated) } is AlbumResult.Success)
    check(successValue<AlbumEntry>(runSuspend { repository.get(created.entryId) }).notes == "updated")

    val deleted = successValue<AlbumMutation.Deleted>(runSuspend { repository.delete(created.entryId) })
    check(deleted.removedImage == created.managedImage)
    check(runSuspend { repository.get(created.entryId) } is AlbumResult.Failure)
    subscription.cancel()
    val eventCountAfterCancel = favoriteEvents.size
    check(runSuspend { repository.update(ContractFixtures.favoriteEntry.copy(notes = "no event")) } is AlbumResult.Success)
    check(favoriteEvents.size == eventCountAfterCancel)

    println("RESULT OK fake_adapter_checks=18 recognition_states=5 album_operations=7")
}

private fun <T> successValue(result: AlbumResult<T>): T = when (result) {
    is AlbumResult.Success -> result.value
    is AlbumResult.Failure -> error("Expected success, got ${result.error}")
}

private fun <T> runSuspend(block: suspend () -> T): T {
    val incomplete = Any()
    var value: Any? = incomplete
    var failure: Throwable? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            result.fold(
                onSuccess = { value = it },
                onFailure = { failure = it }
            )
        }
    })
    failure?.let { throw it }
    check(value !== incomplete) { "Fixture coroutine did not complete synchronously" }
    @Suppress("UNCHECKED_CAST")
    return value as T
}
