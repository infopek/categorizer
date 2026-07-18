# Shared Domain Contracts

Status: draft implementation for `CONTRACT-001`, pending review.

## Boundary

`shared/domain` contains platform-neutral Kotlin contracts consumed by the application, persistence, and ML runtime lanes. It must not import Android, database, UI, filesystem, or ONNX Runtime types.

Recognition candidates and album entries use the category-neutral `CategoryIdentity`
contract documented in `category-identity-contract.md`. The `(categoryId, classId)`
pair is the stable identity boundary; scientific and display names have distinct
roles.

## Identity and recognition

`CarIdentity.classId` is the stable key; display names are presentation data. Catalog identities use `MODEL_CATALOG`. Manual corrections use `USER_CONFIRMED` and remain album metadata, not training input. Generation and approximate-year labels are optional; trim is outside the MVP.

Recognition returns completed, failed, or cancelled outcomes. Completed results explicitly distinguish candidates, uncertainty, and unsupported input. Candidate ranks start at one, remain contiguous, contain unique class IDs, and match the result model version. Unsupported results contain no candidates. Scores are finite model outputs and are not calibrated percentages.

## Album

`AlbumRepository` owns restart-safe persistence and transactions behind platform-neutral get, query, observation, create, update, and delete operations. Durable entries reference private app-managed relative image paths. External gallery URIs, absolute paths, database entities, platform handles, and ONNX tensors must not cross the domain boundary.

Deletion returns the managed image reference so adapters can implement and prove cleanup. Album dates use `YYYY-MM-DD`; instants use epoch milliseconds to avoid a shared date-time dependency. A positive schema version provides the persistence/archive migration hook.

Breaking field or semantic changes require reviewed migration and fixture updates.

## Verification

Until `APP-001` creates the Gradle project, compile and run the contract checks directly:

```bash
kotlinc \
  shared/domain/src/commonMain/kotlin/categorizer/domain/RecognitionContracts.kt \
  shared/domain/src/commonMain/kotlin/categorizer/domain/AlbumContracts.kt \
  shared/domain/src/commonTest/kotlin/categorizer/domain/ContractChecks.kt \
  -include-runtime -d /tmp/categorizer-domain-contracts.jar
java -jar /tmp/categorizer-domain-contracts.jar
```

`APP-001` must incorporate these source sets and preserve their platform-neutral boundary.
