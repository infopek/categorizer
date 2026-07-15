package categorizer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import categorizer.data.AndroidAlbumRepository
import categorizer.domain.ManagedImageRef
import categorizer.media.AndroidImageAcquisition
import categorizer.media.ImageAcquisitionResult
import categorizer.media.ManagedImageStore

class MainActivity : ComponentActivity() {
    private lateinit var albumRepository: AndroidAlbumRepository
    private lateinit var acquisition: AndroidImageAcquisition
    private lateinit var acquisitionController: AcquisitionFlowController
    private lateinit var imageStore: ManagedImageStore
    private var showingAcquisition by mutableStateOf(false)
    private var acquisitionState by mutableStateOf<AcquisitionScreenState>(AcquisitionScreenState.Choosing())
    private var activeRequestToken = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumRepository = AndroidAlbumRepository(applicationContext)
        imageStore = ManagedImageStore(applicationContext)
        showingAcquisition = savedInstanceState?.getBoolean(KEY_SHOWING_ACQUISITION) ?: false
        acquisitionState = restoreState(savedInstanceState)
        acquisitionController = AcquisitionFlowController(acquisitionState) { acquisitionState = it }
        activeRequestToken = acquisitionController.activeToken
        acquisition = AndroidImageAcquisition(
            activity = this,
            onProcessing = ::onProcessing,
            onResult = ::onAcquisitionResult
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingAcquisition) leaveAcquisition() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        setContent {
            if (showingAcquisition) {
                AcquisitionScreen(
                    state = acquisitionState,
                    onCamera = { launch(AcquisitionSource.CAMERA) },
                    onGallery = { launch(AcquisitionSource.GALLERY) },
                    onRetry = acquisitionController::reset,
                    onCancel = ::leaveAcquisition,
                    onContinue = {},
                    onChooseAnother = ::discardAndReset
                )
            } else {
                CategorizerApp(
                    repository = albumRepository,
                    onAddSighting = {
                        acquisitionController.reset()
                        showingAcquisition = true
                    }
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHOWING_ACQUISITION, showingAcquisition)
        saveState(outState, acquisitionState)
    }

    override fun onDestroy() {
        acquisition.close()
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

    private fun leaveAcquisition() {
        discardAndReset()
        showingAcquisition = false
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
