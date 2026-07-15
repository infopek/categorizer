package categorizer.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import categorizer.application.ManualIdentityInput
import categorizer.application.RecognitionUiState
import categorizer.domain.CarIdentity
import categorizer.domain.ManagedImageRef
import categorizer.domain.RecognitionCandidate
import categorizer.domain.RecognitionError
import categorizer.domain.RecognitionErrorCode
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecognitionReviewScreenTest {
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launch() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() = scenario.close()

    @Test
    fun rankedCandidatesRemainOrderedSelectableAndConfirmable() {
        var confirmed: String? = null
        show(candidateState(), onConfirmCandidate = { confirmed = it })

        val first = findText("Porsche 911 (992)")
        val second = findText("BMW 3 Series (G20)")
        val firstBounds = Rect().also(first::getBoundsInScreen)
        val secondBounds = Rect().also(second::getBoundsInScreen)
        assertTrue(firstBounds.top < secondBounds.top)
        findText("#1")
        findText("#2")
        screenshot("ranked")
        click(findText("Confirm and save"))
        assertEquals("porsche-911-992", confirmed)
    }

    @Test
    fun uncertainStateUsesHonestWordingAndKeepsCandidates() {
        show(
            RecognitionUiState.Uncertain(
                "uncertain", image, candidates, 30, "fixture-model"
            )
        )
        findText("We’re not certain")
        findText("Review the ranked suggestions or enter the identity yourself.")
        findText("Porsche 911 (992)")
        screenshot("uncertain")
    }

    @Test
    fun unsupportedStateValidatesAndSubmitsManualIdentity() {
        var submitted: ManualIdentityInput? = null
        show(
            RecognitionUiState.Unsupported("unsupported", image, 12, "fixture-model"),
            onConfirmManual = { submitted = it }
        )
        findText("This car isn’t supported yet")
        screenshot("unsupported")
        click(findText("Confirm manual identity"))
        findText("Make is required")
        findText("Model is required")

        val fields = findNodes { it.className == "android.widget.EditText" }
        setText(fields[0], "Mercedes-Benz")
        setText(fields[1], "C-Class")
        setText(fields[2], "W205")
        setText(fields[3], "2014-2021")
        screenshot("manual-correction")
        click(findText("Confirm manual identity"))
        assertEquals("Mercedes-Benz", submitted?.make)
        assertEquals("C-Class", submitted?.model)
    }

    @Test
    fun recoverableEngineErrorOffersRetry() {
        var retries = 0
        show(
            RecognitionUiState.Error(
                image,
                RecognitionError(RecognitionErrorCode.INFERENCE_FAILED, "Recognition interrupted", true)
            ),
            onRetry = { retries++ }
        )
        findText("Recognition couldn’t finish")
        click(findText("Try recognition again"))
        assertEquals(1, retries)
    }

    private fun show(
        state: RecognitionUiState,
        onRetry: () -> Unit = {},
        onConfirmCandidate: (String) -> Unit = {},
        onConfirmManual: (ManualIdentityInput) -> Unit = {}
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                RecognitionReviewScreen(
                    state = state,
                    saveState = ReviewSaveState.Ready,
                    onRetry = onRetry,
                    onCancel = {},
                    onConfirmCandidate = onConfirmCandidate,
                    onConfirmManual = onConfirmManual
                )
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun candidateState() = RecognitionUiState.Candidates(
        "ranked", image, candidates, 25, "fixture-model"
    )

    private fun findText(text: String) = findNode { it.text?.toString() == text }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val nodes = findNodes(predicate)
            if (nodes.isNotEmpty()) return nodes.first()
            Thread.sleep(50)
        }
        return assertNotNull(null, "Expected accessibility node was not visible")
    }

    private fun findNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return emptyList()
        return buildList { root.collectDepthFirst(predicate, this) }
    }

    private fun AccessibilityNodeInfo.collectDepthFirst(
        predicate: (AccessibilityNodeInfo) -> Boolean,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (predicate(this)) result += this
        repeat(childCount) { index -> getChild(index)?.collectDepthFirst(predicate, result) }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        val context = instrumentation.targetContext
        val temporary = File(context.cacheDir, "ui-003-$name.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ui-003-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Categorizer/UI-003")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri).use { output ->
            checkNotNull(output)
            temporary.inputStream().use { input -> input.copyTo(output) }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        temporary.delete()
    }

    private companion object {
        const val UI_TIMEOUT_MS = 5_000L
        val image = ManagedImageRef("review-image", "images/review-image.jpg")
        val candidates = listOf(
            RecognitionCandidate(
                CarIdentity(
                    "porsche-911-992", "Porsche", "911", "992", "2019-present",
                    "Porsche 911 (992)"
                ),
                1, 0.5f, "fixture-model"
            ),
            RecognitionCandidate(
                CarIdentity(
                    "bmw-3-series-g20", "BMW", "3 Series", "G20", "2018-present",
                    "BMW 3 Series (G20)"
                ),
                2, 0.5f, "fixture-model"
            )
        )
    }
}
