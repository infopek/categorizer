# Categorizer Requirements

Status: accepted intake; Lepidoptera MVP amendment accepted by the repository owner on 2026-07-17 and pending pull-request review and merge.

## Goal

Create a mobile-first application that identifies butterflies and moths from camera or gallery photos entirely on-device and lets casual users build a private personal collection. Preserve the completed car-category foundations for later use without shipping car recognition in the MVP.

## Target users

- Casual users and nature enthusiasts who are curious about butterflies and moths they encounter and want to collect identified sightings.

## MVP

### Must have

- Android 10 or newer on recent mid-range devices with approximately 4 GB RAM or more.
- Camera capture and gallery import.
- Fully on-device recognition using a model bundled with the application.
- The 162 Austrian Lepidoptera dataset classes with at least 50 expert-validated images, subject to model and evaluation gates.
- Species-level identification, retaining combined labels for source-defined species pairs that cannot be distinguished reliably from photographs.
- Ranked recognition candidates with manual correction when none is correct.
- Private app-managed photo copies with unnecessary metadata removed.
- A local-only album containing the photo, confirmed identity, date, favorite state, and personal notes.
- Album search and filtering.
- Manual export and import for backup or transfer.
- Distribution through Google Play closed testing.

### Explicitly postponed

- iOS and desktop applications.
- Car recognition and the prepared 200-class car catalog.
- Butterfly and moth species outside the accepted Austrian dataset.
- Downloadable model or category packs.
- Accounts, cloud backup, and synchronization.
- Social features, sharing, location tracking, statistics, badges, and gamification.
- Telemetry and user-photo uploads for model training.
- Public Google Play release.

## Platforms and stack constraints

- Kotlin Multiplatform and Compose Multiplatform provide the application foundation.
- ONNX Runtime performs on-device inference.
- Python, PyTorch, and TorchVision provide model development and fine-tuning, followed by ONNX export and runtime verification.
- Platform-specific camera, storage, and inference integrations must remain behind clear adapters.
- Every exported model must be validated for operator compatibility, output equivalence, size, memory use, and latency on Android.

## Working style and proof

- One human coordinates two or three AI agents across independently scoped application, ML pipeline, and validation work.
- Prefer small tasks and focused pull requests with explicit contracts between workstreams.
- Require relevant automated tests and static checks.
- Compare ranked model outputs between the training environment and Android runtime within documented tolerances.
- Report held-out top-five accuracy and per-class results.
- Benchmark median inference latency on one selected mid-range Android device.
- Demonstrate closed-track installation, offline recognition, album persistence, export, and import.

## Safety boundaries and non-goals

- No LLM, subscription, remote inference service, or network dependency for recognition.
- Keep collection data and inference local to the device.
- Remove unnecessary metadata, including location metadata, from app-managed image copies.
- Do not upload photos, corrections, or collection data for training or analytics.
- Do not collect accounts, credentials, or cloud data in the MVP.
- Use only datasets, model weights, and images with verified compatible licenses.
- Preserve CC BY 4.0 attribution and artifact provenance for every consumed Figshare dataset or checkpoint file.
- Keep the MVP catalog to the 162 sufficiently represented source classes unless a reviewed amendment changes that boundary.

## Assumptions

- Scientific species names are canonical; common names are optional display metadata.
- Source-defined visually inseparable species pairs remain combined.
- Manual corrections become album metadata and do not trigger automatic retraining.
- The app may report unsupported or uncertain input instead of forcing a confident result.
- Export and import use a documented, versioned format suitable for future migration.
- Development builds may precede the required Google Play closed-testing milestone.

## Open questions

- Can the provided MaxViT-T checkpoint pass provenance, ONNX compatibility, application-size, memory, and Galaxy S20 FE latency gates, or must it be distilled?
- Which specific mid-range Android device will anchor the latency benchmark?
- What maximum bundled application and model size is acceptable for closed testing?

## Acceptance signals

- On a held-out representative test set, the correct supported Lepidoptera class appears within the top five candidates at least 80% of the time.
- Per-class results are reported so frequent classes cannot conceal weak supported classes.
- Median on-device inference is below two seconds on the selected benchmark device.
- Camera and gallery photos of supported butterflies and moths can be classified offline, confirmed or corrected, and saved.
- Album entries survive restarts and can be searched, filtered, favorited, annotated, exported, and imported.
- Stored app-managed photos omit unnecessary metadata.
- A signed build containing the bundled model installs through Google Play closed testing.
