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
    fun manualCorrectionRequiresMakeAndModelAndPreservesOptionalFields() {
        assertIs<ManualIdentityValidation.Invalid>(ManualIdentityInput().validate())
        val valid = assertIs<ManualIdentityValidation.Valid>(
            ManualIdentityInput(" Mercedes-Benz ", " C-Class ", " W205 ", "2014-2021").validate()
        )
        assertEquals("Mercedes-Benz C-Class (W205)", valid.identity.displayName)
        assertEquals("user:mercedes-benz-c-class-w205", valid.identity.classId)
        assertEquals(IdentitySource.USER_CONFIRMED, valid.identity.source)
        assertEquals("2014-2021", valid.identity.approximateYearRange)
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
