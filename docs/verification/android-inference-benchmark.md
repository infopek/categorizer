# Android Inference Benchmark Protocol

Status: accepted by the repository owner on 2026-07-13 under `DEC-002`.

## Benchmark device

The repository owner confirmed physical access to:

- Samsung Galaxy S20 FE;
- model `SM-G780G/DS`;
- 6 GB RAM;
- Android 13;
- One UI 5.1.

The machine-readable record is `verification/device/benchmark-device.json`. Chipset, ABI, build fingerprint, and security-patch values must be captured from the physical device at execution time rather than inferred from retail listings.

## Acceptance metric

The MVP gate is:

> Median warm inference-only latency must be strictly below 2,000 ms on the accepted device.

Cold start, decoding, preprocessing, end-to-end latency, memory, and package size are still reported, but they do not replace the accepted inference-only gate.

## Required build and fixture identity

Record before each benchmark:

- Git commit and application version;
- release/debug build type and signing mode;
- APK/AAB and bundled ONNX SHA-256 hashes;
- model-manifest and class-map versions/hashes;
- fixture-set manifest and hash;
- ONNX Runtime version and enabled execution providers;
- device properties listed in `runtime_capture_required`;
- battery percentage, charging state, power-saving state, and thermal status.

Use a fixed, locally stored fixture set containing at least 30 distinct valid images. Include multiple resolutions and orientations. The fixture manifest must identify the managed input bytes so image selection cannot drift between runs.

## Required benchmark controls

The benchmark is valid when it:

1. records the exact application/model build and device properties;
2. uses the same versioned fixture set;
3. performs the defined warm-up and measured run counts;
4. records battery, charging, power-mode, and thermal state so abnormal results can be identified;
5. reports any inference failures or excluded samples rather than silently dropping them.

Severe thermal throttling invalidates the affected samples and must be reported.

## Recommended normalization

For easier comparison between repeated runs, prefer to:

- reboot and allow startup activity to settle;
- close unrelated foreground applications;
- disable battery saver and unusual performance-enhancing modes;
- benchmark unplugged at a moderate battery level;
- keep room temperature stable;
- preserve adequate free storage;
- install cleanly for cold-start measurements.

These are recommendations, not application requirements or automatic pass/fail conditions. Record deviations with the results. Airplane mode is not required for performance benchmarking; it belongs to the separate offline-functionality verification in `VER-005`.

## Property capture

With Android platform tools available, capture at minimum:

```bash
adb shell getprop ro.product.model
adb shell getprop ro.product.device
adb shell getprop ro.build.fingerprint
adb shell getprop ro.soc.manufacturer
adb shell getprop ro.soc.model
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.security_patch
adb shell dumpsys battery
adb shell dumpsys thermalservice
```

Save raw output beside the benchmark report. If a property is blank, keep the blank value and record the alternative inspection method; do not guess.

## Timing boundaries

Instrument monotonic timestamps in the Android adapter for:

- model bundle validation;
- ONNX session creation;
- image decode;
- orientation normalization and resize/normalization;
- `session.run` inference only;
- top-k postprocessing;
- total recognition request.

The acceptance metric uses only `session.run` elapsed time. Every record must also contain image fixture ID, run sequence, cold/warm flag, success/error, model version, and thermal status when available.

## Cold protocol

Perform 10 independent cold trials:

1. Force-stop the application.
2. Clear only runtime caches documented as safe; do not delete the bundled model.
3. Launch and run one fixed recognition fixture.
4. Record bundle validation, session creation, preprocessing, inference, postprocessing, and total duration.
5. Record thermal state before the next trial; pause when needed to avoid measuring severe throttling.

Report all raw samples plus median, p90, p95, minimum, and maximum. Cold numbers characterize startup and extraction behavior; they are not substituted for the warm gate.

## Warm protocol

1. Create and retain one validated ONNX session.
2. Run 10 unreported warm-up inferences spanning at least five fixtures.
3. Run 100 measured inference requests, cycling deterministically through at least 30 fixtures.
4. Do not include failed requests in latency statistics; report every failure separately and fail the benchmark if any failure is unexplained.
5. Capture thermal state before, after 50 samples, and after 100 samples.

Report raw samples and median, p90, p95, minimum, maximum, and arithmetic mean for preprocessing, inference-only, postprocessing, and total request time. The run passes the latency gate only when warm inference-only median is below 2,000 ms.

## Memory and stability

Record application memory before session creation, after session creation, and at peak during 100 measured requests using one documented Android memory tool. Repeat the 100-request sequence three times without recreating the process; unexplained monotonic native-memory growth is a failure requiring investigation.

## Result artifact

Store a machine-readable report under `verification/device/results/` containing:

- all identities and device properties;
- raw timing samples;
- summary statistics;
- memory observations;
- failures/exclusions and reasons;
- thermal observations;
- explicit pass/fail for the median inference gate.

The report must be reproducible from the raw samples. `VER-003` independently repeats the protocol for the release candidate.

## Current limitation

ADB is not installed or connected in the current AI execution environment, so no dry run was performed during `DEC-002`. This does not change the accepted physical device; execution tasks must capture the missing runtime properties and raw measurements on the actual phone.
