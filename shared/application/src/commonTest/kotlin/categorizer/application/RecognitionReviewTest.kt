package categorizer.application

import categorizer.domain.IdentitySource
import categorizer.domain.testing.ContractFixtures
import categorizer.domain.testing.InMemoryAlbumRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecognitionReviewTest {
    @Test
    fun manualCorrectionRequiresDisplayNameAndPreservesNeutralFields() {
        assertIs<ManualIdentityValidation.Invalid>(ManualIdentityInput().validate())
        val valid = assertIs<ManualIdentityValidation.Valid>(
            ManualIdentityInput(" Eros blue ", " Polyommatus eros ", " Eros blue butterfly, Eros blue butterfly ", "male form").validate()
        )
        assertEquals("Eros blue", valid.identity.displayName)
        assertEquals("Polyommatus eros", valid.identity.scientificName)
        assertEquals(listOf("Eros blue butterfly"), valid.identity.alternateNames)
        assertEquals("user:polyommatus-eros", valid.identity.classId)
        assertEquals(IdentitySource.USER_CONFIRMED, valid.identity.source)
        assertEquals("male form", valid.identity.attributes["notes"])
    }

    @Test
    fun simultaneousConfirmationsCreateExactlyOneEntry() = runTest {
        val repository = InMemoryAlbumRepository()
        val saver = RecognitionEntrySaver(repository)
        val draft = RecognitionSaveDraft(
            ContractFixtures.candidateImage,
            ContractFixtures.porscheIdentity,
            "result-once",
            ContractFixtures.MODEL_VERSION,
            42
        )

        val results = listOf(
            async { saver.save(draft, "entry-first", "2026-07-15", 1) },
            async { saver.save(draft, "entry-second", "2026-07-15", 1) }
        ).awaitAll()

        assertEquals(1, results.count { it is RecognitionSaveResult.Saved })
        assertEquals(1, results.count { it is RecognitionSaveResult.DuplicateIgnored })
        assertEquals(1, assertIs<categorizer.domain.AlbumResult.Success<List<categorizer.domain.AlbumEntry>>>(
            repository.query(categorizer.domain.AlbumQuery())
        ).value.size)
    }
}
