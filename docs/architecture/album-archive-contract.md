# Versioned Album Archive Contract

Status: draft implementation for `CONTRACT-003`, pending review.

## Container and layout

An archive is a ZIP file with this exact logical layout:

```text
manifest.json
images/<safe-image-id>.<jpg|jpeg|png|webp>
```

`manifest.json` conforms to `verification/contracts/archive/album-archive.schema.json`, which covers readable versions 1 and 2. Image entries use `ZIP_STORED` because managed JPEG, PNG, and WebP bytes are already compressed. The manifest may be stored or deflated. Directory entries, duplicate ZIP member names, backslashes, absolute paths, `.` or `..` segments, drive-qualified paths, symbolic links, and undeclared files are invalid.

The archive includes only app-managed image copies that have already passed the metadata-removal boundary. It never exports source gallery URIs, absolute device paths, cache paths, database keys, recognition candidates, or training feedback.

## Manifest model

The manifest records:

- format and archive schema version;
- logical archive ID and creation time;
- exporting application identity and version;
- declared entry and image counts;
- album fields that map to the shared `AlbumEntry` contract;
- complete confirmed identity data (`category_id`, `class_id`, nullable `scientific_name`, display and alternate names, category attributes, and source);
- logical image IDs, relative archive paths, media types, byte counts, and SHA-256 checksums.

Entries reference images by logical `image_id`; internal database paths and the archive's relative ZIP path are not persisted as the imported managed path. Import assigns a new private managed path while retaining the logical entry and image IDs.

Every entry has exactly one image, every image is referenced by exactly one entry, and neither IDs nor paths may repeat. Empty archives are valid.

## Validation limits

Validation occurs from a private staging location before any album mutation. Version 1 limits are:

- at most 10,000 entries and 10,000 images;
- at most 50 MiB per image;
- at most 10 GiB total declared and observed image bytes;
- at most 10 MiB for `manifest.json`;
- at most 240 characters per relative image path.

The importer compares declared and observed counts, paths, compression methods, sizes, CRC-readable bytes, and SHA-256 values. It rejects missing, extra, duplicate, corrupt, unsupported, or path-unsafe members. File extensions and declared media types must agree. These checks occur before image decoding or repository writes.

## Conflict policy

Duplicates inside one archive are always invalid. Conflicts against local state are handled only after structural validation:

1. The default decision for an incoming `entry_id` or `image_id` already present locally is `ABORT_ARCHIVE`.
2. The user may explicitly choose `KEEP_EXISTING` for each conflicting entry. That skips the incoming entry and its image as a pair.
3. Version 1 never overwrites, merges, silently renames, or replaces an existing entry or image.
4. A non-conflicting entry whose image ID conflicts remains blocking and cannot be imported independently.
5. Re-importing the same archive therefore aborts by default; choosing `KEEP_EXISTING` for every conflict produces a validated no-op.

The preview must identify all conflicts and the resulting import count before confirmation.

## Atomic import

Validation and commit are separate phases. Validation produces an immutable import plan containing the archive hash, accepted entry/image set, conflict decisions, required byte count, and target IDs. Confirmation is valid only for that exact plan and unchanged local-state revision.

Commit stages sanitized image bytes under temporary private names, starts one metadata transaction, installs final managed files, inserts all planned entries, and then commits. On any error it rolls back metadata, removes newly installed files, and leaves pre-existing files untouched. A small recovery journal is required around filesystem moves because a database and filesystem cannot share one native transaction. Startup recovery completes rollback or finalization before exposing imported entries.

No entry becomes observable until all planned images and metadata are durable. Invalid archives and rejected conflict plans cause no durable mutation.

## Compatibility policy

- Archive versions use `major.minor.patch`.
- Writers emit category-neutral version `2.0.0`; readers accept both `1.0.0` and `2.0.0`.
- Version 1 car identities are migrated on import to category `cars`, with generation and approximate years retained as attributes.
- A major version changes required meaning or layout and needs an explicit migration reader.
- A minor version may add optional data, but strict readers remain pinned until updated and tested.
- A patch version may clarify validation without changing serialized meaning.
- `entry_schema_version` is retained per entry so later application migrations do not change the archive's historical meaning.
- Imported identities retain their stable category/class pair and saved display fields even when the installed model catalog differs.

## Fixtures and validation

Run:

```bash
python3 verification/contracts/archive/validate_archive_contract.py
```

The suite builds ZIP archives in a temporary directory from checked-in fixture definitions. It proves a valid archive passes and checksum mismatch, missing files, duplicate IDs, incompatible versions, traversal paths, and duplicate ZIP members fail. It does not mutate an album repository.

The one-pixel PNG fixture contains no EXIF, location, device, account, or original-path metadata. `VER-004` must independently inspect real exported managed images and challenge the implemented atomicity and resource limits.
