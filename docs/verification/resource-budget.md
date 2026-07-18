# Bundled Application and Model Resource Budget

Status: accepted by the repository owner on 2026-07-13 under `DEC-003`.

## Scope and intent

These budgets apply to the Google Play closed-testing Android build with one offline ONNX model bundled at install time. They constrain the release candidate without requiring a particular network architecture or quantization method.

An optimization target guides model and application work. Missing a target requires investigation and a recorded explanation, but does not by itself reject a build. A hard gate is the inclusive maximum for release; exceeding it blocks release unless the repository owner records an explicit exception with the measured value and rationale.

## Proposed budgets

| Resource | Optimization target | Hard gate | What is measured |
|---|---:|---:|---|
| Bundled ONNX file | 80 MiB | 150 MiB | Uncompressed bytes of the exact ONNX artifact bundled in the build |
| Play compressed download | 150 MB | 200 MB | Largest compressed download reported for any supported device configuration |
| Installed footprint | 300 MiB | 500 MiB | Total app footprint after first successful recognition, including extracted or duplicated model data |
| Peak runtime memory | 768 MiB | 1,024 MiB | Peak application `TOTAL PSS` during the accepted 100-request warm sequence |
| Inference working memory | 512 MiB | 768 MiB | Peak `TOTAL PSS` minus idle pre-session `TOTAL PSS` in the same process |

`MB` means 1,000,000 bytes, matching store reporting. `MiB` means 1,048,576 bytes. File, download, installed, and memory sizes must not be compared without preserving these units and definitions.

The fifth metric makes inference overhead visible even when unrelated application state raises the process baseline. The four task-required gates remain the ONNX file, compressed download, installed footprint, and peak runtime memory.

## Why the download gate is 200 MB

As checked on 2026-07-13, Google Play documents a 500 MB compressed-download limit for a base module and a non-blocking mobile-data warning above 200 MB. The 200 MB project gate is therefore an install-UX choice, not the current Play platform maximum. Google recommends staying well below its maximum.

Source: [Google Play Console Help: Optimize your app's size and stay within Google Play app size limits](https://support.google.com/googleplay/android-developer/answer/9859372?hl=en).

If Play changes its limits, update the external context separately. Do not silently loosen the accepted project gate.

## Required measurement checklist

Store release evidence under `verification/release/results/` and identify the Git commit, version code/name, build variant, application artifact SHA-256, ONNX SHA-256, model manifest, device model/ABI, commands and tool versions.

### Bundled ONNX file

1. Locate the exact model referenced by the release manifest before packaging.
2. Record its byte count and SHA-256; convert bytes to MiB without rounding down.
3. Inspect the generated APK set and confirm the same model is present. Report any transformed, split, or duplicated copy separately.

Example tools: `stat`, `sha256sum`, and Android Studio APK Analyzer or `apkanalyzer`.

### Play compressed download

1. Build the signed release AAB.
2. Before upload, estimate device-specific delivery with the pinned `bundletool` version and a representative device specification, including the accepted benchmark device ABI.
3. For the closed-test candidate, record Play Console's maximum compressed download across supported configurations. Play Console is authoritative when its value differs from the local estimate.
4. Record base and configuration APK contributions so ABI-specific native libraries are not hidden.

The AAB file's raw size and a universal APK's size are not substitutes for the device-specific compressed download metric.

### Installed footprint

1. Clean-install the release candidate on the accepted benchmark device.
2. Record the package footprint before first launch.
3. Launch, create the ONNX session, and complete one successful recognition without adding album photos to the measurement.
4. Record the package's code, data, and cache bytes using `adb shell dumpsys package <package>` plus a documented package-storage inspection method available on the test build/device.
5. Report the post-recognition total as the gate value, and report cache separately. If Android prevents a direct component measurement, record Play Console installed size and the device-observable total; use the larger value for the gate.
6. Investigate model duplication caused by asset extraction or runtime optimization.

Collect the device-observable code, data, cache, and total values with:

```bash
python3 verification/release/measure_installed_footprint.py \
  --adb "$ANDROID_SDK_ROOT/platform-tools/adb"
```

The command prefers aligned package arrays from `dumpsys diskstats`. When a newly sideloaded
debuggable build is not yet present there, it falls back to package-path `du` plus private `run-as
du`; release builds must use `diskstats` or the Play Console because `run-as` is intentionally
unavailable. The command reads the accepted target and hard gate from `resource-budgets.json` and
exits nonzero when the installed total exceeds the hard gate. Record whether the measurement is
before launch or after a successful recognition; the latter is the release gate value.

The preliminary debug-device result is recorded in
`verification/release/results/2026-07-18-debug-installed-footprint.json`. It passes the hard gate but
misses the optimization target because ONNX Runtime requires a private filesystem copy in addition
to the model bundled in the APK. It is diagnostic evidence only; the signed release candidate still
requires the complete procedure above.

### Runtime memory

Follow the accepted `DEC-002` warm protocol. Capture `adb shell dumpsys meminfo <package>` at idle before session creation, immediately after session creation, periodically during the 100 measured requests, and immediately after the sequence. A profiler may supplement but not replace the recorded `dumpsys meminfo` samples.

Use the largest observed `TOTAL PSS` as peak runtime memory. Subtract the idle pre-session `TOTAL PSS` from that same run for inference working memory. Record Java heap, native heap, graphics, and other categories for diagnosis. Repeat the sequence three times as required by `DEC-002`; report unexplained monotonic growth even when the numeric gate passes.

## Gate result

For each metric, retain the raw value, normalized value, target result, hard-gate result, tool output, and evidence path. The release result fails if any hard gate is exceeded or evidence is missing. It may pass with a missed optimization target only when the report explains the cause and planned follow-up.

The machine-readable proposal is `verification/release/resource-budgets.json`; validate it with:

```bash
python3 verification/release/validate_resource_budgets.py
```

## Human decision

The repository owner accepted these budgets on 2026-07-13. Any later change to a target, hard gate, metric definition, or measurement method requires another explicit decision.
