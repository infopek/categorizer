# Android image acquisition and sanitization

`AndroidImageAcquisition` owns the system photo-picker and camera activity-result contracts.
The photo picker requires no broad media permission and its returned URI is used only while
creating a private copy. Camera capture uses a non-exported `FileProvider` URI backed by a
temporary cache directory; a small pending marker allows result recovery after activity process
recreation. Cancellation and completed imports remove the temporary capture.

`ManagedImageStore` is the durable boundary. It reads dimensions before decoding, rejects source
dimensions above 100 megapixels, and chooses a power-of-two sample that keeps the decoded bitmap
within a 4096-pixel edge and 16 megapixels. EXIF orientation, including mirrored variants, is
applied to pixels. The bitmap is then JPEG-encoded into a random temporary file, flushed and
synced, and atomically moved to `files/images/<image-id>.jpg`. Re-encoding carries no source EXIF,
GPS, device, account, original-name, URI, or absolute-path metadata into the managed copy.

All failures are recoverable structured results. Temporary output is deleted in `finally`; camera
input is removed after success or failure. Managed deletion is idempotent and confined below the
app-private files directory. HEIC and other formats are accepted only when Android's platform
decoder can decode them.

Run host checks and build the instrumentation APK:

```shell
./gradlew check :apps:androidApp:assembleDebugAndroidTest
```

Run the media fixtures on the Android 10+ reference phone:

```shell
./gradlew :apps:androidApp:connectedDebugAndroidTest
```

The device suite creates rotated GPS/device-tagged JPEG, 24-megapixel, malformed, and cleanup
fixtures. It independently reopens the managed JPEG with the platform EXIF reader and records an
asserted heap delta below 256 MiB for the large fixture. Final release memory gates remain governed
by `docs/verification/resource-budget.md` and the accepted repeated device protocol.

The complete suite was verified on 2026-07-14 on the Samsung Galaxy S20 FE (`SM-G780G/DS`),
Android 13 reference device.
