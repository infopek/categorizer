package categorizer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import categorizer.application.AlbumEntryEditInput
import categorizer.application.AlbumEntryEditValidation
import categorizer.application.AlbumEntryEditor
import categorizer.application.AlbumEntryEditorResult
import categorizer.application.ManualIdentityInput
import categorizer.application.ManualIdentityValidation
import categorizer.application.RecognitionCoordinator
import categorizer.application.RecognitionEntrySaver
import categorizer.application.RecognitionSaveDraft
import categorizer.application.RecognitionSaveResult
import categorizer.application.RecognitionUiState
import categorizer.application.validate
import categorizer.data.AndroidAlbumRepository
import categorizer.domain.ManagedImageRef
import categorizer.domain.RecognitionEngine
import categorizer.domain.RecognitionError
import categorizer.domain.RecognitionErrorCode
import categorizer.domain.RecognitionOutcome
import categorizer.media.AndroidImageAcquisition
import categorizer.media.ImageAcquisitionResult
import categorizer.media.ManagedImageStore
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var albumRepository: AndroidAlbumRepository
    private lateinit var acquisition: AndroidImageAcquisition
    private lateinit var acquisitionController: AcquisitionFlowController
    private lateinit var imageStore: ManagedImageStore
    private lateinit var recognitionCoordinator: RecognitionCoordinator
    private lateinit var entrySaver: RecognitionEntrySaver
    private lateinit var entryEditor: AlbumEntryEditor
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var showingAcquisition by mutableStateOf(false)
    private var showingRecognition by mutableStateOf(false)
    private var acquisitionState by mutableStateOf<AcquisitionScreenState>(AcquisitionScreenState.Choosing())
    private var recognitionState by mutableStateOf<RecognitionUiState>(RecognitionUiState.Idle)
    private var reviewSaveState by mutableStateOf<ReviewSaveState>(ReviewSaveState.Ready)
    private var selectedEntryId by mutableStateOf<String?>(null)
    private var entryDetailState by mutableStateOf<EntryDetailUiState>(EntryDetailUiState.Loading)
    private var activeRequestToken = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumRepository = AndroidAlbumRepository(applicationContext)
        entrySaver = RecognitionEntrySaver(albumRepository)
        entryEditor = AlbumEntryEditor(albumRepository)
        recognitionCoordinator = RecognitionCoordinator(
            engine = UnavailableRecognitionEngine,
            scope = activityScope
        )
        activityScope.launch {
            recognitionCoordinator.state.collectLatest { recognitionState = it }
        }
        imageStore = ManagedImageStore(applicationContext)
        showingAcquisition = savedInstanceState?.getBoolean(KEY_SHOWING_ACQUISITION) ?: false
        showingRecognition = savedInstanceState?.getBoolean(KEY_SHOWING_RECOGNITION) ?: false
        selectedEntryId = savedInstanceState?.getString(KEY_SELECTED_ENTRY_ID)
        acquisitionState = restoreState(savedInstanceState)
        acquisitionController = AcquisitionFlowController(acquisitionState) { acquisitionState = it }
        activeRequestToken = acquisitionController.activeToken
        if (showingRecognition) {
            (acquisitionState as? AcquisitionScreenState.Review)?.let {
                recognitionCoordinator.submit(it.image)
            } ?: run { showingRecognition = false }
        }
        selectedEntryId?.let(::loadEntry)
        acquisition = AndroidImageAcquisition(
            activity = this,
            onProcessing = ::onProcessing,
            onResult = ::onAcquisitionResult
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingRecognition) leaveRecognition()
                else if (showingAcquisition) leaveAcquisition()
                else if (selectedEntryId != null) selectedEntryId = null
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        setContent {
            if (selectedEntryId != null) {
                AlbumEntryDetailScreen(
                    state = entryDetailState,
                    onBack = { selectedEntryId = null },
                    onRetry = { selectedEntryId?.let(::loadEntry) },
                    onToggleFavorite = { entry -> saveEntry(AlbumEntryEditInput.from(entry).copy(isFavorite = !entry.isFavorite)) },
                    onSave = ::saveEntry,
                    onDelete = ::deleteEntry
                )
            } else if (showingRecognition) {
                RecognitionReviewScreen(
                    state = recognitionState,
                    saveState = reviewSaveState,
                    onRetry = recognitionCoordinator::retry,
                    onCancel = ::leaveRecognition,
                    onConfirmCandidate = ::confirmCandidate,
                    onConfirmManual = ::confirmManual
                )
            } else if (showingAcquisition) {
                AcquisitionScreen(
                    state = acquisitionState,
                    onCamera = { launch(AcquisitionSource.CAMERA) },
                    onGallery = { launch(AcquisitionSource.GALLERY) },
                    onRetry = acquisitionController::reset,
                    onCancel = ::leaveAcquisition,
                    onContinue = ::startRecognition,
                    onChooseAnother = ::discardAndReset
                )
            } else {
                CategorizerApp(
                    repository = albumRepository,
                    onAddSighting = {
                        acquisitionController.reset()
                        showingAcquisition = true
                    },
                    onOpenEntry = ::openEntry
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOWING_ACQUISITION, showingAcquisition)
        outState.putBoolean(KEY_SHOWING_RECOGNITION, showingRecognition)
        outState.putString(KEY_SELECTED_ENTRY_ID, selectedEntryId)
        saveState(outState, acquisitionState)
    }

    override fun onDestroy() {
        acquisition.close()
        recognitionCoordinator.dispose()
        activityScope.cancel()
        albumRepository.close()
        super.onDestroy()
    }

    private fun launch(source: AcquisitionSource) {
        val token = acquisitionController.request(source) ?: return
        activeRequestToken = token
        when (source) {
            AcquisitionSource.CAMERA -> acquisition.takePhoto()
            AcquisitionSource.GALLERY -> acquisition.pickFromGallery()
        }
    }

    private fun onProcessing() {
        val source = (acquisitionController.state as? AcquisitionScreenState.Launching)?.source ?: return
        acquisitionController.processing(activeRequestToken, source)
    }

    private fun onAcquisitionResult(result: ImageAcquisitionResult) {
        when (result) {
            is ImageAcquisitionResult.Success ->
                acquisitionController.completed(activeRequestToken, result.image.reference)
            is ImageAcquisitionResult.Failure -> acquisitionController.failed(
                activeRequestToken, result.error.message, result.error.recoverable
            )
            ImageAcquisitionResult.Cancelled -> acquisitionController.cancelled(activeRequestToken)
        }
    }

    private fun discardAndReset() {
        (acquisitionController.state as? AcquisitionScreenState.Review)?.let { imageStore.delete(it.image) }
        acquisitionController.reset()
    }

    private fun startRecognition() {
        val image = (acquisitionController.state as? AcquisitionScreenState.Review)?.image ?: return
        reviewSaveState = ReviewSaveState.Ready
        showingRecognition = true
        recognitionCoordinator.submit(image)
    }

    private fun confirmCandidate(classId: String) {
        recognitionCoordinator.confirmCandidate(classId)?.let(::saveDraft)
    }

    private fun confirmManual(input: ManualIdentityInput) {
        val identity = (input.validate() as? ManualIdentityValidation.Valid)?.identity ?: return
        recognitionCoordinator.manualCorrection(identity)?.let(::saveDraft)
    }

    private fun saveDraft(draft: RecognitionSaveDraft) {
        if (reviewSaveState is ReviewSaveState.Saving) return
        reviewSaveState = ReviewSaveState.Saving
        activityScope.launch {
            when (val result = entrySaver.save(
                draft = draft,
                entryId = UUID.randomUUID().toString(),
                albumDate = LocalDate.now().toString(),
                nowEpochMs = System.currentTimeMillis()
            )) {
                is RecognitionSaveResult.Saved -> {
                    recognitionCoordinator.cancel()
                    acquisitionController.reset()
                    showingRecognition = false
                    showingAcquisition = false
                    reviewSaveState = ReviewSaveState.Ready
                }
                RecognitionSaveResult.DuplicateIgnored -> Unit
                is RecognitionSaveResult.Failed -> reviewSaveState = ReviewSaveState.Failed(
                    result.message, result.recoverable
                )
            }
        }
    }

    private fun leaveRecognition() {
        recognitionCoordinator.cancel()
        showingRecognition = false
        leaveAcquisition()
    }

    private fun leaveAcquisition() {
        discardAndReset()
        showingAcquisition = false
    }

    private fun openEntry(entryId: String) {
        selectedEntryId = entryId
        loadEntry(entryId)
    }

    private fun loadEntry(entryId: String) {
        entryDetailState = EntryDetailUiState.Loading
        activityScope.launch {
            entryDetailState = when (val result = entryEditor.load(entryId)) {
                is AlbumEntryEditorResult.Success -> EntryDetailUiState.Ready(result.value)
                is AlbumEntryEditorResult.Failed -> EntryDetailUiState.Unavailable(result.error)
            }
        }
    }

    private fun saveEntry(input: AlbumEntryEditInput) {
        val ready = entryDetailState as? EntryDetailUiState.Ready ?: return
        val validation = input.validate(ready.entry, System.currentTimeMillis())
        val updated = (validation as? AlbumEntryEditValidation.Valid)?.entry ?: return
        entryDetailState = ready.copy(saving = true, error = null)
        activityScope.launch {
            entryDetailState = when (val result = entryEditor.save(updated)) {
                is AlbumEntryEditorResult.Success -> EntryDetailUiState.Ready(result.value)
                is AlbumEntryEditorResult.Failed -> ready.copy(saving = false, error = result.error)
            }
        }
    }

    private fun deleteEntry() {
        val entryId = selectedEntryId ?: return
        activityScope.launch {
            when (val result = entryEditor.delete(entryId)) {
                is AlbumEntryEditorResult.Success -> {
                    imageStore.delete(result.value.removedImage)
                    selectedEntryId = null
                }
                is AlbumEntryEditorResult.Failed -> entryDetailState = EntryDetailUiState.Unavailable(result.error)
            }
        }
    }

    private fun restoreState(bundle: Bundle?): AcquisitionScreenState {
        val kind = bundle?.getString(KEY_STATE) ?: return AcquisitionScreenState.Choosing()
        return when (kind) {
            STATE_LAUNCHING -> AcquisitionScreenState.Launching(
                AcquisitionSource.valueOf(bundle.getString(KEY_SOURCE) ?: AcquisitionSource.GALLERY.name)
            )
            STATE_REVIEW -> {
                val id = bundle.getString(KEY_IMAGE_ID)
                val path = bundle.getString(KEY_IMAGE_PATH)
                if (id != null && path != null) AcquisitionScreenState.Review(ManagedImageRef(id, path))
                else AcquisitionScreenState.Choosing()
            }
            STATE_PROCESSING -> AcquisitionScreenState.Error(
                "Photo processing was interrupted. Please choose the photo again.", true
            )
            STATE_ERROR -> AcquisitionScreenState.Error(
                bundle.getString(KEY_MESSAGE) ?: "Photo preparation failed", true
            )
            else -> AcquisitionScreenState.Choosing(bundle.getString(KEY_MESSAGE))
        }
    }

    private fun saveState(bundle: Bundle, state: AcquisitionScreenState) {
        when (state) {
            is AcquisitionScreenState.Choosing -> {
                bundle.putString(KEY_STATE, STATE_CHOOSING)
                bundle.putString(KEY_MESSAGE, state.message)
            }
            is AcquisitionScreenState.Launching -> {
                bundle.putString(KEY_STATE, STATE_LAUNCHING)
                bundle.putString(KEY_SOURCE, state.source.name)
            }
            is AcquisitionScreenState.Processing -> bundle.putString(KEY_STATE, STATE_PROCESSING)
            is AcquisitionScreenState.Review -> {
                bundle.putString(KEY_STATE, STATE_REVIEW)
                bundle.putString(KEY_IMAGE_ID, state.image.imageId)
                bundle.putString(KEY_IMAGE_PATH, state.image.relativePath)
            }
            is AcquisitionScreenState.Error -> {
                bundle.putString(KEY_STATE, STATE_ERROR)
                bundle.putString(KEY_MESSAGE, state.message)
            }
        }
    }

    private companion object {
        const val KEY_SHOWING_ACQUISITION = "showing_acquisition"
        const val KEY_SHOWING_RECOGNITION = "showing_recognition"
        const val KEY_SELECTED_ENTRY_ID = "selected_entry_id"
        const val KEY_STATE = "acquisition_state"
        const val KEY_SOURCE = "acquisition_source"
        const val KEY_IMAGE_ID = "acquisition_image_id"
        const val KEY_IMAGE_PATH = "acquisition_image_path"
        const val KEY_MESSAGE = "acquisition_message"
        const val STATE_CHOOSING = "choosing"
        const val STATE_LAUNCHING = "launching"
        const val STATE_PROCESSING = "processing"
        const val STATE_REVIEW = "review"
        const val STATE_ERROR = "error"
    }
}

private object UnavailableRecognitionEngine : RecognitionEngine {
    override suspend fun recognize(input: categorizer.domain.RecognitionInput): RecognitionOutcome =
        RecognitionOutcome.Failed(
            RecognitionError(
                RecognitionErrorCode.MODEL_UNAVAILABLE,
                "The bundled recognition model is not installed in this build.",
                recoverable = false
            )
        )
}
