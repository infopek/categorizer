package categorizer.app

import categorizer.domain.ManagedImageRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AcquisitionFlowTest {
    @Test
    fun repeatedTapCannotCreateSecondLaunch() {
        val controller = AcquisitionFlowController {}
        assertNotNull(controller.request(AcquisitionSource.CAMERA))
        assertNull(controller.request(AcquisitionSource.GALLERY))
        assertIs<AcquisitionScreenState.Launching>(controller.state)
    }

    @Test
    fun staleResultCannotReplaceCurrentRequest() {
        val controller = AcquisitionFlowController {}
        val first = assertNotNull(controller.request(AcquisitionSource.GALLERY))
        controller.reset()
        val second = assertNotNull(controller.request(AcquisitionSource.CAMERA))
        controller.processing(second, AcquisitionSource.CAMERA)

        controller.completed(first, ManagedImageRef("stale", "images/stale.jpg"))
        assertEquals(AcquisitionScreenState.Processing(AcquisitionSource.CAMERA), controller.state)
    }

    @Test
    fun successOnlyReachesReviewAfterProcessing() {
        val controller = AcquisitionFlowController {}
        val token = assertNotNull(controller.request(AcquisitionSource.GALLERY))
        controller.processing(token, AcquisitionSource.GALLERY)
        val image = ManagedImageRef("managed", "images/managed.jpg")
        controller.completed(token, image)
        assertEquals(AcquisitionScreenState.Review(image), controller.state)
    }

    @Test
    fun cancelAndFailureAreRecoverable() {
        val controller = AcquisitionFlowController {}
        val cancelToken = assertNotNull(controller.request(AcquisitionSource.CAMERA))
        controller.cancelled(cancelToken)
        assertIs<AcquisitionScreenState.Choosing>(controller.state)

        val failedToken = assertNotNull(controller.request(AcquisitionSource.GALLERY))
        controller.failed(failedToken, "Unreadable image", true)
        assertEquals(AcquisitionScreenState.Error("Unreadable image", true), controller.state)
    }
}
