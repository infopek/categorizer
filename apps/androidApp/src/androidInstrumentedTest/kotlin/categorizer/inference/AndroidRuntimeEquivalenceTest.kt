package categorizer.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

class AndroidRuntimeEquivalenceTest {
    @Test
    fun bundledOnnxMatchesPinnedPyTorchReference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val contract = instrumentation.context.assets.open("recognition-equivalence.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("1.0.0", contract.getString("schema_version"))
        val tolerance = contract.getDouble("absolute_tolerance").toFloat()
        val model = File(instrumentation.targetContext.cacheDir, "equivalence-model.onnx")
        instrumentation.targetContext.assets.open("recognition/model.onnx").use { input ->
            FileOutputStream(model).use(input::copyTo)
        }

        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            environment.createSession(model.absolutePath, options).use { session ->
                val fixtures = contract.getJSONArray("fixtures")
                repeat(fixtures.length()) { fixtureIndex ->
                    val fixture = fixtures.getJSONObject(fixtureIndex)
                    val values = FloatArray(TENSOR_SIZE) { fixture.getDouble("fill").toFloat() }
                    val overrides = fixture.getJSONArray("overrides")
                    repeat(overrides.length()) { overrideIndex ->
                        val override = overrides.getJSONObject(overrideIndex)
                        values[override.getInt("index")] = override.getDouble("value").toFloat()
                    }
                    val actual = OnnxTensor.createTensor(
                        environment,
                        FloatBuffer.wrap(values),
                        longArrayOf(1, 3, 224, 224),
                    ).use { tensor ->
                        session.run(mapOf("images" to tensor)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            (result[0].value as Array<FloatArray>)[0]
                        }
                    }
                    val expectedJson = fixture.getJSONArray("expected_logits")
                    val expected = FloatArray(expectedJson.length()) { expectedJson.getDouble(it).toFloat() }
                    val maximumDifference = actual.indices.maxOf { abs(actual[it] - expected[it]) }
                    val actualTopFive = actual.indices.sortedWith(compareByDescending<Int> { actual[it] }.thenBy { it }).take(5)
                    val expectedTopFiveJson = fixture.getJSONArray("expected_top_five")
                    val expectedTopFive = List(expectedTopFiveJson.length()) { expectedTopFiveJson.getInt(it) }
                    assertTrue(maximumDifference <= tolerance, "${fixture.getString("name")} max difference $maximumDifference")
                    assertEquals(expectedTopFive, actualTopFive, fixture.getString("name"))
                }
            }
        }
    }

    private companion object {
        const val TENSOR_SIZE = 3 * 224 * 224
    }
}
