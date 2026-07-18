package categorizer.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class ModelInfoScreenTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    @Before fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() = scenario.close()

    @Test fun bundledCatalogPrivacyAndNoticesAreRendered() {
        val state = assertIs<ModelInfoUiState.Ready>(AndroidModelInfoLoader(InstrumentationRegistry.getInstrumentation().targetContext).load())
        assertEquals("lepidoptera-at-163-v1", state.info.catalogId); assertEquals(163, state.info.classes.size)
        assertEquals("Death's Head Hawkmoth", state.info.classes.first())
        assertTrue(state.info.notices.any { it.license == "CC BY 4.0" })
        show(state); findText("Model version lepidoptera-maxvit-t-163-pilot"); findText("Supported catalog: lepidoptera-at-163-v1 · 163 species"); findText("Private and offline"); findText("Recognition has limits"); screenshot("disclosures")
    }

    @Test fun missingAndInvalidManifestFailSafely() {
        val loader = AndroidModelInfoLoader(InstrumentationRegistry.getInstrumentation().targetContext)
        assertIs<ModelInfoUiState.Invalid>(loader.load("missing-model-info.json"))
        val withoutBundle = assertIs<ModelInfoUiState.Ready>(loader.load(bundleAssetDir = "recognition-missing"))
        assertEquals(null, withoutBundle.info.modelVersion)
        val invalid = assertIs<ModelInfoUiState.Invalid>(loader.parse("{\"schema_version\":\"9.0.0\"}") { "{}" })
        show(invalid); findText("Model information unavailable"); findText("Recognition stays disabled until a valid bundled manifest is installed. Your album remains available."); screenshot("invalid-manifest")
    }

    private fun show(state: ModelInfoUiState) { scenario.onActivity { it.setContent { ModelInfoScreen(state) {} } }; InstrumentationRegistry.getInstrumentation().waitForIdleSync() }
    private fun findText(value: String): AccessibilityNodeInfo { val deadline = System.currentTimeMillis() + 5_000; while (System.currentTimeMillis() < deadline) { nodes().firstOrNull { it.text?.toString() == value }?.let { return it }; Thread.sleep(50) }; return assertNotNull(null, "Missing $value") }
    private fun nodes(): List<AccessibilityNodeInfo> { val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList(); return buildList { root.collect(this) } }
    private fun AccessibilityNodeInfo.collect(out: MutableList<AccessibilityNodeInfo>) { out += this; repeat(childCount) { getChild(it)?.collect(out) } }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); instrumentation.waitForIdleSync(); Thread.sleep(250); val context = instrumentation.targetContext; val temporary = File(context.cacheDir, "ui-006-$name.png")
        FileOutputStream(temporary).use { check(checkNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "ui-006-$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Categorizer/UI-006"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)); context.contentResolver.openOutputStream(uri).use { out -> temporary.inputStream().use { it.copyTo(checkNotNull(out)) } }; values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); context.contentResolver.update(uri, values, null, null); temporary.delete()
    }
}
