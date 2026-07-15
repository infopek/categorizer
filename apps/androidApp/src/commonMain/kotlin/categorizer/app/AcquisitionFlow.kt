package categorizer.app

import categorizer.domain.ManagedImageRef

enum class AcquisitionSource { CAMERA, GALLERY }

sealed class AcquisitionScreenState {
    data class Choosing(val message: String? = null) : AcquisitionScreenState()
    data class Launching(val source: AcquisitionSource) : AcquisitionScreenState()
    data class Processing(val source: AcquisitionSource) : AcquisitionScreenState()
    data class Review(val image: ManagedImageRef) : AcquisitionScreenState()
    data class Error(val message: String, val recoverable: Boolean) : AcquisitionScreenState()
}

/** Small state machine that keeps launches and stale results deterministic and restorable. */
class AcquisitionFlowController(
    initialState: AcquisitionScreenState = AcquisitionScreenState.Choosing(),
    private val onChanged: (AcquisitionScreenState) -> Unit
) {
    var state: AcquisitionScreenState = initialState
        private set
    private var requestToken = if (
        initialState is AcquisitionScreenState.Launching || initialState is AcquisitionScreenState.Processing
    ) 1L else 0L
    val activeToken: Long get() = requestToken

    fun request(source: AcquisitionSource): Long? {
        if (state !is AcquisitionScreenState.Choosing && state !is AcquisitionScreenState.Error) return null
        requestToken += 1
        publish(AcquisitionScreenState.Launching(source))
        return requestToken
    }

    fun processing(token: Long, source: AcquisitionSource) {
        if (token == requestToken && state == AcquisitionScreenState.Launching(source)) {
            publish(AcquisitionScreenState.Processing(source))
        }
    }

    fun completed(token: Long, image: ManagedImageRef) {
        if (token == requestToken && state is AcquisitionScreenState.Processing) {
            publish(AcquisitionScreenState.Review(image))
        }
    }

    fun cancelled(token: Long) {
        if (token == requestToken) publish(AcquisitionScreenState.Choosing("No photo was selected."))
    }

    fun failed(token: Long, message: String, recoverable: Boolean) {
        if (token == requestToken) publish(AcquisitionScreenState.Error(message, recoverable))
    }

    fun reset(message: String? = null) {
        requestToken += 1
        publish(AcquisitionScreenState.Choosing(message))
    }

    private fun publish(updated: AcquisitionScreenState) {
        state = updated
        onChanged(updated)
    }
}
