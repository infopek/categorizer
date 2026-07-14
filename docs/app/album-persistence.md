# Album persistence

`AndroidAlbumRepository` is the production Android adapter for the platform-neutral
`AlbumRepository` contract. It uses an app-private SQLite database named
`categorizer-album.db`; no database, cursor, context, URI, or absolute-path type crosses the
domain boundary.

## Schema and migration baseline

The initial database version is `1`. The table stores the complete `AlbumEntry` value and only
the `ManagedImageRef.imageId` plus its validated relative path. It deliberately has no source
gallery URI, original path, EXIF, account, network, or recognition-candidate columns. Future
schema versions must add an incremental `onUpgrade` migration and an instrumentation fixture
covering the previous version before increasing `ALBUM_DATABASE_VERSION`.

All create, update, and delete calls are single SQLite transactions. Duplicate IDs map to
`DUPLICATE_ID`; absent IDs map to `NOT_FOUND`. Create and update resolve the relative path
strictly below the app-private files directory and return `IMAGE_MISSING` unless it names a
regular file. SQLite and mapping failures stay behind the repository as structured domain
errors. Search trims the query and applies locale-independent,
Unicode-aware case folding in Kotlin. Sorts always use the entry ID as a deterministic tie-breaker.

## Managed-image deletion policy

Album metadata owns only a reference; the private image store is implemented by APP-003.
Deleting an entry transactionally removes its metadata and returns the exact
`AlbumMutation.Deleted.removedImage`. The application must pass that reference to the managed
image store for idempotent cleanup. A missing image is therefore safe to treat as already clean,
and image cleanup must never restore deleted metadata. This separation prevents a filesystem
failure after a committed database mutation from corrupting the album transaction.

## Verification

Host checks:

```shell
./gradlew :apps:androidApp:testDebugUnitTest :apps:androidApp:lintDebug
./gradlew :apps:androidApp:assembleDebug :apps:androidApp:assembleDebugAndroidTest
```

With an Android 10+ device or emulator connected:

```shell
./gradlew :apps:androidApp:connectedDebugAndroidTest
```

The instrumentation contract test covers CRUD, observation, favorite and Unicode search,
deterministic sorting, blank notes, duplicate IDs, the returned cleanup reference, repository
recreation, schema version, and the absence of durable external-URI columns.

The suite was verified on the MVP reference phone, a Samsung Galaxy S20 FE (`SM-G780G/DS`)
running Android 13, with `connectedDebugAndroidTest` completing successfully on 2026-07-14.
