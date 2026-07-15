package categorizer.app

import android.content.Context
import org.json.JSONObject

class AndroidModelInfoLoader(private val context: Context) {
    fun load(infoAsset: String = "model-info.json"): ModelInfoUiState = try {
        val info = context.assets.open(infoAsset).bufferedReader().use { it.readText() }
        parse(info) { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
    } catch (error: Exception) { ModelInfoUiState.Invalid(error.message ?: "Bundled model information is invalid") }

    internal fun parse(infoJson: String, catalogReader: (String) -> String): ModelInfoUiState = try {
        val root = JSONObject(infoJson)
        require(root.getString("schema_version") == "1.0.0") { "Unsupported model information version" }
        val catalog = JSONObject(catalogReader(root.getString("catalog_file")))
        require(catalog.getString("status") == "accepted") { "Catalog is not accepted" }
        require(catalog.getString("catalog_id") == root.getString("catalog_id")) { "Manifest and catalog identifiers do not match" }
        val classesJson = catalog.getJSONArray("classes")
        require(catalog.getInt("class_count") == classesJson.length()) { "Catalog class count does not match its entries" }
        val classes = (0 until classesJson.length()).map { classesJson.getJSONObject(it).getString("display_name") }
        val noticesJson = root.getJSONArray("notices")
        require(noticesJson.length() > 0) { "Required license notices are missing" }
        val notices = (0 until noticesJson.length()).map { index -> noticesJson.getJSONObject(index).let { ModelNotice(it.getString("name"), it.getString("license"), it.getString("acknowledgement")) } }
        ModelInfoUiState.Ready(ModelInfo(if (root.isNull("model_version")) null else root.getString("model_version"), catalog.getString("catalog_id"), classes, notices))
    } catch (error: Exception) { ModelInfoUiState.Invalid(error.message ?: "Bundled model information is invalid") }
}
