# Album archive export and import

The Android archive adapter implements version `1.0.0` of the accepted album archive contract.
Export reads only domain album entries and app-managed images. ZIP image members are stored without
recompression and the manifest contains logical IDs, portable image paths, byte sizes, media types,
and SHA-256 checksums—never database details, source URIs, cache paths, or absolute device paths.

Import is deliberately two-phase. `validate` checks the exact version and layout, safe paths,
member uniqueness, counts, one-to-one image references, extensions/media types, stored compression,
declared and observed sizes, CRC-readable bytes, checksums, and the 10,000-file/50 MiB/10 GiB limits.
It then returns a preview and immutable plan bound to both the archive SHA-256 and a local album
revision. No album or durable image is mutated during validation.

Local entry or image conflicts abort the whole archive by default. An explicit `KEEP_EXISTING`
decision skips the entry and its image as one pair. Version 1 never overwrites, merges, or renames.
A changed archive or album after preview invalidates confirmation with `STALE_PLAN`.

Commit extracts into private cache staging, fsyncs each image, writes a recovery journal, installs
new private files, and batch-inserts metadata in one SQLite transaction. Any reported failure
removes installed files and leaves metadata unchanged. On startup, constructing the archive service
processes a leftover journal before import/export UI is exposed: if all metadata committed it keeps
the files; otherwise it removes the planned new files. Pre-existing files are never journal targets.

Verification:

```shell
python3 verification/contracts/archive/validate_archive_contract.py
./gradlew :apps:androidApp:connectedDebugAndroidTest
```

The device suite covers byte-equivalent round trips, every canonical negative mutation, default and
keep-existing conflicts, stale plans, and injected failure after filesystem installation. It runs
on the accepted Samsung Galaxy S20 FE (`SM-G780G/DS`) Android 13 reference phone.
