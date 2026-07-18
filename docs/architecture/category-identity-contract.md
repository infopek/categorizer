# Category identity contract

`CategoryIdentity` is the versioned, platform-neutral identity used by recognition
candidates and album entries. Its stable key is the pair `(categoryId, classId)`;
display names are never keys.

Required fields are:

- a lowercase stable `categoryId` such as `lepidoptera` or `cars`;
- a category-scoped stable `classId`;
- `scientificName` for every model-catalog identity;
- a non-empty user-facing `displayName`;
- an explicit `IdentitySource`.

Optional alternate names are unique non-blank strings. Category-specific information
belongs in the string `attributes` map rather than required domain fields. A manually
confirmed identity may omit a scientific name because the user may only know a common
description.

Recognition results require category/class pairs to be unique. `CANDIDATES` requires
at least one candidate, while `UNSUPPORTED` requires none. These states remain
explicit and are not encoded as sentinel identities.

## Version-1 car migration

A version-1 car database or archive record decodes as:

- `categoryId = "cars"`;
- `scientificName = "$make $model"`;
- generation and approximate years in category-neutral `attributes`;
- the original stable class ID, display name, and source unchanged.

Compatibility is implemented inside the versioned database and archive readers, not
as car-shaped domain APIs. This keeps existing albums and version-1 archives readable
without allowing new application code to depend on make/model fields.

The compatibility path must not be used for new Lepidoptera model output. The Android
ONNX adapter reads the bundle-level `category_id`, and the version-2 Android database
persists `categoryId`, `scientificName`, alternate names, and attributes directly.
Opening a version-1 car database migrates its legacy identity fields without losing
generation or approximate-year metadata.

Archive exports use the category-neutral version-2 schema. The explicit version-1
reader maps legacy car fields into `CategoryIdentity`, keeping existing backups
readable without using deprecated car views for new data.
