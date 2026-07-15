# Camera and gallery acquisition flow

UI-002 connects the album's **Add** action to a shared source chooser and the Android acquisition
adapters from APP-003. Camera capture uses `ActivityResultContracts.TakePicture`; gallery selection
uses the system photo picker. Neither path requests camera, broad media, or external-storage
permission.

The flow has explicit choosing, launching, processing, review, and recoverable-error states.
Repeated taps cannot create a second launch, and request tokens prevent stale results from replacing
the current request. Camera launch state and pending files survive activity recreation. If recreation
interrupts image sanitization, the flow reports a recoverable error instead of implying success.

Successful inputs are sanitized into private managed storage before the recognition handoff screen
appears. Canceling or choosing another photo deletes an unconfirmed managed copy. No album entry is
created by acquisition or review; saving remains an explicit later recognition-review action.

## Device verification

The instrumentation suite exercises source chooser navigation, activity recreation, system photo
picker cancellation, camera contract availability and pending-file cleanup, recoverable errors, and
manifest permissions on the Samsung SM-G780G running Android 13.

- [Source chooser](screenshots/ui-002/chooser.png)
- [Recoverable input error](screenshots/ui-002/recoverable-error.png)

Run verification with:

```sh
./gradlew :apps:androidApp:testDebugUnitTest
./gradlew :apps:androidApp:connectedDebugAndroidTest
```
