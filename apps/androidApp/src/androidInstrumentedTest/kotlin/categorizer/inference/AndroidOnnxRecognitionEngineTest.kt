package categorizer.inference

import androidx.test.platform.app.InstrumentationRegistry
import categorizer.domain.ManagedImageRef
import categorizer.domain.RecognitionErrorCode
import categorizer.domain.RecognitionInput
import categorizer.domain.RecognitionOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertTrue

class AndroidOnnxRecognitionEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val input = RecognitionInput(ManagedImageRef("fixture", "images/missing.jpg"))

    @Test fun missingBundleFailsHonestlyAndRepeatedCallsDoNotCreateSessions() = runBlocking {
        AndroidOnnxRecognitionEngine(context, "recognition-missing").use { engine ->
            repeat(3) {
                val failed = assertIs<RecognitionOutcome.Failed>(engine.recognize(input))
                assertEquals(RecognitionErrorCode.MODEL_UNAVAILABLE, failed.error.code)
            }
        }
    }

    @Test fun malformedManifestFailsAsInvalidBundle() = runBlocking {
        AndroidOnnxRecognitionEngine(context, "recognition-invalid").use { engine ->
            val failed = assertIs<RecognitionOutcome.Failed>(engine.recognize(input))
            assertEquals(RecognitionErrorCode.INVALID_MODEL_BUNDLE, failed.error.code)
        }
    }

    @Test fun rankingMatchesPythonTiePolicyAndBoundsTopK() {
        assertEquals(listOf(0, 1, 3), rankIndices(floatArrayOf(1f, 1f, -1f, 0.5f), 3))
        assertEquals(listOf(1, 0), rankIndices(floatArrayOf(0f, 1f), 5))
    }

    @Test fun bundledLepidopteraClassesUseCategoryNeutralIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val image = File(appContext.filesDir, "images/category-identity.jpg").apply { parentFile?.mkdirs() }
        instrumentation.context.assets.open("Polyommatus_eros.ZIP").use { source ->
            ZipInputStream(source).use { archive ->
                var entry = archive.nextEntry
                while (entry != null && !entry.name.lowercase().endsWith(".jpg")) entry = archive.nextEntry
                check(entry != null) { "Benchmark archive has no JPEG fixture" }
                FileOutputStream(image).use { archive.copyTo(it) }
            }
        }
        try {
            AndroidOnnxRecognitionEngine(appContext).use { engine ->
                val result = assertIs<RecognitionOutcome.Completed>(
                    engine.recognize(RecognitionInput(ManagedImageRef("category-identity", "images/category-identity.jpg")))
                ).result
                assertTrue(result.candidates.isNotEmpty())
                assertTrue(result.candidates.all { it.identity.categoryId == "lepidoptera" })
                assertTrue(result.candidates.all { !it.identity.scientificName.isNullOrBlank() })
            }
        } finally { image.delete() }
    }
}
