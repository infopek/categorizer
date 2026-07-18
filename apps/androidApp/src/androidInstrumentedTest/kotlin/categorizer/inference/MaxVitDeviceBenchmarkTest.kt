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
        val sessionStarted = SystemClock.elapsedRealtimeNanos()
        val session = environment.createSession(model.absolutePath, options)
        val sessionMs = elapsedMs(sessionStarted)
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
            val samples = LongArray(MEASURED_RUNS)
            repeat(MEASURED_RUNS) { sequence ->
                tensor(environment, fixtures[sequence % fixtures.size]).use { tensor ->
                    val started = SystemClock.elapsedRealtimeNanos()
                    session.run(mapOf("images" to tensor)).close()
                    samples[sequence] = SystemClock.elapsedRealtimeNanos() - started
                }
                if ((sequence + 1) % 10 == 0) {
                    println("BENCHMARK_PROGRESS ${sequence + 1}/$MEASURED_RUNS")
                }
            }
            val sorted = samples.sorted()
            val medianMs = percentile(sorted, 0.50)
            val p90Ms = percentile(sorted, 0.90)
            val p95Ms = percentile(sorted, 0.95)
            val meanMs = samples.average() / 1_000_000.0
            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            println(
                "BENCHMARK_RESULT " +
                    "copy_ms=$copyMs session_ms=$sessionMs fixtures=${fixtures.size} " +
                    "runs=$MEASURED_RUNS median_ms=$medianMs p90_ms=$p90Ms p95_ms=$p95Ms " +
                    "min_ms=${sorted.first() / 1_000_000.0} " +
                    "max_ms=${sorted.last() / 1_000_000.0} mean_ms=$meanMs " +
                    "total_pss_kib=${memory.totalPss} " +
                    "samples_ns=${samples.joinToString(",")}",
            )
            check(medianMs < 2_000.0) { "Median warm inference must be below 2000 ms" }
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

    private companion object {
        const val WARM_UP_RUNS = 10
        const val MEASURED_RUNS = 100
    }
}
