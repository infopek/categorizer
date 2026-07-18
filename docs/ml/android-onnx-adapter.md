# Android ONNX Runtime adapter

`AndroidOnnxRecognitionEngine` implements the shared contract without exposing Android or ONNX types. It lazily validates and loads an approved bundle from `assets/recognition`, checks model/class-map hashes and tensor metadata, reuses one CPU session behind a mutex, and closes native resources explicitly. Images are resolved only from app-private managed storage, bounded by the acquisition pipeline, resized/cropped/normalized according to the manifest, and converted to fixed NCHW float32 tensors.

Cancellation is checked before preprocessing, before native inference, and before returning results; the coordinator's request token prevents stale results after cancellation or recreation. Missing bundles/native libraries, corrupt metadata, unreadable images, memory exhaustion, and inference failures map to structured domain errors. There is no network permission, fallback, download, telemetry, or on-device training.

Generated model binaries remain ignored. Gradle automatically includes a local bundle from
`ml/artifacts/lepidoptera/android-assets/recognition`; CI or alternate builds can pass
`-PrecognitionAssetRoot=<absolute-directory>`. Debug builds without a bundle honestly return
`MODEL_UNAVAILABLE`, while release builds fail before packaging rather than silently shipping
without offline recognition.
