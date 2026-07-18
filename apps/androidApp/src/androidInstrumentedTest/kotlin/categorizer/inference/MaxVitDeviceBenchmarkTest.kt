package categorizer.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals

class MaxVitDeviceBenchmarkTest {
    @Test
    fun benchmarkColdSessionAndInference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val model = File(targetContext.cacheDir, "lepidoptera-maxvit-t.onnx")
        check(!model.exists() || model.delete()) { "Cannot clear the extracted model before a cold trial" }

        val totalStarted = SystemClock.elapsedRealtimeNanos()
        val fixtureStarted = SystemClock.elapsedRealtimeNanos()
        val fixture = instrumentation.context.assets.open("Polyommatus_eros.ZIP").use { source ->
            ZipInputStream(source).use { archive ->
                var entry = archive.nextEntry
                while (entry != null && !entry.name.lowercase().endsWith(".jpg")) entry = archive.nextEntry
                check(entry != null) { "Benchmark archive has no JPEG fixture" }
                val bitmap = requireNotNull(BitmapFactory.decodeStream(archive))
                try {
                    preprocess(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val fixtureMs = elapsedMs(fixtureStarted)
        val copyStarted = SystemClock.elapsedRealtimeNanos()
        targetContext.assets.open("recognition/model.onnx").use { input ->
            FileOutputStream(model).use { output -> input.copyTo(output) }
        }
        val copyMs = elapsedMs(copyStarted)
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        val sessionStarted = SystemClock.elapsedRealtimeNanos()
        val session = environment.createSession(model.absolutePath, options)
        val sessionMs = elapsedMs(sessionStarted)
        options.close()
        val inferenceStarted = SystemClock.elapsedRealtimeNanos()
        session.use {
            tensor(environment, fixture).use { input ->
                session.run(mapOf("images" to input)).use { result ->
                    assertEquals(163, (result[0].value as Array<FloatArray>)[0].size)
                }
            }
        }
        val inferenceMs = elapsedMs(inferenceStarted)
        val totalMs = elapsedMs(totalStarted)
        println(
            "BENCHMARK_COLD_RESULT fixture_ms=$fixtureMs copy_ms=$copyMs " +
                "session_ms=$sessionMs inference_ms=$inferenceMs total_ms=$totalMs",
        )
    }

    @Test
    fun benchmarkWarmInference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val model = File(instrumentation.targetContext.cacheDir, "lepidoptera-maxvit-t.onnx")
        val modelDirectory = requireNotNull(model.parentFile)
        check(modelDirectory.isDirectory || modelDirectory.mkdirs()) {
            "Cannot create benchmark code-cache directory"
        }
        val copyStarted = SystemClock.elapsedRealtimeNanos()
        instrumentation.targetContext.assets.open("recognition/model.onnx").use { input ->
            FileOutputStream(model).use { output -> input.copyTo(output) }
        }
        val copyMs = elapsedMs(copyStarted)
        val fixtures = mutableListOf<FloatArray>()
        testContext.assets.open("Polyommatus_eros.ZIP").use { source ->
            ZipInputStream(source).use { archive ->
                var entry = archive.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".jpg")) {
                        val bitmap = requireNotNull(BitmapFactory.decodeStream(archive))
                        fixtures += preprocess(bitmap)
                        bitmap.recycle()
                    }
                    archive.closeEntry()
                    entry = archive.nextEntry
                }
            }
        }
        check(fixtures.size >= 30) { "Benchmark requires at least 30 valid images" }
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        val memorySamples = mutableListOf<MemorySample>()
        fun sampleMemory(label: String): Int {
            val totalPss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
            memorySamples += MemorySample(label, totalPss)
            println("BENCHMARK_MEMORY label=$label total_pss_kib=$totalPss")
            return totalPss
        }
        val baselinePss = sampleMemory("before_session")
        val sessionStarted = SystemClock.elapsedRealtimeNanos()
        val session = environment.createSession(model.absolutePath, options)
        val sessionMs = elapsedMs(sessionStarted)
        sampleMemory("after_session")
        options.close()
        session.use {
            assertEquals(listOf("images"), session.inputNames.toList())
            assertEquals(listOf("logits"), session.outputNames.toList())
            repeat(WARM_UP_RUNS) { sequence ->
                tensor(environment, fixtures[sequence % fixtures.size]).use { tensor ->
                    session.run(mapOf("images" to tensor)).use { result ->
                        assertEquals(163, (result[0].value as Array<FloatArray>)[0].size)
                    }
                }
                println("BENCHMARK_WARMUP ${sequence + 1}/$WARM_UP_RUNS")
            }
            val samples = LongArray(MEASURED_RUNS * MEASURED_SEQUENCES)
            val sequenceEndPss = IntArray(MEASURED_SEQUENCES)
            repeat(MEASURED_SEQUENCES) { measuredSequence ->
                repeat(MEASURED_RUNS) { request ->
                    val sampleIndex = measuredSequence * MEASURED_RUNS + request
                    tensor(environment, fixtures[sampleIndex % fixtures.size]).use { tensor ->
                        val started = SystemClock.elapsedRealtimeNanos()
                        session.run(mapOf("images" to tensor)).close()
                        samples[sampleIndex] = SystemClock.elapsedRealtimeNanos() - started
                    }
                    if ((request + 1) % MEMORY_SAMPLE_INTERVAL == 0) {
                        sampleMemory("sequence_${measuredSequence + 1}_request_${request + 1}")
                    }
                    if ((request + 1) % 10 == 0) {
                        println(
                            "BENCHMARK_PROGRESS sequence=${measuredSequence + 1}/$MEASURED_SEQUENCES " +
                                "request=${request + 1}/$MEASURED_RUNS",
                        )
                    }
                }
                sequenceEndPss[measuredSequence] = sampleMemory("sequence_${measuredSequence + 1}_end")
            }
            val sorted = samples.sorted()
            val medianMs = percentile(sorted, 0.50)
            val p90Ms = percentile(sorted, 0.90)
            val p95Ms = percentile(sorted, 0.95)
            val meanMs = samples.average() / 1_000_000.0
            val peakPss = memorySamples.maxOf { it.totalPssKib }
            val workingMemoryPss = peakPss - baselinePss
            println(
                "BENCHMARK_RESULT " +
                    "copy_ms=$copyMs session_ms=$sessionMs fixtures=${fixtures.size} " +
                    "sequences=$MEASURED_SEQUENCES runs_per_sequence=$MEASURED_RUNS " +
                    "median_ms=$medianMs p90_ms=$p90Ms p95_ms=$p95Ms " +
                    "min_ms=${sorted.first() / 1_000_000.0} " +
                    "max_ms=${sorted.last() / 1_000_000.0} mean_ms=$meanMs " +
                    "baseline_pss_kib=$baselinePss peak_pss_kib=$peakPss " +
                    "working_memory_pss_kib=$workingMemoryPss " +
                    "sequence_end_pss_kib=${sequenceEndPss.joinToString(",")} " +
                    "samples_ns=${samples.joinToString(",")}",
            )
            check(medianMs < 2_000.0) { "Median warm inference must be below 2000 ms" }
            check(peakPss <= PEAK_PSS_HARD_GATE_KIB) { "Peak PSS exceeds the 1024 MiB hard gate" }
            check(workingMemoryPss <= WORKING_MEMORY_HARD_GATE_KIB) {
                "Inference working memory exceeds the 768 MiB hard gate"
            }
        }
    }

    private fun tensor(environment: OrtEnvironment, values: FloatArray): OnnxTensor =
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(values),
            longArrayOf(1, 3, 224, 224),
        )

    private fun preprocess(source: Bitmap): FloatArray {
        val scale = 224f / minOf(source.width, source.height)
        val width = ceil(source.width * scale).toInt()
        val height = ceil(source.height * scale).toInt()
        val resized = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val pixels = IntArray(224 * 224)
            resized.getPixels(pixels, 0, 224, (width - 224) / 2, (height - 224) / 2, 224, 224)
            val output = FloatArray(3 * 224 * 224)
            val plane = 224 * 224
            for (index in pixels.indices) {
                val color = pixels[index]
                output[index] = ((((color shr 16) and 255) / 255f) - 0.485f) / 0.229f
                output[plane + index] = ((((color shr 8) and 255) / 255f) - 0.456f) / 0.224f
                output[2 * plane + index] = (((color and 255) / 255f) - 0.406f) / 0.225f
            }
            return output
        } finally {
            if (resized !== source) resized.recycle()
        }
    }

    private fun elapsedMs(started: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    private fun percentile(sorted: List<Long>, quantile: Double): Double {
        val index = ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index] / 1_000_000.0
    }

    private data class MemorySample(val label: String, val totalPssKib: Int)

    private companion object {
        const val WARM_UP_RUNS = 10
        const val MEASURED_RUNS = 100
        const val MEASURED_SEQUENCES = 3
        const val MEMORY_SAMPLE_INTERVAL = 10
        const val PEAK_PSS_HARD_GATE_KIB = 1_048_576
        const val WORKING_MEMORY_HARD_GATE_KIB = 786_432
    }
}
