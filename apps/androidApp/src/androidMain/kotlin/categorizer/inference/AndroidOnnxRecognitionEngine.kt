package categorizer.inference

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import categorizer.domain.CarIdentity
import categorizer.domain.ManagedImageRef
import categorizer.domain.RecognitionCandidate
import categorizer.domain.RecognitionEngine
import categorizer.domain.RecognitionError
import categorizer.domain.RecognitionErrorCode
import categorizer.domain.RecognitionInput
import categorizer.domain.RecognitionOutcome
import categorizer.domain.RecognitionResult
import categorizer.domain.RecognitionStatus
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.FloatBuffer
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class AndroidOnnxRecognitionEngine(
    context: Context,
    private val bundleAssetPath: String = "recognition",
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) : RecognitionEngine, AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private val assets = context.assets
    private val mutex = Mutex()
    @Volatile private var loaded: LoadedBundle? = null
    @Volatile private var closed = false

    override suspend fun recognize(input: RecognitionInput): RecognitionOutcome = try {
        coroutineContext.ensureActive()
        val bundle = loadBundle()
        val image = resolve(input.sourceImage) ?: return failure(RecognitionErrorCode.IMAGE_UNREADABLE, "Managed image is missing", true)
        val started = clockMs()
        val tensorData = preprocess(image, bundle.manifest)
        coroutineContext.ensureActive()
        val logits = mutex.withLock {
            coroutineContext.ensureActive()
            OnnxTensor.createTensor(bundle.environment, FloatBuffer.wrap(tensorData), bundle.manifest.inputShape).use { tensor ->
                bundle.session.run(mapOf(bundle.manifest.inputName to tensor)).use { result ->
                    val value = result.get(bundle.manifest.outputName).orElseThrow { IllegalStateException("Output tensor is missing") }
                    val info = value.info as? TensorInfo ?: throw IllegalStateException("Output must be a tensor")
                    if (info.type != OnnxJavaType.FLOAT) throw IllegalStateException("Output tensor must be float32")
                    (value as OnnxTensor).floatBuffer.let { buffer -> FloatArray(buffer.remaining()).also(buffer::get) }
                }
            }
        }
        coroutineContext.ensureActive()
        if (logits.size != bundle.classes.size || logits.any { !it.isFinite() }) throw IllegalStateException("Output class shape is invalid")
        val probabilities = softmax(logits)
        val ordered = rankIndices(logits, bundle.manifest.topK)
        val candidates = ordered.mapIndexed { rank, index ->
            RecognitionCandidate(bundle.classes[index], rank + 1, probabilities[index], bundle.manifest.modelVersion)
        }
        RecognitionOutcome.Completed(RecognitionResult(UUID.randomUUID().toString(), input.sourceImage, candidates, RecognitionStatus.CANDIDATES, clockMs() - started, bundle.manifest.modelVersion))
    } catch (_: kotlinx.coroutines.CancellationException) {
        RecognitionOutcome.Cancelled
    } catch (_: OutOfMemoryError) {
        failure(RecognitionErrorCode.OUT_OF_MEMORY, "Recognition ran out of memory", true)
    } catch (error: BundleException) {
        failure(error.code, error.message ?: "Invalid recognition bundle", false)
    } catch (error: UnsatisfiedLinkError) {
        failure(RecognitionErrorCode.MODEL_UNAVAILABLE, "ONNX Runtime native library is unavailable", false)
    } catch (error: Exception) {
        failure(RecognitionErrorCode.INFERENCE_FAILED, error.message ?: "Recognition failed", true)
    }

    override fun close() {
        closed = true
        runBlocking { mutex.withLock { loaded?.session?.close();loaded = null } }
    }

    private suspend fun loadBundle(): LoadedBundle {
        loaded?.let { return it }
        return mutex.withLock {
            loaded?.let { return@withLock it }
            if (closed) throw BundleException(RecognitionErrorCode.MODEL_UNAVAILABLE, "Recognition engine is closed")
            val manifestBytes = asset("model-manifest.json")
            val manifest = try { parseManifest(JSONObject(manifestBytes.decodeToString())) } catch (error: BundleException) { throw error } catch (_: Exception) { throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE, "Model manifest is invalid") }
            val classBytes = asset(manifest.classMapFilename)
            if (sha256(classBytes) != manifest.classMapSha256) throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE, "Class-map hash mismatch")
            val classes = parseClasses(JSONObject(classBytes.decodeToString()), manifest)
            val modelBytes = asset(manifest.modelFilename)
            if (modelBytes.size.toLong() != manifest.modelSize || modelBytes.size > MAX_MODEL_BYTES) throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE, "Model size mismatch or exceeds safety limit")
            if (sha256(modelBytes) != manifest.modelSha256) throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE, "Model hash mismatch")
            val modelFile = File(appContext.codeCacheDir, "categorizer-${manifest.modelSha256}.onnx")
            if (!modelFile.isFile || sha256(modelFile.readBytes()) != manifest.modelSha256) FileOutputStream(modelFile).use { it.write(modelBytes);it.fd.sync() }
            val environment = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { environment.createSession(modelFile.absolutePath, it) }
            validateSession(session, manifest, classes.size)
            LoadedBundle(environment, session, manifest, classes).also { loaded = it }
        }
    }

    private fun asset(name: String): ByteArray = try { assets.open("$bundleAssetPath/$name").use { it.readBytes() } }
    catch (_: Exception) { throw BundleException(RecognitionErrorCode.MODEL_UNAVAILABLE, "Bundled recognition model is not installed") }

    private fun resolve(ref: ManagedImageRef): File? {
        val root = appContext.filesDir.canonicalFile
        return File(root, ref.relativePath).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    private fun preprocess(file: File, manifest: Manifest): FloatArray {
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: throw BundleException(RecognitionErrorCode.IMAGE_UNREADABLE, "Managed image cannot be decoded")
        try {
            val scale = manifest.resizeShort.toFloat() / minOf(source.width, source.height)
            val width = kotlin.math.ceil(source.width * scale).toInt();val height = kotlin.math.ceil(source.height * scale).toInt()
            val resized = Bitmap.createScaledBitmap(source, width, height, true)
            try {
                val left=(width-manifest.width)/2;val top=(height-manifest.height)/2
                if (left<0 || top<0) throw BundleException(RecognitionErrorCode.IMAGE_UNREADABLE,"Image is too small after resize")
                val pixels=IntArray(manifest.width*manifest.height);resized.getPixels(pixels,0,manifest.width,left,top,manifest.width,manifest.height);val plane=manifest.width*manifest.height;val output=FloatArray(plane*3)
                for(i in pixels.indices){val color=pixels[i];output[i]=(((color shr 16) and 255)/255f-manifest.mean[0])/manifest.std[0];output[plane+i]=(((color shr 8) and 255)/255f-manifest.mean[1])/manifest.std[1];output[2*plane+i]=((color and 255)/255f-manifest.mean[2])/manifest.std[2]}
                return output
            } finally { if(resized!==source)resized.recycle() }
        } finally { source.recycle() }
    }

    private fun validateSession(session:OrtSession,m:Manifest,classCount:Int){val input=session.inputInfo[m.inputName]?.info as? TensorInfo ?: throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE,"Input tensor is missing");val output=session.outputInfo[m.outputName]?.info as? TensorInfo ?: throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE,"Output tensor is missing");if(input.type!=OnnxJavaType.FLOAT || !input.shape.contentEquals(m.inputShape) || output.type!=OnnxJavaType.FLOAT || !output.shape.contentEquals(longArrayOf(1,classCount.toLong())))throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE,"Tensor metadata mismatch")}
    private fun parseManifest(j:JSONObject):Manifest { val model=j.getJSONObject("model");val map=j.getJSONObject("class_map");val input=j.getJSONObject("input");val output=j.getJSONObject("output");val pre=j.getJSONObject("preprocessing");val resize=pre.getJSONObject("resize");val crop=pre.getJSONObject("crop");val normalization=pre.getJSONObject("normalization");if(j.getString("schema_version")!="1.0.0" || model.getString("format")!="ONNX" || input.getString("element_type")!="float32" || input.getString("layout")!="NCHW" || input.getString("color_order")!="RGB" || output.getString("element_type")!="float32" || output.getString("semantics")!="logits" || output.getString("ranking")!="descending_score_then_ascending_class_index" || resize.getString("mode")!="shorter_side" || resize.getString("interpolation")!="bilinear" || crop.getString("mode")!="center")throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE,"Unsupported manifest contract");return Manifest(j.getString("model_version"),model.getString("filename"),model.getString("sha256"),model.getLong("size_bytes"),map.getString("filename"),map.getString("sha256"),map.getString("catalog_id"),map.getInt("class_count"),input.getString("tensor_name"),output.getString("tensor_name"),output.getInt("top_k"),input.getJSONArray("shape").longs(),resize.getInt("shorter_side"),crop.getInt("width"),crop.getInt("height"),normalization.getJSONArray("mean").floats(),normalization.getJSONArray("std").floats()).also { if(it.inputShape.size!=4 || it.inputShape[0]!=1L || it.inputShape[1]!=3L || it.inputShape[2]!=it.height.toLong() || it.inputShape[3]!=it.width.toLong() || it.topK !in 1..it.classCount)throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE,"Unsupported input/output metadata") } }
    private fun parseClasses(j:JSONObject,m:Manifest):List<CarIdentity>{val values=j.getJSONArray("classes");if(j.getString("schema_version")!="1.0.0" || j.getString("catalog_id")!=m.catalogId || j.getInt("class_count")!=m.classCount || values.length()!=m.classCount)throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE,"Class-map identity mismatch");return (0 until values.length()).map { i->val x=values.getJSONObject(i);if(x.getInt("index")!=i)throw BundleException(RecognitionErrorCode.INVALID_MODEL_BUNDLE,"Class order mismatch");CarIdentity(x.getString("class_id"),x.getString("make"),x.getString("model"),x.optString("generation_label").takeIf(String::isNotBlank),x.optString("approximate_year_range").takeIf(String::isNotBlank),x.getString("display_name")) }}
    private fun JSONArray.longs()=LongArray(length()){getLong(it)};private fun JSONArray.floats()=FloatArray(length()){getDouble(it).toFloat()}
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun softmax(values:FloatArray):FloatArray{val max=values.max();val exp=FloatArray(values.size){exp((values[it]-max).toDouble()).toFloat()};val sum=exp.sum();return FloatArray(values.size){exp[it]/sum}}
    private fun failure(code:RecognitionErrorCode,message:String,recoverable:Boolean)=RecognitionOutcome.Failed(RecognitionError(code,message,recoverable))
    private data class LoadedBundle(val environment:OrtEnvironment,val session:OrtSession,val manifest:Manifest,val classes:List<CarIdentity>)
    private data class Manifest(val modelVersion:String,val modelFilename:String,val modelSha256:String,val modelSize:Long,val classMapFilename:String,val classMapSha256:String,val catalogId:String,val classCount:Int,val inputName:String,val outputName:String,val topK:Int,val inputShape:LongArray,val resizeShort:Int,val width:Int,val height:Int,val mean:FloatArray,val std:FloatArray)
    private class BundleException(val code:RecognitionErrorCode,message:String):Exception(message)
    private companion object { const val MAX_MODEL_BYTES=150*1024*1024 }
}

internal fun rankIndices(logits: FloatArray, topK: Int): List<Int> {
    require(logits.all(Float::isFinite)) { "Logits must be finite" }
    return logits.indices.sortedWith(compareByDescending<Int> { logits[it] }.thenBy { it }).take(minOf(topK, logits.size))
}
