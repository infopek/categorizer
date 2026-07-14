# Contract Fixture and Fake Adapter Handoff

Status: draft implementation for `CONTRACT-004`, pending review.

## Consumer entry points

Application tests compile `shared/domain/src/commonTestFixtures` alongside `shared/domain/src/commonMain`. `ContractFixtures` supplies stable car identities, managed-image references, equal-score candidates, uncertain and unsupported results, a recoverable failure, cancellation, and album entries including a user-confirmed label outside the model catalog.

`DeterministicRecognitionEngine` maps image IDs to fixed outcomes and records received inputs. Unknown image IDs fail deterministically. It performs no decoding, ONNX loading, timing, thresholding, or production fallback.

`InMemoryAlbumRepository` implements the accepted repository interface with deterministic filtering, sorting, observation, and CRUD behavior. It is process-local test state: it provides no restart safety, transactions, filesystem cleanup, database behavior, fault recovery, or concurrency guarantee. Production acceptance must never use it as persistence evidence.

Until `APP-001` adds Gradle source sets, compile the fakes directly with `kotlinc`. `APP-001` owns wiring `commonTestFixtures` for application tests without packaging it in production artifacts.

## Canonical JSON fixtures

Model-manifest consumers use only:

- `verification/contracts/model-bundle/fixtures/valid/class-map.json`
- `verification/contracts/model-bundle/fixtures/valid/model-manifest.json`
- `verification/contracts/model-bundle/fixtures/invalid/*.json`

Archive consumers use only:

- `verification/contracts/archive/fixtures/valid/archive-manifest.json`
- `verification/contracts/archive/fixtures/valid/image-payload.json`
- `verification/contracts/archive/fixtures/invalid/cases.json`

The contract validators are the canonical interpreters of negative cases. Some negative JSON files are schema-valid but semantically invalid by design; archive ZIP corruptions are generated in a temporary directory so malformed binaries are not duplicated or committed. Consumers must reuse these paths or generate test artifacts from them rather than maintain lane-specific copies.

## Ownership and review

The Contract Steward owns fixture meaning, stable IDs, and compatibility. A fixture change requires review from every affected application, ML, or verification consumer and must update its validator/check plus this handoff when paths or semantics change. Changes to class order, archive layout, preprocessing, output interpretation, stable IDs, or expected failure category are contract changes, not routine test-data refreshes.

Fixtures contain synthetic metadata and a generated one-pixel PNG only. No third-party image, dataset sample, model weight, credential, device path, location, or account metadata may be added without an explicit license and privacy review.

## Verification

Run:

```bash
kotlinc \
  shared/domain/src/commonMain/kotlin/categorizer/domain/RecognitionContracts.kt \
  shared/domain/src/commonMain/kotlin/categorizer/domain/AlbumContracts.kt \
  shared/domain/src/commonTestFixtures/kotlin/categorizer/domain/testing/ContractFixtures.kt \
  shared/domain/src/commonTestFixtures/kotlin/categorizer/domain/testing/DeterministicRecognitionEngine.kt \
  shared/domain/src/commonTestFixtures/kotlin/categorizer/domain/testing/InMemoryAlbumRepository.kt \
  shared/domain/src/commonTest/kotlin/categorizer/domain/FakeAdapterChecks.kt \
  -include-runtime -d /tmp/categorizer-fake-adapters.jar
java -jar /tmp/categorizer-fake-adapters.jar
python3 verification/contracts/model-bundle/validate_model_bundle_contract.py
python3 verification/contracts/archive/validate_archive_contract.py
```
